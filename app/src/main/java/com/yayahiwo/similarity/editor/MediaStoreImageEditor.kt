package com.yayahiwo.similarity.editor

import android.content.ContentResolver
import android.content.Context
import android.app.RecoverableSecurityException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

class MediaStoreImageEditor(
    private val context: Context,
) {
    sealed class Result {
        data object Success : Result()
        data class NeedsWritePermission(val pendingIntent: android.app.PendingIntent) : Result()
        data class Error(val message: String) : Result()
    }

    fun applyOperation(
        imageUri: Uri,
        operation: ImageEditOperation,
    ): Result {
        return applyBitmapOperation(imageUri, operation)
    }

    fun applyOperation(
        imageUri: Uri,
        operation: MediaStoreEditOperation,
    ): Result {
        val resolver = context.contentResolver

        try {
            operation.apply(resolver, imageUri)

            return Result.Success
        } catch (se: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                se is RecoverableSecurityException
            ) {
                return Result.NeedsWritePermission(se.userAction.actionIntent)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val pi = MediaStore.createWriteRequest(resolver, listOf(imageUri))
                return Result.NeedsWritePermission(pi)
            }
            return Result.Error("No permission to modify this image")
        } catch (e: Exception) {
            return Result.Error(e.message ?: "Edit failed")
        }
    }

    private fun applyBitmapOperation(
        imageUri: Uri,
        operation: ImageEditOperation,
    ): Result {
        val resolver = context.contentResolver

        val mimeType = runCatching { resolver.getType(imageUri) }.getOrNull()
        val compress = when (mimeType?.lowercase()) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (compress == Bitmap.CompressFormat.JPEG) 95 else 100

        try {
            val src = decodeBitmap(resolver, imageUri)
                ?: return Result.Error("Failed to load image")
            val edited = operation.applyTo(src)

            resolver.openOutputStream(imageUri, "w")?.use { out ->
                if (!edited.compress(compress, quality, out)) {
                    throw IOException("Failed to encode image")
                }
            } ?: throw IOException("Failed to open image for writing")

            return Result.Success
        } catch (se: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                se is RecoverableSecurityException
            ) {
                return Result.NeedsWritePermission(se.userAction.actionIntent)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val pi = MediaStore.createWriteRequest(resolver, listOf(imageUri))
                return Result.NeedsWritePermission(pi)
            }
            return Result.Error("No permission to modify this image")
        } catch (e: Exception) {
            return Result.Error(e.message ?: "Edit failed")
        }
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        return resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    // Note: Do not set MediaStore.MediaColumns.IS_PENDING for existing photos.
    // IS_PENDING is intended for items created by the app and can trigger
    // "only owner can interact with pending/trashed item" for user-owned media.
}
