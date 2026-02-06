package com.yayahiwo.similarity.editor

import android.graphics.Bitmap

class ResizeLongEdgeOperation(
    private val targetLongEdgePx: Int,
) : ImageEditOperation {
    override val name: String = "Resize"
    override val confirmationMessage: String =
        "Resize this photo so its long edge is ${targetLongEdgePx}px?\n\nThis will overwrite the original file."

    override fun applyTo(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return bitmap

        val longEdge = maxOf(w, h)
        if (targetLongEdgePx <= 0 || longEdge == targetLongEdgePx) return bitmap

        val scale = targetLongEdgePx.toFloat() / longEdge.toFloat()
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
    }
}

