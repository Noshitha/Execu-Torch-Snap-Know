package com.snapknow.app.database.dao

import androidx.room.*
import com.snapknow.app.database.entity.FaceMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: FaceMemory): Long

    /** Load ALL faces so the caller can do embedding comparison in Kotlin */
    @Query("SELECT * FROM face_memory ORDER BY timestamp DESC")
    suspend fun getAll(): List<FaceMemory>

    @Query("SELECT * FROM face_memory ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<FaceMemory>>

    @Query("SELECT * FROM face_memory WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): FaceMemory?

    @Query("SELECT * FROM face_memory WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): FaceMemory?

    @Delete
    suspend fun delete(memory: FaceMemory)

    @Query("DELETE FROM face_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM face_memory WHERE LOWER(name) = LOWER(:name)")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM face_memory")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM face_memory")
    suspend fun count(): Int
}
