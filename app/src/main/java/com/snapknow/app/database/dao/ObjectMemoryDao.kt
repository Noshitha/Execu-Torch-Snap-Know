package com.snapknow.app.database.dao

import androidx.room.*
import com.snapknow.app.database.entity.ObjectMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: ObjectMemory): Long

    /** Full-text-like search: matches object name containing the query term */
    @Query("""
        SELECT * FROM object_memory
        WHERE LOWER(object_name) LIKE '%' || LOWER(:query) || '%'
        ORDER BY timestamp DESC
        LIMIT 5
    """)
    suspend fun search(query: String): List<ObjectMemory>

    /** Exact match first, then fuzzy — used when we get a very specific query */
    @Query("""
        SELECT * FROM object_memory
        WHERE LOWER(object_name) = LOWER(:name)
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun findExact(name: String): ObjectMemory?

    @Query("SELECT * FROM object_memory ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ObjectMemory>>

    @Query("SELECT * FROM object_memory ORDER BY timestamp DESC")
    suspend fun getAll(): List<ObjectMemory>

    @Delete
    suspend fun delete(memory: ObjectMemory)

    @Query("DELETE FROM object_memory WHERE LOWER(object_name) LIKE '%' || LOWER(:name) || '%'")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM object_memory")
    suspend fun deleteAll()
}
