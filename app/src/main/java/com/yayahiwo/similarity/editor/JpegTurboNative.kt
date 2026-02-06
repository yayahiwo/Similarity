package com.yayahiwo.similarity.editor

internal object JpegTurboNative {
    init {
        System.loadLibrary("imageedit_jni")
    }

    external fun isAvailable(): Boolean
    external fun rotate90ClockwiseLosslessJpeg(jpegBytes: ByteArray, exifOrientation: Int): ByteArray?
    external fun rotate90CounterClockwiseLosslessJpeg(jpegBytes: ByteArray, exifOrientation: Int): ByteArray?
}
