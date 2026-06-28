package com.snapknow.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores "I left my keys on the right side of the table" type memories.
 */
@Entity(tableName = "object_memory")
data class ObjectMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised object name, e.g. "keys", "glasses", "phone" */
    @ColumnInfo(name = "object_name") val objectName: String,
    /** Where the object is, e.g. "right side of the table" */
    val location: String,
    /** Optional extra context the user spoke */
    val context: String = "",
    /** Unix timestamp in milliseconds */
    val timestamp: Long = System.currentTimeMillis()
)
