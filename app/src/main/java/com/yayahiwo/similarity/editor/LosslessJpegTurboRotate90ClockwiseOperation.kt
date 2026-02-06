package com.yayahiwo.similarity.editor

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

class LosslessJpegTurboRotate90ClockwiseOperation : MediaStoreEditOperation {
    override val name: String = "Rotate"
    override val confirmationMessage: String =
        "Rotate this JPEG 90° clockwise (true lossless DCT transform)?\n\n" +
            "This will overwrite the original file.\n" +
            "If the image size is not aligned to JPEG block boundaries, a few edge pixels may be trimmed."

    override fun apply(resolver: ContentResolver, imageUri: Uri) {
        val exifOrientation = resolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val inBytes = resolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: error("Failed to read image")

        val outBytes = JpegTurboNative.rotate90ClockwiseLosslessJpeg(inBytes, exifOrientation)
            ?: error("Lossless JPEG rotate is not available (missing libjpeg-turbo JNI)")

        resolver.openOutputStream(imageUri, "w")?.use { out ->
            out.write(outBytes)
            out.flush()
        } ?: error("Failed to open image for writing")

        // The JPEG pixels are now physically rotated; normalize EXIF orientation.
        resolver.openFileDescriptor(imageUri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            exif.saveAttributes()
        }
    }
}
