package com.snapknow.app.database

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.snapknow.app.database.entity.FaceMemory
import com.snapknow.app.database.entity.ObjectMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

private const val TAG = "MemoryRepository"
private const val FACE_MATCH_THRESHOLD = 0.65f  // cosine similarity threshold

class MemoryRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val objDao = db.objectMemoryDao()
    private val faceDao = db.faceMemoryDao()
    private val filesDir = context.filesDir

    // ─── Object memories ─────────────────────────────────────────────────────

    suspend fun storeObject(objectName: String, location: String, context: String = "") {
        val normalised = objectName.trim().lowercase()
        objDao.insert(ObjectMemory(objectName = normalised, location = location, context = context))
        Log.d(TAG, "Stored: '$normalised' → '$location'")
    }

    /**
     * Returns the most recent location for [query], or null if not found.
     * Tries exact match first, then fuzzy.
     */
    suspend fun findObject(query: String): ObjectMemory? {
        val clean = query.trim().lowercase()
        return objDao.findExact(clean) ?: objDao.search(clean).firstOrNull()
    }

    suspend fun getAllObjects(): List<ObjectMemory> = objDao.getAll()

    fun observeObjects(): Flow<List<ObjectMemory>> = objDao.observeAll()

    suspend fun forgetObject(name: String) = objDao.deleteByName(name)

    // ─── Face memories ────────────────────────────────────────────────────────

    /**
     * Saves a new face with [name], [relationship], and its 128-dim [embedding].
     * Optionally persists [faceBitmap] as JPEG in internal storage.
     */
    suspend fun storeFace(
        name: String,
        relationship: String,
        embedding: FloatArray,
        faceBitmap: Bitmap? = null,
        notes: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val existing = faceDao.findByName(cleanName)
        val photoPath = faceBitmap?.let { saveFacePhoto(cleanName, it) } ?: existing?.photoPath.orEmpty()
        val entity = FaceMemory(
            id = existing?.id ?: 0,
            name = cleanName,
            relationship = relationship.trim(),
            embedding = embedding,
            photoPath = photoPath,
            notes = notes.ifBlank { existing?.notes.orEmpty() }
        )
        faceDao.insert(entity).also { Log.d(TAG, "Stored face: '$cleanName' id=$it") }
    }

    /**
     * Finds the best matching person for [queryEmbedding].
     * Returns null if no stored face has similarity ≥ [FACE_MATCH_THRESHOLD].
     */
    suspend fun matchFace(queryEmbedding: FloatArray): Pair<FaceMemory, Float>? =
        withContext(Dispatchers.Default) {
            val allFaces = faceDao.getAll()
            if (allFaces.isEmpty()) return@withContext null

            var bestMatch: FaceMemory? = null
            var bestScore = -1f

            allFaces.forEach { face ->
                val score = cosineSimilarity(queryEmbedding, face.embedding)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = face
                }
            }

            Log.d(TAG, "Best face match: ${bestMatch?.name} score=$bestScore threshold=$FACE_MATCH_THRESHOLD")
            if (bestScore >= FACE_MATCH_THRESHOLD) Pair(bestMatch!!, bestScore) else null
        }

    suspend fun getAllFaces(): List<FaceMemory> = faceDao.getAll()

    fun observeFaces(): Flow<List<FaceMemory>> = faceDao.observeAll()

    suspend fun forgetFace(name: String) = faceDao.deleteByName(name)

    suspend fun updateFaceDetails(id: Long, relationship: String, notes: String) =
        withContext(Dispatchers.IO) {
            val existing = faceDao.findById(id) ?: return@withContext
            faceDao.insert(
                existing.copy(
                    relationship = relationship.trim(),
                    notes = notes.trim(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }

    suspend fun deleteFaceById(id: Long) = withContext(Dispatchers.IO) {
        val existing = faceDao.findById(id) ?: return@withContext
        if (existing.photoPath.isNotBlank()) {
            val photoFile = File(existing.photoPath)
            if (photoFile.exists() && !photoFile.delete()) {
                Log.w(TAG, "Failed to delete face photo at ${existing.photoPath}")
            }
        }
        faceDao.deleteById(id)
    }

    suspend fun faceCount(): Int = faceDao.count()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun saveFacePhoto(name: String, bitmap: Bitmap): String {
        val dir = File(filesDir, "faces").apply { mkdirs() }
        val file = File(dir, "${name.replace(" ", "_")}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    /**
     * Cosine similarity between two L2-normalised vectors.
     * Value in [-1, 1]; higher = more similar.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-8f) -1f else dot / denom
    }
}
