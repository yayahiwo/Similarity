package com.yayahiwo.similarity.data

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray?): FloatArray {
        if (value == null || value.isEmpty()) return floatArrayOf()
        if (value.size % 4 != 0) return floatArrayOf()

        val floats = FloatArray(value.size / 4)
        ByteBuffer.wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats)
        return floats
    }

    @TypeConverter
    fun fromFloatArray(array: FloatArray): ByteArray {
        if (array.isEmpty()) return ByteArray(0)
        val buffer = ByteBuffer
            .allocate(array.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(array)
        return buffer.array()
    }
}
