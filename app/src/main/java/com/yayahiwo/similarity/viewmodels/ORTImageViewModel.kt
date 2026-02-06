package com.yayahiwo.similarity.viewmodels

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.Application
import android.content.ContentUris
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.lifecycle.*
import com.yayahiwo.similarity.ClipEngineModels
import com.yayahiwo.similarity.ClipEngineModelsSelector
import com.yayahiwo.similarity.SimilaritySettings
import com.yayahiwo.similarity.data.ImageEmbedding
import com.yayahiwo.similarity.data.ImageEmbeddingDatabase
import com.yayahiwo.similarity.data.ImageEmbeddingRepository
import com.yayahiwo.similarity.allocateModelInputBuffer
import com.yayahiwo.similarity.DIM_PIXEL_SIZE
import com.yayahiwo.similarity.IMAGE_SIZE_X
import com.yayahiwo.similarity.IMAGE_SIZE_Y
import com.yayahiwo.similarity.normalizeL2
import com.yayahiwo.similarity.preProcessIntoClipMeanStd
import com.yayahiwo.similarity.preProcessIntoUnitRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ORTImageViewModel(application: Application) : AndroidViewModel(application) {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val repository: ImageEmbeddingRepository
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

    @Volatile private var cpuSession: OrtSession? = null
    private val cpuSessionLock = Any()
    @Volatile private var cpuOutputName: String? = null
    @Volatile private var cpuBatchCap: Int? = null
    @Volatile private var cpuInputWidth: Int? = null
    @Volatile private var cpuInputHeight: Int? = null

    private fun configureCpuSessionOptions(opts: OrtSession.SessionOptions) {
        val prefs = SimilaritySettings.prefs(getApplication())
        val useOrtDefaultThreading =
            prefs.getBoolean(
                SimilaritySettings.KEY_CPU_USE_ORT_DEFAULT_THREADING,
                SimilaritySettings.DEFAULT_CPU_USE_ORT_DEFAULT_THREADING
            )
        val intraOverride =
            prefs.getInt(
                SimilaritySettings.KEY_CPU_INTRA_OP_THREADS,
                SimilaritySettings.DEFAULT_CPU_INTRA_OP_THREADS
            )

        fun invokeIntSetter(name: String, value: Int) {
            runCatching {
                opts.javaClass.methods.firstOrNull { m ->
                    m.name == name &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.java)
                }?.invoke(opts, value)
            }
        }

        fun tryAddSessionConfigEntry(key: String, value: String) {
            runCatching {
                opts.javaClass.methods.firstOrNull { m ->
                    (m.name == "addConfigEntry" || m.name == "addSessionConfigEntry") &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1] == String::class.java
                }?.invoke(opts, key, value)
            }
        }

        // Always apply safe CPU optimizations. Thread counts may still be left to ORT defaults.
        runCatching {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        runCatching {
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        // On some devices/workloads memory pattern optimization can reduce throughput (and it may
        // increase memory usage). Keep it off by default; benchmarking can validate otherwise.
        runCatching {
            opts.setMemoryPatternOptimization(false)
        }
        runCatching {
            opts.setCPUArenaAllocator(true)
        }

        if (useOrtDefaultThreading) return

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Leave some room for UI/IO while still using multiple cores for ORT kernels.
        val intra =
            if (intraOverride > 0) intraOverride.coerceIn(1, 64) else (cores - 1).coerceAtLeast(1)

        invokeIntSetter("setIntraOpNumThreads", intra)
        invokeIntSetter("setInterOpNumThreads", 1)
    }

    private fun getOrCreateCpuSession(): OrtSession {
        cpuSession?.let { return it }
        synchronized(cpuSessionLock) {
            cpuSession?.let { return it }
            val opts = OrtSession.SessionOptions()
            try {
                configureCpuSessionOptions(opts)
                val models = clipModels()
                val sess = ortEnv.createSession(models.visionModelFile.absolutePath, opts)
                cpuSession = sess
                cpuOutputName = selectEmbeddingOutputName(sess)
                cpuBatchCap = selectBatchCap(sess)
                val (w, h) = selectInputDims(sess)
                cpuInputWidth = w
                cpuInputHeight = h
                return sess
            } finally {
                runCatching { opts.close() }
            }
        }
    }

    var idxList: ArrayList<Long> = arrayListOf()
    var embeddingsList: ArrayList<FloatArray> = arrayListOf()
    var progress: MutableLiveData<Double> = MutableLiveData(0.0)
    var indexedCount: MutableLiveData<Int> = MutableLiveData(0)
    var isIndexing: MutableLiveData<Boolean> = MutableLiveData(false)
    val indexingError: MutableLiveData<String?> = MutableLiveData(null)
    private var indexingJob: Job? = null
    private var indexingRunId: Int = 0

    private companion object {
        private const val DB_ID_BATCH_SIZE = 750
        private const val INSERT_BATCH_SIZE = 100
        private const val MAX_MICRO_BATCH_HARD_CAP = 128
        private const val LOG_TAG = "Similarity"
        private const val PROGRESS_UPDATE_MIN_INTERVAL_MS = 100L
        private const val INDEXED_COUNT_UPDATE_MIN_INTERVAL_MS = 200L
        private const val INDEXED_COUNT_UPDATE_MIN_DELTA = 25
    }

    init {
        val imageEmbeddingDao = ImageEmbeddingDatabase.getDatabase(application).imageEmbeddingDao()
        repository = ImageEmbeddingRepository(imageEmbeddingDao)

        // If the embedding engine/model changed, the stored vectors are incompatible. Wipe the DB
        // and in-memory cache so the UI forces a reindex.
        val prefs = SimilaritySettings.prefs(application)
        val currentEngineId = runCatching { clipModels().engineId }.getOrNull()
        val previousEngineId = prefs.getString(SimilaritySettings.KEY_IMAGE_EMBEDDING_ENGINE_ID, null)
        if (currentEngineId != null && previousEngineId != currentEngineId) {
            idxList.clear()
            embeddingsList.clear()
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repository.clearAll() }
            }
            prefs.edit().putString(SimilaritySettings.KEY_IMAGE_EMBEDDING_ENGINE_ID, currentEngineId).apply()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqSizePx: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqSizePx || width > reqSizePx) {
            // Keep dividing by 2 until both dimensions are at most reqSizePx.
            while ((height / inSampleSize) > reqSizePx || (width / inSampleSize) > reqSizePx) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun decodeSampledBitmap(
        contentResolver: ContentResolver,
        uri: Uri,
        reqSizePx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val inSampleSize = calculateInSampleSize(bounds, reqSizePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }

        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            return BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOpts)
        }
        return null
    }

    private fun decodeBitmapForEmbedding(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Bitmap? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(IMAGE_SIZE_X, IMAGE_SIZE_Y), null)
            } else {
                // Slightly bigger than model input to preserve detail before center-crop+scale.
                decodeSampledBitmap(contentResolver, uri, 512)
            }
        } catch (_: Exception) {
            null
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

    private fun selectBatchCap(session: OrtSession): Int {
        val inputName =
            session.inputNames.firstOrNull { it.contains("pixel", ignoreCase = true) }
                ?: session.inputNames.firstOrNull()
                ?: return 1

        val node = session.inputInfo[inputName] ?: return 1
        val tensorInfo = node.info as? TensorInfo ?: return 1
        val shape = tensorInfo.shape ?: return 1
        if (shape.isEmpty()) return 1

        // If batch dim is dynamic (-1/0), we can micro-batch.
        return if (shape[0] <= 0L) {
            MAX_MICRO_BATCH_HARD_CAP
        } else {
            1
        }
    }

    private fun selectInputDims(session: OrtSession): Pair<Int, Int> {
        val inputName =
            session.inputNames.firstOrNull { it.contains("pixel", ignoreCase = true) }
                ?: session.inputNames.firstOrNull()
                ?: return IMAGE_SIZE_X to IMAGE_SIZE_Y

        val node = session.inputInfo[inputName] ?: return IMAGE_SIZE_X to IMAGE_SIZE_Y
        val tensorInfo = node.info as? TensorInfo ?: return IMAGE_SIZE_X to IMAGE_SIZE_Y
        val shape = tensorInfo.shape ?: return IMAGE_SIZE_X to IMAGE_SIZE_Y
        if (shape.size < 4) return IMAGE_SIZE_X to IMAGE_SIZE_Y

        val h = shape[2].toInt()
        val w = shape[3].toInt()
        if (w <= 0 || h <= 0) return IMAGE_SIZE_X to IMAGE_SIZE_Y
        return w to h
    }

    private fun getInputDims(): Pair<Int, Int> {
        return (cpuInputWidth ?: IMAGE_SIZE_X) to (cpuInputHeight ?: IMAGE_SIZE_Y)
    }

    private fun clampCpuMicroBatchForMemory(requested: Int, bytesPerImage: Long): Int {
        if (requested <= 1) return requested.coerceAtLeast(1)
        if (bytesPerImage <= 0L) return requested.coerceAtLeast(1)

        val rt = Runtime.getRuntime()
        val maxHeap = rt.maxMemory().coerceAtLeast(0L)
        val usedHeap = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        val availableHeap = (maxHeap - usedHeap).coerceAtLeast(0L)

        // The model input buffer is a large direct ByteBuffer, and ORT will allocate additional
        // internal memory. Keep a conservative budget to avoid OOM / direct-buffer failures.
        val budget = minOf(maxHeap / 3, availableHeap / 2).coerceAtLeast(8L * 1024L * 1024L)
        val maxByBudget = (budget / bytesPerImage).toInt().coerceAtLeast(1)
        val clamped = requested.coerceAtMost(maxByBudget).coerceAtLeast(1)
        if (clamped < requested) {
            Log.w(
                LOG_TAG,
                "Reducing CPU micro-batch from $requested to $clamped (bytesPerImage=$bytesPerImage budget=$budget)"
            )
        }
        return clamped
    }

    fun getRecommendedCpuMicroBatchMax(
        inputWidth: Int = IMAGE_SIZE_X,
        inputHeight: Int = IMAGE_SIZE_Y,
    ): Int {
        val bytesPerImage =
            DIM_PIXEL_SIZE.toLong() * inputWidth.toLong() * inputHeight.toLong() * 4L
        return clampCpuMicroBatchForMemory(MAX_MICRO_BATCH_HARD_CAP, bytesPerImage)
    }

    private fun getBatchCap(): Int {
        return cpuBatchCap ?: 1
    }

    private fun getOutputName(session: OrtSession): String {
        return cpuOutputName ?: selectEmbeddingOutputName(session).also { cpuOutputName = it }
    }

    private fun selectSession(): OrtSession {
        return getOrCreateCpuSession()
    }

    private fun getOutputTensor(output: OrtSession.Result, outName: String): OnnxTensor {
        val v = if (outName.isNotBlank()) output.get(outName).orElse(null) else null
        val outValue = v ?: output[0]
        return outValue as? OnnxTensor
            ?: throw IllegalStateException("Expected OnnxTensor output, got ${outValue.javaClass}")
    }

    private fun extractEmbeddingsFromTensor(
        tensor: OnnxTensor,
        batchSize: Int,
    ): Array<FloatArray> {
        val shape = tensor.info.shape ?: longArrayOf()
        val fb = tensor.floatBuffer.duplicate().apply { rewind() }

        if (shape.size == 1) {
            val dim = shape[0].toInt()
            val out = FloatArray(dim)
            fb.get(out)
            return arrayOf(out)
        }

        if (shape.size == 2) {
            val dim = shape[1].toInt()
            val out = Array(batchSize) { FloatArray(dim) }
            for (b in 0 until batchSize) {
                fb.get(out[b])
            }
            return out
        }

        if (shape.size == 3) {
            val seq = shape[1].toInt()
            val dim = shape[2].toInt()
            val out = Array(batchSize) { FloatArray(dim) }
            val stride = seq * dim
            for (b in 0 until batchSize) {
                fb.position(b * stride)
                fb.get(out[b], 0, dim) // token 0
            }
            fb.rewind()
            return out
        }

        throw IllegalStateException("Unexpected output tensor rank=${shape.size} shape=${shape.contentToString()}")
    }

    fun generateIndex() {
        indexingJob?.cancel()
        indexingRunId += 1
        val runId = indexingRunId

        indexingJob = viewModelScope.launch(Dispatchers.Default) {
            val models = runCatching { clipModels() }.getOrElse { t ->
                indexingError.postValue(t.message ?: "No models available")
                isIndexing.postValue(false)
                progress.postValue(0.0)
                indexedCount.postValue(0)
                return@launch
            }
            val prefs = SimilaritySettings.prefs(getApplication())
            val configured = prefs.getBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)
            if (!configured) {
                indexingError.postValue("Select a folder to index from the menu first.")
                isIndexing.postValue(false)
                progress.postValue(0.0)
                indexedCount.postValue(0)
                return@launch
            }

            val session =
                try {
                    selectSession()
                } catch (t: Throwable) {
                    val msg =
                        "Failed to start indexing (${models.engineId}): " +
                            (t.message ?: t.javaClass.simpleName)
                    indexingError.postValue(msg)
                    isIndexing.postValue(false)
                    return@launch
                }

            Log.i(LOG_TAG, "Indexing session selected: CPU model=${models.engineId}")

            fun isLatest(): Boolean = indexingRunId == runId
            fun postIsIndexing(value: Boolean) {
                if (isLatest()) isIndexing.postValue(value)
            }
            fun postProgress(value: Double) {
                if (isLatest()) progress.postValue(value)
            }
            fun postIndexedCount(value: Int) {
                if (isLatest()) indexedCount.postValue(value)
            }

            postIsIndexing(true)
            postProgress(0.0)
            postIndexedCount(0)

            var lastProgressUpdateMs = 0L
            var lastProgressPercentPosted = -1
            var lastIndexedCountUpdateMs = 0L
            var lastIndexedCountPosted = 0

            fun maybePostProgress(position: Int, totalImages: Int, force: Boolean = false) {
                if (totalImages <= 0) return
                if (position < 0) return
                val now = SystemClock.uptimeMillis()
                val progressValue =
                    minOf(((position + 1).toDouble() / totalImages.toDouble()), 0.99)
                val percent = (progressValue * 100.0).toInt().coerceIn(0, 99)
                val shouldPost =
                    force ||
                        percent != lastProgressPercentPosted ||
                        (now - lastProgressUpdateMs) >= PROGRESS_UPDATE_MIN_INTERVAL_MS
                if (!shouldPost) return
                lastProgressPercentPosted = percent
                lastProgressUpdateMs = now
                postProgress(progressValue)
            }

            fun maybePostIndexedCount(current: Int, force: Boolean = false) {
                val now = SystemClock.uptimeMillis()
                val shouldPost =
                    force ||
                        (current - lastIndexedCountPosted) >= INDEXED_COUNT_UPDATE_MIN_DELTA ||
                        (now - lastIndexedCountUpdateMs) >= INDEXED_COUNT_UPDATE_MIN_INTERVAL_MS
                if (!shouldPost) return
                lastIndexedCountPosted = current
                lastIndexedCountUpdateMs = now
                postIndexedCount(current)
            }

            val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
            val localWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Similarity:Indexing").apply {
                setReferenceCounted(false)
                // Keep CPU running if the screen turns off during indexing (safety timeout).
                acquire(2 * 60 * 60 * 1000L)
            }

            val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val contentResolver: ContentResolver = getApplication<Application>().contentResolver
            val inputName = "pixel_values"
            val inputMap = HashMap<String, OnnxTensor>(1)

            val useClipMeanStd = models.visionUsesClipMeanStd
            val (inputWidth, inputHeight) = getInputDims()
            val stride = inputWidth * inputHeight
            val elemsPerImage = DIM_PIXEL_SIZE * stride
            val cpuMicroBatchPref =
                prefs.getInt(
                    SimilaritySettings.KEY_CPU_MICRO_BATCH_MAX,
                    SimilaritySettings.DEFAULT_CPU_MICRO_BATCH_MAX
                ).coerceIn(1, MAX_MICRO_BATCH_HARD_CAP)
            val requestedMicroBatchMax =
                minOf(getBatchCap(), cpuMicroBatchPref, MAX_MICRO_BATCH_HARD_CAP)
                    .coerceAtLeast(1)
            val microBatchBytesPerImage =
                DIM_PIXEL_SIZE.toLong() * inputWidth.toLong() * inputHeight.toLong() * 4L
            val microBatchMax = clampCpuMicroBatchForMemory(requestedMicroBatchMax, microBatchBytesPerImage)

            val cpuResizeFilterEnabled =
                prefs.getBoolean(
                    SimilaritySettings.KEY_CPU_RESIZE_FILTER_ENABLED,
                    SimilaritySettings.DEFAULT_CPU_RESIZE_FILTER_ENABLED
                )
            val cpuPreprocessWorkersMultiplierPercent =
                prefs.getInt(
                    SimilaritySettings.KEY_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT,
                    SimilaritySettings.DEFAULT_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT
                ).coerceIn(50, 300)
            val preprocessWorkersMultiplier = cpuPreprocessWorkersMultiplierPercent / 100.0

            data class MediaRow(val id: Long, val date: Long, val width: Int, val height: Int)

            class PreprocessWorker(
                val workBitmap: Bitmap,
                val canvas: Canvas,
                val paint: Paint,
                val srcRect: Rect,
                val dstRect: Rect,
                val bmpData: IntArray,
                val floats: FloatArray,
                var decodeBitmap: Bitmap,
                val boundsOptions: BitmapFactory.Options,
                val thumbOptions: BitmapFactory.Options,
                val decodeOptions: BitmapFactory.Options,
            )

            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val targetWorkers =
                (cores * preprocessWorkersMultiplier).roundToInt().coerceAtLeast(1)
            val workerCount = minOf(microBatchMax, targetWorkers)
            // Only used on the non-thumbnail fallback path (e.g., some RAW formats).
            // 384px tends to be a good speed/quality tradeoff because we later center-crop+resize to model input.
            val decodeReqSizePx = 384

            val imgDataBatch = allocateModelInputBuffer(microBatchMax, inputWidth, inputHeight)
            val microBatchInputBuffer = imgDataBatch.duplicate()
            val microBatchInputShape = longArrayOf(0L, 3L, inputHeight.toLong(), inputWidth.toLong())
            val imgDataSlices =
                Array(microBatchMax) { i ->
                    imgDataBatch.duplicate().apply {
                        position(i * elemsPerImage)
                        limit((i + 1) * elemsPerImage)
                    }.slice()
                }

            data class EmbedTask(val row: MediaRow, val uri: Uri, val sliceIndex: Int)
            data class EmbedResult(val sliceIndex: Int, val ok: Boolean)

            val tasks = Channel<EmbedTask>(capacity = microBatchMax)
            val results = Channel<EmbedResult>(capacity = microBatchMax)
            val workerJobs = ArrayList<Job>(workerCount)
            repeat(workerCount) {
                val wb = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                val paint =
                    if (cpuResizeFilterEnabled) {
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    } else {
                        Paint().apply { isFilterBitmap = false }
                    }
                val worker =
                    PreprocessWorker(
                        workBitmap = wb,
                        canvas = Canvas(wb),
                        paint = paint,
                        srcRect = Rect(),
                        dstRect = Rect(0, 0, inputWidth, inputHeight),
                        bmpData = IntArray(stride),
                        floats = FloatArray(3 * stride),
                        decodeBitmap =
                            Bitmap.createBitmap(
                                decodeReqSizePx,
                                decodeReqSizePx,
                                Bitmap.Config.ARGB_8888
                            ),
                        boundsOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        },
                        thumbOptions = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                        },
                        decodeOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = false
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                            inMutable = true
                        },
                    )

                workerJobs.add(
                    launch {
                        for (task in tasks) {
                            if (!isActive || !isLatest()) break
                            val ok =
                                runCatching {
                                    val imageId = task.row.id
                                    val imageUri = task.uri
                                    val slice = imgDataSlices[task.sliceIndex]

                                    val decoded =
                                        withContext(Dispatchers.IO) io@{
                                            // Fast path: use system thumbnails when available.
                                            // - API 29+: loadThumbnail() (typically cached/decoded by the system)
                                            // - pre-29: MediaStore MINI_KIND thumbnails
                                            val thumb = runCatching {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                                    contentResolver.loadThumbnail(
                                                        imageUri,
                                                        Size(inputWidth, inputHeight),
                                                        null
                                                    )
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    MediaStore.Images.Thumbnails.getThumbnail(
                                                        contentResolver,
                                                        imageId,
                                                        MediaStore.Images.Thumbnails.MINI_KIND,
                                                        worker.thumbOptions
                                                    )
                                                }
                                            }.getOrNull()
                                            if (thumb != null) return@io thumb

                                            fun calcInSampleSize(srcW: Int, srcH: Int): Int {
                                                var inSampleSize = 1
                                                if (srcW > decodeReqSizePx || srcH > decodeReqSizePx) {
                                                    while ((srcW / inSampleSize) > decodeReqSizePx ||
                                                        (srcH / inSampleSize) > decodeReqSizePx) {
                                                        inSampleSize *= 2
                                                    }
                                                }
                                                return inSampleSize.coerceAtLeast(1)
                                            }

                                            var srcW = task.row.width
                                            var srcH = task.row.height
                                            if (srcW <= 0 || srcH <= 0) {
                                                val bounds = worker.boundsOptions.apply { inJustDecodeBounds = true }
                                                contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                                                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                                                } ?: return@io null
                                                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@io null
                                                srcW = bounds.outWidth
                                                srcH = bounds.outHeight
                                            }

                                            val inSampleSize = calcInSampleSize(srcW, srcH)
                                            val opts =
                                                worker.decodeOptions.apply {
                                                    this.inSampleSize = inSampleSize
                                                    inJustDecodeBounds = false
                                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                                    inScaled = false
                                                    inMutable = true
                                                    inBitmap = worker.decodeBitmap
                                                }

                                            fun decodeOnce(): Bitmap? {
                                                return contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                                                    BitmapFactory.decodeFileDescriptor(
                                                        pfd.fileDescriptor,
                                                        null,
                                                        opts
                                                    )
                                                }
                                            }

                                            val bmp =
                                                try {
                                                    decodeOnce()
                                                } catch (_: IllegalArgumentException) {
                                                    opts.inBitmap = null
                                                    decodeOnce()
                                                } ?: return@io null

                                            if (bmp !== worker.decodeBitmap) {
                                                runCatching { worker.decodeBitmap.recycle() }
                                                worker.decodeBitmap = bmp
                                            }
                                            bmp
                                        } ?: return@runCatching false

                                    val cropSize = minOf(decoded.width, decoded.height)
                                    val cropX = (decoded.width - cropSize) / 2
                                    val cropY = (decoded.height - cropSize) / 2
                                    worker.srcRect.set(cropX, cropY, cropX + cropSize, cropY + cropSize)
                                    worker.canvas.drawBitmap(decoded, worker.srcRect, worker.dstRect, worker.paint)
                                    if (useClipMeanStd) {
                                        preProcessIntoClipMeanStd(
                                            worker.workBitmap,
                                            worker.bmpData,
                                            worker.floats,
                                            slice
                                        )
                                    } else {
                                        preProcessIntoUnitRange(
                                            worker.workBitmap,
                                            worker.bmpData,
                                            worker.floats,
                                            slice
                                        )
                                    }
                                    if (decoded !== worker.decodeBitmap) {
                                        runCatching { decoded.recycle() }
                                    }
                                    true
                                }.getOrElse { false }
                            if (!isActive || !isLatest()) break
                            runCatching { results.send(EmbedResult(task.sliceIndex, ok)) }
                        }
                    }
                )
            }

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT
            )
            val sortOrder = "${MediaStore.Images.Media._ID} ASC"

            val bucketIds: Set<String> =
                prefs.getStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet()
                    ?: emptySet()
            val selectionParts = ArrayList<String>(2)
            val selectionArgsList = ArrayList<String>((bucketIds.size + 1).coerceAtLeast(1))
            if (bucketIds.isNotEmpty()) {
                val placeholders = bucketIds.joinToString(",") { "?" }
                selectionParts.add("${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)")
                selectionArgsList.addAll(bucketIds)
            }
            // Exclude screenshots, matching the previous on-device filter semantics (null buckets are kept).
            selectionParts.add(
                "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NULL OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} != ?)"
            )
            selectionArgsList.add("Screenshots")
            val selection = selectionParts.joinToString(" AND ")
            val selectionArgs = selectionArgsList.toTypedArray()

            val cursor: Cursor? =
                contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            val totalImages = cursor?.count ?: 0
            val desiredIds: HashSet<Long> = HashSet(((totalImages * 4) / 3).coerceAtLeast(16))
            val newIdxList = ArrayList<Long>(totalImages.coerceAtLeast(16))
            val newEmbeddingsList = ArrayList<FloatArray>(totalImages.coerceAtLeast(16))
            var completedNormally = false
            try {
                cursor?.use {
                    val idColumn: Int = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateColumn: Int =
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val widthColumn: Int = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val heightColumn: Int = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

                    suspend fun processBatch(batch: List<MediaRow>) {
                        if (batch.isEmpty()) return
                        if (!isActive || !isLatest()) return

                        val ids = ArrayList<Long>(batch.size)
                        for (row in batch) ids.add(row.id)
                        val records = withContext(Dispatchers.IO) { repository.getRecordsByIds(ids) }
                        val recordById =
                            HashMap<Long, ImageEmbedding>(((records.size * 4) / 3).coerceAtLeast(16))
                        for (r in records) recordById[r.id] = r

                        val toInsert = ArrayList<ImageEmbedding>(INSERT_BATCH_SIZE)
                        suspend fun flushInserts() {
                            if (toInsert.isEmpty()) return
                            withContext(Dispatchers.IO) { repository.addImageEmbeddings(toInsert) }
                            toInsert.clear()
                        }

                        val embedRows = ArrayList<MediaRow>(microBatchMax)
                        val okFlags = BooleanArray(microBatchMax)
                        var pendingResults = 0

                        suspend fun runMicroBatch() {
                            if (embedRows.isEmpty()) return
                            if (!isActive || !isLatest()) return

                            val batchSize = embedRows.size
                            microBatchInputShape[0] = batchSize.toLong()
                            microBatchInputBuffer.rewind()
                            microBatchInputBuffer.limit(batchSize * elemsPerImage)

                            val inputTensor =
                                OnnxTensor.createTensor(ortEnv, microBatchInputBuffer, microBatchInputShape)
                            inputTensor.use { t ->
                                inputMap[inputName] = t
                                val output = session.run(inputMap)
                                output.use { out ->
                                    val outName = getOutputName(session)
                                    val outTensor = getOutputTensor(out, outName)
                                    val embeddings = extractEmbeddingsFromTensor(outTensor, batchSize)

                                    for (i in 0 until batchSize) {
                                        val row = embedRows[i]
                                        val rawOutput = embeddings[i]
                                        normalizeL2(rawOutput)

                                        toInsert.add(ImageEmbedding(row.id, row.date, rawOutput))
                                        if (toInsert.size >= INSERT_BATCH_SIZE) flushInserts()

                                        newIdxList.add(row.id)
                                        newEmbeddingsList.add(rawOutput)
                                        maybePostIndexedCount(newIdxList.size)
                                    }
                                }
                                inputMap.clear()
                            }

                            embedRows.clear()
                        }

                        suspend fun flushEmbeddedRows() {
                            if (embedRows.isEmpty()) return
                            if (!isActive || !isLatest()) return

                            repeat(pendingResults) {
                                val r = results.receive()
                                if (r.sliceIndex in 0 until microBatchMax) okFlags[r.sliceIndex] = r.ok
                            }
                            pendingResults = 0

                            var write = 0
                            for (read in 0 until embedRows.size) {
                                if (!okFlags[read]) continue
                                if (write != read) {
                                    val dst = imgDataSlices[write].duplicate().apply { rewind() }
                                    val src = imgDataSlices[read].duplicate().apply { rewind() }
                                    dst.put(src)
                                    embedRows[write] = embedRows[read]
                                }
                                write += 1
                            }
                            while (embedRows.size > write) embedRows.removeAt(embedRows.lastIndex)
                            runMicroBatch()
                        }

                        for (row in batch) {
                            if (!isActive || !isLatest()) return

                            val record = recordById[row.id]
                            if (record != null) {
                                newIdxList.add(record.id)
                                newEmbeddingsList.add(record.embedding)
                                maybePostIndexedCount(newIdxList.size)
                                continue
                            }

                            val imageUri: Uri = ContentUris.withAppendedId(uri, row.id)
                            val batchIndex = embedRows.size
                            embedRows.add(row)
                            okFlags[batchIndex] = false
                            tasks.send(EmbedTask(row = row, uri = imageUri, sliceIndex = batchIndex))
                            pendingResults += 1

                            if (embedRows.size >= microBatchMax) {
                                flushEmbeddedRows()
                            }
                        }

                        flushEmbeddedRows()
                        flushInserts()
                    }

                        val pending = ArrayList<MediaRow>(DB_ID_BATCH_SIZE)
                        var lastCursorPos = -1
                        while (it.moveToNext()) {
                            if (!isActive || !isLatest()) return@use

                            val id: Long = it.getLong(idColumn)
                            val date: Long = it.getLong(dateColumn)
                            lastCursorPos = it.position
                            val w: Int = if (widthColumn >= 0) it.getInt(widthColumn) else 0
                            val h: Int = if (heightColumn >= 0) it.getInt(heightColumn) else 0

                            desiredIds.add(id)
                            pending.add(MediaRow(id = id, date = date, width = w, height = h))
                            if (pending.size >= DB_ID_BATCH_SIZE) {
                                processBatch(pending)
                                pending.clear()
                            }

                            maybePostProgress(lastCursorPos, totalImages)
                        }

                        // Cursor iteration may complete very quickly (small folders / mostly DB hits),
                        // so force at least one final progress update before heavy embedding work.
                        maybePostProgress(lastCursorPos, totalImages, force = true)

                        processBatch(pending)
                    }

                if (isActive && isLatest()) {
                    completedNormally = true

                    viewModelScope.launch(Dispatchers.IO) purge@{
                        if (!isLatest()) return@purge
                        val existingIds = repository.getAllIds().toHashSet()
                        existingIds.removeAll(desiredIds)
                        if (existingIds.isNotEmpty()) repository.deleteByIds(existingIds.toList())
                    }
                }
            } finally {
                runCatching { tasks.close() }
                workerJobs.forEach { runCatching { it.cancel() } }
                cursor?.close()
                try {
                    if (localWakeLock.isHeld) localWakeLock.release()
                } catch (_: Exception) {
                }

                if (isLatest()) {
                    // Commit partial results even if the job was cancelled (e.g. user tapped X).
                        withContext(NonCancellable + Dispatchers.Main) {
                            idxList = newIdxList
                            embeddingsList = newEmbeddingsList
                        }
                        withContext(NonCancellable) {
                            maybePostIndexedCount(newIdxList.size, force = true)
                        }
                        if (completedNormally) {
                            withContext(NonCancellable) {
                                postProgress(1.0)
                            }
                        }
                        withContext(NonCancellable) {
                            postIsIndexing(false)
                        }
                    }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { cpuSession?.close() }
        cpuSession = null
    }

    fun clearIndexingError() {
        indexingError.value = null
    }

    fun resetForFullReset() {
        // Stop in-progress indexing and drop all model/session caches so the next indexing run
        // reselects models and reinitializes ORT sessions.
        runCatching { indexingJob?.cancel() }

        synchronized(clipModelsLock) { clipModelsCached = null }

        synchronized(cpuSessionLock) {
            runCatching { cpuSession?.close() }
            cpuSession = null
        }
        cpuOutputName = null
        cpuBatchCap = null
        cpuInputWidth = null
        cpuInputHeight = null

        idxList = arrayListOf()
        embeddingsList = arrayListOf()
    }

    fun resetVisualSessionsForSettingsChange() {
        // Close sessions so new SessionOptions (e.g., CPU threading) take effect.
        runCatching { indexingJob?.cancel() }

        synchronized(cpuSessionLock) {
            runCatching { cpuSession?.close() }
            cpuSession = null
        }
        cpuOutputName = null
        cpuBatchCap = null
        cpuInputWidth = null
        cpuInputHeight = null
    }

    fun cancelIndexing(): Job? {
        val job = indexingJob
        job?.cancel()
        return job
    }

    suspend fun getEmbeddingForImageId(id: Long): FloatArray? {
        val idx = idxList.indexOf(id)
        if (idx >= 0 && idx < embeddingsList.size) return embeddingsList[idx]
        val record = withContext(Dispatchers.IO) { repository.getRecord(id) }
        return record?.embedding
    }

    fun removeFromIndex(ids: List<Long>) {
        if (ids.isEmpty()) return

        val idsSet = ids.toHashSet()
        val newIdxList = ArrayList<Long>(idxList.size)
        val newEmbeddingsList = ArrayList<FloatArray>(embeddingsList.size)
        for (i in idxList.indices) {
            val id = idxList[i]
            if (!idsSet.contains(id)) {
                newIdxList.add(id)
                newEmbeddingsList.add(embeddingsList[i])
            }
        }
        idxList = newIdxList
        embeddingsList = newEmbeddingsList

        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteByIds(ids)
        }
    }
}
