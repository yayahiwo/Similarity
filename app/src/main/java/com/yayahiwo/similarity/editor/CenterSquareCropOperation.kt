package com.yayahiwo.similarity.editor

import android.graphics.Bitmap

class CenterSquareCropOperation : ImageEditOperation {
    override val name: String = "Crop"
    override val confirmationMessage: String =
        "Crop this photo to a centered square?\n\nThis will overwrite the original file."

    override fun applyTo(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }
}

