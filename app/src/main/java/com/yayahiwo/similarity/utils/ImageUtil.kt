package com.yayahiwo.similarity

import android.graphics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

const val DIM_BATCH_SIZE = 1
const val DIM_PIXEL_SIZE = 3
const val IMAGE_SIZE_X = 224
const val IMAGE_SIZE_Y = 224

fun allocateModelInputBuffer(batchSize: Int = DIM_BATCH_SIZE): FloatBuffer {
    return allocateModelInputBuffer(batchSize, IMAGE_SIZE_X, IMAGE_SIZE_Y)
}

fun allocateModelInputBuffer(
    batchSize: Int,
    imageSizeX: Int,
    imageSizeY: Int,
): FloatBuffer {
    val bytes = batchSize * DIM_PIXEL_SIZE * imageSizeX * imageSizeY * 4
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
}

internal fun preProcessPixelsIntoClipMeanStd(
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    preProcessPixelsIntoClipMeanStd(bmpData, floats, out, IMAGE_SIZE_X, IMAGE_SIZE_Y)
}

internal fun preProcessPixelsIntoClipMeanStd(
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
    imageSizeX: Int,
    imageSizeY: Int,
) {
    val stride = imageSizeX * imageSizeY
    require(bmpData.size >= stride) { "bmpData must be at least $stride" }
    require(floats.size >= DIM_PIXEL_SIZE * stride) { "floats must be at least ${DIM_PIXEL_SIZE * stride}" }

    val inv255 = 1.0f / 255.0f
    // OpenAI CLIP normalization (after scaling to [0,1]).
    val meanR = 0.48145466f
    val meanG = 0.4578275f
    val meanB = 0.40821073f
    val invStdR = 1.0f / 0.26862954f
    val invStdG = 1.0f / 0.26130258f
    val invStdB = 1.0f / 0.27577711f

    val stride2 = stride * 2
    for (idx in 0 until stride) {
        val pixelValue = bmpData[idx]
        val r = ((pixelValue shr 16) and 0xFF) * inv255
        val g = ((pixelValue shr 8) and 0xFF) * inv255
        val b = (pixelValue and 0xFF) * inv255
        floats[idx] = (r - meanR) * invStdR
        floats[idx + stride] = (g - meanG) * invStdG
        floats[idx + stride2] = (b - meanB) * invStdB
    }

    out.rewind()
    out.put(floats, 0, DIM_PIXEL_SIZE * stride)
    out.rewind()
}

internal fun preProcessPixelsIntoUnitRange(
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    preProcessPixelsIntoUnitRange(bmpData, floats, out, IMAGE_SIZE_X, IMAGE_SIZE_Y)
}

internal fun preProcessPixelsIntoUnitRange(
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
    imageSizeX: Int,
    imageSizeY: Int,
) {
    val stride = imageSizeX * imageSizeY
    require(bmpData.size >= stride) { "bmpData must be at least $stride" }
    require(floats.size >= DIM_PIXEL_SIZE * stride) { "floats must be at least ${DIM_PIXEL_SIZE * stride}" }

    val inv255 = 1.0f / 255.0f
    val stride2 = stride * 2
    for (idx in 0 until stride) {
        val pixelValue = bmpData[idx]
        floats[idx] = ((pixelValue shr 16) and 0xFF) * inv255
        floats[idx + stride] = ((pixelValue shr 8) and 0xFF) * inv255
        floats[idx + stride2] = (pixelValue and 0xFF) * inv255
    }

    out.rewind()
    out.put(floats, 0, DIM_PIXEL_SIZE * stride)
    out.rewind()
}

fun preProcessIntoClipMeanStd(
    bitmap: Bitmap,
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    bitmap.getPixels(bmpData, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    preProcessPixelsIntoClipMeanStd(bmpData, floats, out, bitmap.width, bitmap.height)
}

fun preProcessIntoUnitRange(
    bitmap: Bitmap,
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    bitmap.getPixels(bmpData, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    preProcessPixelsIntoUnitRange(bmpData, floats, out, bitmap.width, bitmap.height)
}

fun centerCrop(bitmap: Bitmap, imageSize: Int): Bitmap {
    val cropX: Int
    val cropY: Int
    val cropSize: Int
    if (bitmap.width >= bitmap.height) {
        cropX = bitmap.width / 2 - bitmap.height / 2
        cropY = 0
        cropSize = bitmap.height
    } else {
        cropX = 0
        cropY = bitmap.height / 2 - bitmap.width / 2
        cropSize = bitmap.width
    }
    var bitmapCropped = Bitmap.createBitmap(
        bitmap, cropX, cropY, cropSize, cropSize
    )
    bitmapCropped = Bitmap.createScaledBitmap(
        bitmapCropped, imageSize, imageSize, false
    )
    return bitmapCropped
}
