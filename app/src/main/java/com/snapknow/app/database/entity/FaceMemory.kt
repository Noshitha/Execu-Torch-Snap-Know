package com.snapknow.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores a known person: name, relationship, and a 128-dim face embedding
 * produced by the ExecuTorch MobileFaceNet model.
 */
@Entity(tableName = "face_memory")
data class FaceMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Person's name, e.g. "John" */
    val name: String,
    /** Optional relationship, e.g. "son", "doctor", "neighbour" */
    val relationship: String = "",
    /**
     * 128-dim L2-normalised embedding from MobileFaceNet.
     * Stored as ByteArray via [com.snapknow.app.database.Converters].
     */
    val embedding: FloatArray,
    /** Path to the saved face-crop JPEG inside app's internal filesDir */
    val photoPath: String = "",
    /** Any notes the user spoke, e.g. "she brings me medicine on Mondays" */
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    // FloatArray doesn't have structural equality — override for Room/data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceMemory) return false
        return id == other.id && name == other.name && embedding.contentEquals(other.embedding)
    }
    override fun hashCode(): Int = 31 * id.hashCode() + name.hashCode()
}
