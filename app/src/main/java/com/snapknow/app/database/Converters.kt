package com.snapknow.app.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts FloatArray ↔ ByteArray for Room storage.
 * Little-endian IEEE 754 float encoding (4 bytes per float).
 */
class Converters {

    @TypeConverter
    fun floatArrayToByteArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun byteArrayToFloatArray(value: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(value.size / 4)
        buffer.asFloatBuffer().get(result)
        return result
    }
}
