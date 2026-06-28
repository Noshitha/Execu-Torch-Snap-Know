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
     * Saves a new face sample for [name]. Repeated saves for the same person
     * accumulate multiple samples, which are later averaged as a centroid.
     */
    suspend fun storeFace(
        name: String,
        relationship: String,
        embedding: FloatArray,
        faceBitmap: Bitmap? = null,
        notes: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val normalizedEmbedding = normalizeEmbedding(embedding) ?: return@withContext -1L
        val existing = faceDao.findByName(cleanName)
        val photoPath = faceBitmap?.let { saveFacePhoto(cleanName, it) } ?: existing?.photoPath.orEmpty()
        val entity = FaceMemory(
            id = 0,
            name = cleanName,
            relationship = relationship.trim().ifBlank { existing?.relationship.orEmpty() },
            embedding = normalizedEmbedding,
            photoPath = photoPath,
            notes = notes.ifBlank { existing?.notes.orEmpty() }
        )
        val sampleId = faceDao.insert(entity)
        val sampleCount = faceDao.findAllByName(cleanName).size
        Log.d(TAG, "Stored face sample: '$cleanName' id=$sampleId total_samples=$sampleCount")
        sampleId
    }

    /**
     * Finds the best matching person for [queryEmbedding].
     * Returns null if no stored face has similarity ≥ [FACE_MATCH_THRESHOLD].
     */
    suspend fun matchFace(queryEmbedding: FloatArray): Pair<FaceMemory, Float>? =
        withContext(Dispatchers.Default) {
            val normalizedQuery = normalizeEmbedding(queryEmbedding) ?: return@withContext null
            val aggregatedFaces = aggregateFaceSamples(faceDao.getAll())
            if (aggregatedFaces.isEmpty()) return@withContext null

            var bestMatch: FaceMemory? = null
            var bestScore = -1f

            aggregatedFaces.forEach { face ->
                val score = cosineSimilarity(normalizedQuery, face.embedding)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = face
                }
            }

            Log.d(TAG, "Best face match: ${bestMatch?.name} score=$bestScore threshold=$FACE_MATCH_THRESHOLD")
            if (bestScore >= FACE_MATCH_THRESHOLD) Pair(bestMatch!!, bestScore) else null
        }

    suspend fun getAllFaces(): List<FaceMemory> = withContext(Dispatchers.Default) {
        aggregateFaceSamples(faceDao.getAll())
    }

    fun observeFaces(): Flow<List<FaceMemory>> = faceDao.observeAll()

    suspend fun forgetFace(name: String) = withContext(Dispatchers.IO) {
        val samples = faceDao.findAllByName(name)
        samples.forEach { sample ->
            if (sample.photoPath.isNotBlank()) deleteFacePhoto(sample.photoPath)
        }
        faceDao.deleteByName(name)
    }

    suspend fun purgeIncompatibleFaceSamples(expectedEmbeddingSize: Int): Int = withContext(Dispatchers.IO) {
        val allSamples = faceDao.getAll()
        val incompatible = allSamples.filter { it.embedding.size != expectedEmbeddingSize }
        incompatible.forEach { sample ->
            if (sample.photoPath.isNotBlank()) deleteFacePhoto(sample.photoPath)
            faceDao.deleteById(sample.id)
        }
        if (incompatible.isNotEmpty()) {
            Log.w(TAG, "Removed ${incompatible.size} incompatible face samples. Please re-enroll faces.")
        }
        incompatible.size
    }

    suspend fun updateFaceDetails(id: Long, relationship: String, notes: String) =
        withContext(Dispatchers.IO) {
            val existing = faceDao.findById(id) ?: return@withContext
            val allSamples = faceDao.findAllByName(existing.name)
            allSamples.forEach { sample ->
                faceDao.insert(
                    sample.copy(
                        relationship = relationship.trim(),
                        notes = notes.trim(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

    suspend fun deleteFaceById(id: Long) = withContext(Dispatchers.IO) {
        val existing = faceDao.findById(id) ?: return@withContext
        val allSamples = faceDao.findAllByName(existing.name)
        allSamples.forEach { sample ->
            if (sample.photoPath.isNotBlank()) deleteFacePhoto(sample.photoPath)
            faceDao.deleteById(sample.id)
        }
    }

    suspend fun faceCount(): Int = faceDao.count()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun saveFacePhoto(name: String, bitmap: Bitmap): String {
        val dir = File(filesDir, "faces").apply { mkdirs() }
        val file = File(dir, "${name.replace(" ", "_")}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    private fun deleteFacePhoto(photoPath: String) {
        val photoFile = File(photoPath)
        if (photoFile.exists() && !photoFile.delete()) {
            Log.w(TAG, "Failed to delete face photo at $photoPath")
        }
    }

    private fun aggregateFaceSamples(samples: List<FaceMemory>): List<FaceMemory> {
        if (samples.isEmpty()) return emptyList()

        val grouped = samples.groupBy { it.name.trim().lowercase() }
        return grouped.values.mapNotNull { personSamples ->
            val compatibleSamples = personSamples.mapNotNull { sample ->
                normalizeEmbedding(sample.embedding)?.let { normalized ->
                    sample to normalized
                }
            }
            if (compatibleSamples.isEmpty()) return@mapNotNull null

            val latest = personSamples.maxByOrNull { it.timestamp } ?: return@mapNotNull null
            val centroid = centroid(compatibleSamples.map { it.second }) ?: return@mapNotNull null
            latest.copy(embedding = centroid)
        }.sortedByDescending { it.timestamp }
    }

    private fun centroid(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors.first().size
        if (vectors.any { it.size != dim }) return null
        val mean = FloatArray(dim)
        vectors.forEach { vec ->
            for (i in vec.indices) mean[i] += vec[i]
        }
        for (i in mean.indices) mean[i] /= vectors.size.toFloat()
        return normalizeEmbedding(mean)
    }

    private fun normalizeEmbedding(embedding: FloatArray): FloatArray? {
        if (embedding.isEmpty()) return null
        var norm = 0f
        for (x in embedding) norm += x * x
        val denom = sqrt(norm)
        if (denom < 1e-8f) return null
        return FloatArray(embedding.size) { embedding[it] / denom }
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
