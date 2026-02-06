package com.yayahiwo.similarity.editor

import android.graphics.Bitmap
import android.graphics.Matrix

class Rotate90ClockwiseOperation : ImageEditOperation {
    override val name: String = "Rotate"
    override val confirmationMessage: String =
        "Rotate this photo 90° clockwise?\n\nThis will overwrite the original file."

    override fun applyTo(bitmap: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}

