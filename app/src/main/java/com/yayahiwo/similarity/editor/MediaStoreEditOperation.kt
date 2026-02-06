package com.yayahiwo.similarity.editor

import android.content.ContentResolver
import android.net.Uri

interface MediaStoreEditOperation {
    val name: String
    val confirmationMessage: String

    fun apply(resolver: ContentResolver, imageUri: Uri)
}

