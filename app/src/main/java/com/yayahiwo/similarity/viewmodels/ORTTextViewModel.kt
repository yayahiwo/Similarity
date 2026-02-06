package com.yayahiwo.similarity.viewmodels

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yayahiwo.similarity.ClipEngineModels
import com.yayahiwo.similarity.ClipEngineModelsSelector
import com.yayahiwo.similarity.normalizeL2
import com.yayahiwo.similarity.tokenizer.ClipTokenizer
import com.yayahiwo.similarity.tokenizer.ClipTokenizerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale

class ORTTextViewModel(application: Application) : AndroidViewModel(application) {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val clipModelsLock = Any()
    @Volatile private var clipModelsCached: ClipEngineModels? = null

    private fun clipModels(): ClipEngineModels {
        clipModelsCached?.let { return it }
        synchronized(clipModelsLock) {
            clipModelsCached?.let { return it }
            val out = ClipEngineModelsSelector.get(getApplication())
            clipModelsCached = out
            return out
        }
    }

    // CLIP-family text models commonly use 77 tokens.
    private val maxLen: Int = 77
    private val inputShape: LongArray = longArrayOf(1, maxLen.toLong())

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: ClipTokenizer? = null
    @Volatile private var inputIdsName: String? = null
    @Volatile private var attentionMaskName: String? = null
    @Volatile private var inputIdsType: OnnxJavaType? = null
    @Volatile private var attentionMaskType: OnnxJavaType? = null
    @Volatile private var initError: Throwable? = null
    @Volatile private var outputName: String? = null
    private val embeddingCacheLock = Any()
    private val embeddingCache =
        object : LinkedHashMap<String, FloatArray>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean {
                return size > 1024
            }
        }

    private val initLock = Any()

    // Match CLIP-style query normalization to avoid tokenization mismatches.
    private val queryFilter = Regex("[^A-Za-z0-9 ]")
    private val whitespaceFilter = Regex("\\s+")

    // NOTE: Do not prewarm by default.
    fun prewarmAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ensureInitialized() }
                .onFailure {
                    initError = it
                    Log.e("Similarity", "CLIP text init failed", it)
                }
        }
    }

    fun init() {
        // no-op
    }

    fun getTextEmbedding(text: String): FloatArray {
        ensureInitialized()

        val tok = tokenizer ?: error("Tokenizer missing")
        val sess = session ?: error("Text session missing")
        val idsName = inputIdsName ?: "input_ids"
        val maskName = attentionMaskName
        val idsType = inputIdsType
        val maskType = attentionMaskType

        val (ids, mask) = encodeToFixedLength(text, tok)

        val inputIdsTensor = createIdsTensor(ids, idsType)

        val attentionMaskTensor =
            if (maskName != null) {
                createMaskTensor(mask, maskType)
            } else {
                null
            }

        inputIdsTensor.use { idsTensor ->
            attentionMaskTensor?.use { maskTensor ->
                val inputMap = HashMap<String, OnnxTensor>(2)
                inputMap[idsName] = idsTensor
                maskName?.let { inputMap[it] = maskTensor }

                val output = sess.run(inputMap)
                output.use {
                    val outName = outputName ?: selectEmbeddingOutputName(sess).also { outputName = it }
                    val v =
                        if (outName.isNotBlank()) {
                            output.get(outName).orElse(null)?.value ?: output[0].value
                        } else {
                            output[0].value
                        }
                    val rawOutput: FloatArray = extractEmbedding(v)
                    normalizeL2(rawOutput)
                    return rawOutput
                }
            } ?: run {
                val inputMap = HashMap<String, OnnxTensor>(1)
                inputMap[idsName] = idsTensor
                val output = sess.run(inputMap)
                output.use {
                    val outName = outputName ?: selectEmbeddingOutputName(sess).also { outputName = it }
                    val v =
                        if (outName.isNotBlank()) {
                            output.get(outName).orElse(null)?.value ?: output[0].value
                        } else {
                            output[0].value
                        }
                    val rawOutput: FloatArray = extractEmbedding(v)
                    normalizeL2(rawOutput)
                    return rawOutput
                }
            }
        }
    }

    fun getTextEmbeddingCached(text: String): FloatArray {
        val key = normalizeQuery(text)
        if (key.isEmpty()) return FloatArray(0)
        synchronized(embeddingCacheLock) {
            embeddingCache[key]?.let { return it.clone() }
        }
        val emb = getTextEmbedding(key)
        synchronized(embeddingCacheLock) {
            embeddingCache[key] = emb
        }
        return emb.clone()
    }

    private fun createIdsTensor(ids: IntArray, type: OnnxJavaType?): OnnxTensor {
        return when (type ?: OnnxJavaType.INT64) {
            OnnxJavaType.INT64 -> {
                val buf = LongBuffer.allocate(maxLen)
                for (v in ids) buf.put(v.toLong())
                buf.rewind()
                OnnxTensor.createTensor(ortEnv, buf, inputShape)
            }
            OnnxJavaType.INT32 -> {
                val buf = IntBuffer.allocate(maxLen)
                buf.put(ids)
                buf.rewind()
                OnnxTensor.createTensor(ortEnv, buf, inputShape)
            }
            else -> throw IllegalStateException("Unsupported input_ids type: $type")
        }
    }

    private fun createMaskTensor(mask: IntArray, type: OnnxJavaType?): OnnxTensor {
        return when (type ?: OnnxJavaType.INT64) {
            OnnxJavaType.INT64 -> {
                val buf = LongBuffer.allocate(maxLen)
                for (v in mask) buf.put(v.toLong())
                buf.rewind()
                OnnxTensor.createTensor(ortEnv, buf, inputShape)
            }
            OnnxJavaType.INT32 -> {
                val buf = IntBuffer.allocate(maxLen)
                buf.put(mask)
                buf.rewind()
                OnnxTensor.createTensor(ortEnv, buf, inputShape)
            }
            else -> throw IllegalStateException("Unsupported attention_mask type: $type")
        }
    }

    private fun selectEmbeddingOutputName(session: OrtSession): String {
        val info = session.outputInfo
        if (info.isEmpty()) return session.outputNames.firstOrNull() ?: ""

        data class Candidate(val name: String, val score: Int)
        fun score(name: String, shape: LongArray): Int {
            val rank = shape.size
            val dim = (shape.lastOrNull() ?: -1L).toInt()
            var s = 0
            if (rank == 2) s += 1000
            if (rank == 3) s += 500
            if (name.contains("embed", ignoreCase = true)) s += 250
            if (name.contains("pool", ignoreCase = true)) s += 200
            if (name.contains("proj", ignoreCase = true)) s += 150
            if (dim in 128..4096) s += (dim.coerceAtMost(2048) / 8)
            return s
        }

        val candidates = info.mapNotNull { (name, node) ->
            val tensorInfo = node.info as? TensorInfo ?: return@mapNotNull null
            val shape = tensorInfo.shape ?: return@mapNotNull null
            Candidate(name = name, score = score(name, shape))
        }
        return candidates.maxByOrNull { it.score }?.name ?: (session.outputNames.firstOrNull() ?: "")
    }

    private fun extractEmbedding(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val batch0 = value.firstOrNull()
                when (batch0) {
                    is FloatArray -> batch0
                    is Array<*> -> {
                        val token0 = batch0.firstOrNull()
                        when (token0) {
                            is FloatArray -> token0
                            else -> throw IllegalStateException("Unexpected 3D element type: ${token0?.javaClass}")
                        }
                    }
                    else -> throw IllegalStateException("Unexpected batch element type: ${batch0?.javaClass}")
                }
            }
            else -> throw IllegalStateException("Unexpected text encoder output type: ${value?.javaClass}")
        }
    }

    private fun encodeToFixedLength(text: String, tok: ClipTokenizer): Pair<IntArray, IntArray> {
        val clean = normalizeQuery(text)
        val tokenIds = tok.encode(clean)

        // OpenAI CLIP convention.
        val bosTokenId = 49406
        val eosTokenId = 49407
        val padTokenId = 0

        val ids = IntArray(maxLen)
        ids.fill(padTokenId)
        val mask = IntArray(maxLen)

        var outIdx = 0
        fun put(id: Int) {
            if (outIdx >= maxLen) return
            ids[outIdx] = id
            mask[outIdx] = 1
            outIdx += 1
        }

        put(bosTokenId)
        for (id in tokenIds) {
            if (outIdx >= maxLen - 1) break
            put(id)
        }
        put(eosTokenId)

        return ids to mask
    }

    private fun normalizeQuery(text: String): String {
        val filtered = queryFilter.replace(text, " ")
        return whitespaceFilter.replace(filtered, " ").trim().lowercase(Locale.US)
    }

    private fun ensureInitialized() {
        initError?.let { throw IllegalStateException("CLIP text init failed", it) }
        if (session != null && tokenizer != null && inputIdsName != null) return

        synchronized(initLock) {
            initError?.let { throw IllegalStateException("CLIP text init failed", it) }
            if (session != null && tokenizer != null && inputIdsName != null) return

            val appCtx = getApplication<Application>()
            val models = clipModels()
            val sess = ortEnv.createSession(models.textModelFile.absolutePath)

            val inputs = sess.inputNames.toList()
            val idsName =
                inputs.firstOrNull { it.contains("id", ignoreCase = true) }
                    ?: inputs.firstOrNull()
                    ?: "input_ids"
            val maskName = inputs.firstOrNull { it.contains("mask", ignoreCase = true) }

            val idsType = (sess.inputInfo[idsName]?.info as? TensorInfo)?.type
            val maskType = maskName?.let { (sess.inputInfo[it]?.info as? TensorInfo)?.type }

            val tok = ClipTokenizerProvider.get(appCtx)

            session = sess
            tokenizer = tok
            inputIdsName = idsName
            attentionMaskName = maskName
            inputIdsType = idsType
            attentionMaskType = maskType
        }
    }

    fun resetForFullReset() {
        // Drop caches so we reselect models and recreate ORT session next time.
        synchronized(clipModelsLock) { clipModelsCached = null }
        initError = null
        outputName = null
        inputIdsName = null
        attentionMaskName = null
        inputIdsType = null
        attentionMaskType = null
        synchronized(embeddingCacheLock) { embeddingCache.clear() }
        try {
            session?.close()
        } catch (_: Exception) {
        } finally {
            session = null
        }
        tokenizer = null
    }
}
