package com.yayahiwo.similarity.editor

import android.graphics.Bitmap

interface ImageEditOperation {
    val name: String
    val confirmationMessage: String

    fun applyTo(bitmap: Bitmap): Bitmap
}

