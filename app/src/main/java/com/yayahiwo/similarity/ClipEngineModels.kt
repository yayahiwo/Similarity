package com.yayahiwo.similarity

import android.app.Application
import android.util.Log
import java.io.File

data class ClipEngineModels(
    val engineId: String,
    val visionModelFile: File,
    val textModelFile: File,
    val visionUsesClipMeanStd: Boolean,
)

object ClipEngineModelsSelector {
    private const val LOG_TAG = "Similarity"
    private const val MIN_MODEL_BYTES = 1024L

    private val lock = Any()
    @Volatile private var cached: ClipEngineModels? = null

    fun get(application: Application): ClipEngineModels {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val out = select(application)
            Log.i(
                LOG_TAG,
                "CLIP engine selected: ${out.engineId} " +
                    "vision=${out.visionModelFile.absolutePath} (${out.visionModelFile.length() / (1024 * 1024)}MB) " +
                    "text=${out.textModelFile.absolutePath} (${out.textModelFile.length() / (1024 * 1024)}MB)"
            )
            cached = out
            return out
        }
    }

    fun clearCache() {
        synchronized(lock) { cached = null }
    }

    private fun select(application: Application): ClipEngineModels {
        val appCtx = application.applicationContext
        val res = appCtx.resources

        fun rawResId(rawName: String): Int {
            // Avoid compile-time dependency on optional local `res/raw/*.onnx` files.
            // This keeps the project buildable from a fresh public clone.
            return res.getIdentifier(rawName, "raw", appCtx.packageName)
        }

        fun isUsableModelFile(file: File): Boolean {
            if (!file.exists() || !file.isFile) return false
            val len = file.length()
            if (len >= MIN_MODEL_BYTES) return true
            // Some OEM/FUSE stacks can report an incorrect length for files created via adb shell.
            // Try a tiny read to confirm it's not an empty placeholder.
            return try {
                file.inputStream().use { input ->
                    val buf = ByteArray(16)
                    input.read(buf) > 0
                }
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Model file not readable: ${file.absolutePath}", t)
                false
            }
        }

        fun bundledModel(rawName: String): File? {
            val resId = rawResId(rawName)
            if (resId == 0) return null

            val outDir = File(appCtx.filesDir, "models").apply { mkdirs() }
            val outFile = File(outDir, "$rawName.onnx")
            if (!outFile.exists() || outFile.length() < MIN_MODEL_BYTES) {
                res.openRawResource(resId).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            }
            return if (isUsableModelFile(outFile)) outFile else null
        }

        val vision =
            bundledModel("visual_quant")
                ?: throw IllegalStateException(
                    "Missing vision model: expected `app/src/main/res/raw/visual_quant.onnx`."
                )
        val text =
            bundledModel("textual_quant")
                ?: throw IllegalStateException(
                    "Missing text model: expected `app/src/main/res/raw/textual_quant.onnx`."
                )

        return ClipEngineModels(
            engineId = "clip_quant",
            visionModelFile = vision,
            textModelFile = text,
            visionUsesClipMeanStd = true,
        )
    }
}
