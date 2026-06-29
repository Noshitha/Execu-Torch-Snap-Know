package com.snapknow.app

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapknow.app.database.MemoryRepository
import com.snapknow.app.inference.FaceEmbeddingModel
import com.snapknow.app.nlp.Command
import com.snapknow.app.nlp.CommandParser
import com.snapknow.app.voice.SpeechBackend
import com.snapknow.app.voice.SpeechOutputRequest
import com.snapknow.app.voice.SpeechTranscriberMode
import com.snapknow.app.voice.SpeechTranscriberState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

private const val TAG = "MainViewModel"

// ─── UI State ─────────────────────────────────────────────────────────────────

data class UiState(
    /** Short status line shown in the top chip */
    val status: String = "Ready — say something",
    /** Full response shown in the bottom card */
    val response: String = "",
    /** True while the mic is active */
    val isListening: Boolean = false,
    /** True while TTS is speaking (mic should be muted) */
    val isSpeaking: Boolean = false,
    /** Explicit one-shot speech request consumed by the activity layer */
    val pendingSpeech: PendingSpeech? = null,
    /** Human-readable status for the active speech backend */
    val speechStatus: String = "Speech output initializing…",
    /** Backend currently used for spoken responses */
    val speechBackend: SpeechBackend = SpeechBackend.ANDROID_SYSTEM,
    /** Most recent speech error, if any */
    val speechError: String? = null,
    /** Current speech-to-text session state */
    val speechInputState: SpeechTranscriberState = SpeechTranscriberState(
        mode = SpeechTranscriberMode.IDLE,
        engineLabel = "Android system speech",
        message = "Tap Start Mic when you're ready.",
        canStart = true,
        canStop = false
    ),
    /**
     * When non-null, the app is awaiting a name for this face.
     * The UI disables the "query face" flow and pipes the next voice input
     * as the person's name.
     */
    val awaitingFaceNameForBitmap: Bitmap? = null,
    /** Explicit face-naming mode flag so the UI can run a dedicated mic flow. */
    val isFaceNamingMode: Boolean = false,
    /** Prompt shown while the app is waiting for a face name/relationship. */
    val faceNamingPrompt: String = "",
    /** UI-facing summary of saved faces for a lightweight "show faces" action. */
    val savedFacesSummary: String = "",
    /** Human-readable status for the ExecuTorch model */
    val embeddingModelStatus: String = "Loading…",
    /** Human-readable status for the TFLite detector path */
    val objectDetectorStatus: String = "Object detector loading…",
    /** Most recent object detector summary shown in the bottom card */
    val objectDetectionSummary: String = "Object highlights will appear here when the camera is on."
)

data class PendingSpeech(
    val id: Long,
    val request: SpeechOutputRequest
)

// ─────────────────────────────────────────────────────────────────────────────

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var nextSpeechRequestId = 1L

    private val repo = MemoryRepository(application)

    // Constructed immediately (lightweight); preload() does the heavy IO on a bg thread
    val faceEmbeddingModel = FaceEmbeddingModel(application)

    // Throttle TTS face announcements (don't repeat same person within 8 s)
    private val recentlyAnnouncedFaces = LinkedHashMap<Int, Long>()
    private val faceLabelsByTrackingId = ConcurrentHashMap<Int, String>()
    private val faceResolutionInFlight = ConcurrentHashMap.newKeySet<Int>()

    init {
        // Load the 107 MB model off the main thread so the UI is never blocked
        updateState { copy(embeddingModelStatus = "Loading model…") }
        viewModelScope.launch {
            faceEmbeddingModel.preload()
            if (faceEmbeddingModel.isAvailable) {
                val removed = repo.purgeIncompatibleFaceSamples(faceEmbeddingModel.embeddingSize)
                if (removed > 0) {
                    updateState {
                        copy(
                            response = "I removed $removed old face samples from a previous model format. Please re-save those faces."
                        )
                    }
                }
            }
            val status = if (faceEmbeddingModel.isAvailable)
                "Face recognition ON (PyTorch Mobile)"
            else
                "Detection only — model not loaded"
            updateState { copy(embeddingModelStatus = status) }
            Log.i(TAG, status)
        }
    }

    // ─── Voice input entry point ──────────────────────────────────────────────

    /**
     * Called by [MainActivity] every time a complete utterance is recognised.
     * Routes the text to the appropriate handler based on current mode.
     */
    fun onVoiceInput(text: String) {
        Log.d(TAG, "Voice input: '$text'")

        // Are we waiting for a name to save a face?
        val pendingBitmap = _uiState.value.awaitingFaceNameForBitmap
        if (pendingBitmap != null) {
            handlePendingFaceNamingInput(text, pendingBitmap)
            return
        }

        when (val cmd = CommandParser.parse(text)) {
            is Command.StoreObject   -> handleStoreObject(cmd)
            is Command.QueryObject   -> handleQueryObject(cmd)
            is Command.StoreFace     -> {
                // "This is John" without a live face capture — user must point camera + speak
                // The camera layer calls onFaceDetectedForStorage() which sets awaitingFaceNameForBitmap,
                // so StoreFace here means they said the name while looking at the person.
                val bitmap = _uiState.value.awaitingFaceNameForBitmap
                if (bitmap != null) {
                    handleFaceNameFromCommand(cmd, bitmap)
                } else {
                    speak("Please point the camera at the person first, then say their name.")
                }
            }
            is Command.QueryFace     -> speak("Point the camera at the person so I can recognise them.")
            is Command.ListMemories  -> handleListMemories()
            is Command.ForgetObject  -> handleForgetObject(cmd)
            is Command.ForgetFace    -> handleForgetFace(cmd)
            is Command.Unknown       -> {
                Log.d(TAG, "Unknown command: ${cmd.rawText}")
                // Don't respond to every unknown utterance — only if it seems intentional
                if (text.length > 4) speak("I didn't quite get that. Try saying where you put something, or who someone is.")
            }
        }
    }

    // ─── Face detection callback (from FaceAnalyzer) ─────────────────────────

    /**
     * Called per camera frame with the list of detected faces and their
     * embeddings (if the model is loaded).
     *
     * Returns a list of (label, trackingId) for the overlay.
     */
    fun onFacesDetected(
        faces: List<Pair<Int?, Bitmap>>   // (trackingId, cropped bitmap)
    ): List<Pair<Int?, String>> {
        if (faces.isEmpty()) {
            faceLabelsByTrackingId.clear()
            faceResolutionInFlight.clear()
            synchronized(recentlyAnnouncedFaces) { recentlyAnnouncedFaces.clear() }
            return emptyList()
        }
        if (!faceEmbeddingModel.isAvailable) {
            return faces.map { (id, _) -> Pair(id, "Unknown") }
        }

        val activeTrackingIds = faces.mapNotNull { it.first }.toSet()
        faceLabelsByTrackingId.keys.retainAll(activeTrackingIds)
        faceResolutionInFlight.retainAll(activeTrackingIds)
        synchronized(recentlyAnnouncedFaces) {
            recentlyAnnouncedFaces.keys.retainAll(activeTrackingIds)
        }

        faces.forEach { (trackingId, bitmap) ->
            resolveFaceLabelAsync(trackingId, bitmap)
        }

        return faces.map { (trackingId, _) ->
            Pair(trackingId, trackingId?.let(faceLabelsByTrackingId::get) ?: "Unknown")
        }
    }

    fun onObjectDetectionsUpdated(summary: String) {
        updateState { copy(objectDetectionSummary = summary) }
    }

    fun onObjectDetectorStatusChanged(status: String) {
        updateState { copy(objectDetectorStatus = status) }
    }

    /**
     * Called when user wants to save the current face.
     * Transitions to "awaiting name" mode and prompts the user to speak.
     */
    fun startFaceCapture(faceBitmap: Bitmap) {
        startFaceNamingFlow(faceBitmap)
    }

    fun startFaceNamingFlow(faceBitmap: Bitmap) {
        val prompt = "Who is this? Say name and relation/description so I remember them, like: It's Maya, my friend."
        updateState {
            copy(
                awaitingFaceNameForBitmap = faceBitmap,
                isFaceNamingMode = true,
                faceNamingPrompt = prompt,
                status = "Listening for a name…",
                response = prompt,
                isSpeaking = true,
                pendingSpeech = PendingSpeech(
                    id = nextSpeechRequestId++,
                    request = SpeechOutputRequest(text = prompt)
                )
            )
        }
    }

    fun stopFaceNamingFlow() {
        updateState {
            copy(
                awaitingFaceNameForBitmap = null,
                isFaceNamingMode = false,
                faceNamingPrompt = "",
                status = "Tap mic to speak"
            )
        }
    }

    fun submitFaceNamingInput(spokenText: String) {
        val pendingBitmap = _uiState.value.awaitingFaceNameForBitmap
        if (pendingBitmap == null) {
            speak("Point the camera at the person first, then start the naming flow.")
            return
        }
        handlePendingFaceNamingInput(spokenText, pendingBitmap)
    }

    fun showSavedFaces(speakResult: Boolean = false) {
        viewModelScope.launch {
            val faces = repo.getAllFaces()
            val response = buildSavedFacesSummary(faces)
            updateState { copy(savedFacesSummary = response, response = response) }
            if (speakResult) speak(response)
        }
    }

    suspend fun getSavedFacesForManagement(): List<com.snapknow.app.database.entity.FaceMemory> =
        repo.getAllFaces()

    suspend fun updateFaceDetails(id: Long, relationship: String, notes: String) {
        repo.updateFaceDetails(id, relationship, notes)
        clearResolvedFaceCache()
        val updatedFaces = repo.getAllFaces()
        updateState { copy(savedFacesSummary = buildSavedFacesSummary(updatedFaces)) }
    }

    suspend fun deleteFaceById(id: Long) {
        repo.deleteFaceById(id)
        clearResolvedFaceCache()
        val updatedFaces = repo.getAllFaces()
        updateState { copy(savedFacesSummary = buildSavedFacesSummary(updatedFaces)) }
    }

    fun formatMemoryDisplay(face: com.snapknow.app.database.entity.FaceMemory): String {
        val descriptor = face.relationship.ifBlank { face.notes }.ifBlank { "add relation/description" }
        return "${face.name}.$descriptor"
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private fun handleStoreObject(cmd: Command.StoreObject) {
        viewModelScope.launch {
            repo.storeObject(cmd.objectName, cmd.location)
            val response = "Got it! I'll remember that your ${cmd.objectName} is ${cmd.location}."
            updateState { copy(response = response) }
            speak(response)
        }
    }

    private fun handleQueryObject(cmd: Command.QueryObject) {
        viewModelScope.launch {
            val found = repo.findObject(cmd.objectName)
            val response = if (found != null) {
                val ago = timeAgo(found.timestamp)
                // location already includes preposition, e.g. "on the table"
                "I remember! Your ${found.objectName} is ${found.location}. You told me $ago."
            } else {
                "I don't remember where your ${cmd.objectName} is. Try telling me next time you put it somewhere."
            }
            updateState { copy(response = response) }
            speak(response)
        }
    }

    private fun handlePendingFaceNamingInput(spokenText: String, faceBitmap: Bitmap) {
        val details = CommandParser.parseFaceDetails(spokenText, allowBareName = true)
        if (details == null || details.name.isBlank()) {
            speak("I didn't catch the person's name. Please try again.")
            return
        }
        stopFaceNamingFlow()
        saveFace(details.name, details.relationship, details.notes, faceBitmap)
    }

    private fun handleFaceNameFromCommand(cmd: Command.StoreFace, faceBitmap: Bitmap) {
        stopFaceNamingFlow()
        saveFace(cmd.name, cmd.relationship, "", faceBitmap)
    }

    private fun saveFace(name: String, relationship: String, notes: String, bitmap: Bitmap) {
        if (name.isBlank()) {
            speak("I didn't catch a name. Please try again.")
            return
        }
        viewModelScope.launch {
            val embedding = withContext(Dispatchers.Default) {
                faceEmbeddingModel.getEmbedding(bitmap)
            }
            if (embedding == null) {
                speak("Face recognition model isn't loaded yet. I saved the name but won't be able to recognise this person automatically.")
                return@launch
            }
            val storedId = repo.storeFace(name, relationship, embedding, bitmap, notes)
            if (storedId <= 0L) {
                speak("I couldn't save that face sample. Please try again.")
                return@launch
            }
            clearResolvedFaceCache()
            val savedFacesSummary = buildSavedFacesSummary(repo.getAllFaces())
            val rel = if (relationship.isNotBlank()) ", your $relationship" else ""
            val response = "Nice to meet $name$rel! Saved one face sample. For better accuracy, add 2 to 4 more samples."
            updateState { copy(response = response, savedFacesSummary = savedFacesSummary) }
            speak(response)
        }
    }

    private fun handleListMemories() {
        viewModelScope.launch {
            val objects = repo.getAllObjects()
            val faces = repo.getAllFaces()
            val sb = StringBuilder()
            if (objects.isEmpty() && faces.isEmpty()) {
                sb.append("I don't have any memories saved yet.")
            } else {
                if (objects.isNotEmpty()) {
                    sb.append("Things I remember: ")
                    objects.take(5).forEach { obj ->
                        sb.append("Your ${obj.objectName} is ${obj.location}. ")
                    }
                }
                if (faces.isNotEmpty()) {
                    sb.append("People I know: ")
                    faces.forEach { face ->
                        val rel = if (face.relationship.isNotBlank()) " (your ${face.relationship})" else ""
                        sb.append("${face.name}$rel. ")
                    }
                }
            }
            val response = sb.toString()
            updateState {
                copy(
                    response = response,
                    savedFacesSummary = buildSavedFacesSummary(faces)
                )
            }
            speak(response)
        }
    }

    private fun handleForgetObject(cmd: Command.ForgetObject) {
        viewModelScope.launch {
            repo.forgetObject(cmd.objectName)
            speak("Done, I've forgotten where your ${cmd.objectName} was.")
        }
    }

    private fun handleForgetFace(cmd: Command.ForgetFace) {
        viewModelScope.launch {
            repo.forgetFace(cmd.name)
            clearResolvedFaceCache()
            val savedFacesSummary = buildSavedFacesSummary(repo.getAllFaces())
            updateState { copy(savedFacesSummary = savedFacesSummary) }
            speak("Okay, I've forgotten ${cmd.name}.")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    fun speak(text: String) {
        updateState {
            copy(
                response = text,
                isSpeaking = true,
                pendingSpeech = PendingSpeech(
                    id = nextSpeechRequestId++,
                    request = SpeechOutputRequest(text = text)
                )
            )
        }
    }

    fun onSpeechRequestConsumed(id: Long) {
        if (_uiState.value.pendingSpeech?.id == id) {
            updateState { copy(pendingSpeech = null) }
        }
    }

    fun onSpeechInputStateChanged(speechInputState: SpeechTranscriberState) {
        val isListening = speechInputState.mode == SpeechTranscriberMode.STARTING ||
            speechInputState.mode == SpeechTranscriberMode.LISTENING ||
            speechInputState.mode == SpeechTranscriberMode.PROCESSING ||
            speechInputState.mode == SpeechTranscriberMode.MUTED
        val status = when {
            speechInputState.mode == SpeechTranscriberMode.LISTENING && _uiState.value.isFaceNamingMode ->
                "Listening for a name…"
            speechInputState.mode == SpeechTranscriberMode.PROCESSING && _uiState.value.isFaceNamingMode ->
                "Saving face details…"
            _uiState.value.isFaceNamingMode && speechInputState.mode == SpeechTranscriberMode.IDLE ->
                "Ready for the person's name."
            else -> speechInputState.message
        }
        updateState {
            copy(
                isListening = isListening,
                status = status,
                speechInputState = speechInputState
            )
        }
    }

    fun onSpeechStarted() {
        updateState { copy(isSpeaking = true, speechError = null) }
    }

    fun onSpeechReady(backend: SpeechBackend, status: String) {
        updateState {
            copy(
                speechBackend = backend,
                speechStatus = status,
                speechError = null
            )
        }
    }

    fun onSpeechFailed(backend: SpeechBackend, message: String, spokenText: String?) {
        updateState {
            copy(
                isSpeaking = false,
                speechBackend = backend,
                speechStatus = message,
                speechError = message,
                response = spokenText ?: response
            )
        }
    }

    fun onSpeakingDone() {
        updateState { copy(isSpeaking = false) }
    }

    private fun updateState(block: UiState.() -> UiState) {
        _uiState.value = _uiState.value.block()
    }

    private fun resolveFaceLabelAsync(trackingId: Int?, bitmap: Bitmap) {
        if (trackingId == null || !faceResolutionInFlight.add(trackingId)) return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val embedding = faceEmbeddingModel.getEmbedding(bitmap) ?: return@launch
                val match = repo.matchFace(embedding)
                val label = match?.first?.let(::formatFaceLabel) ?: "Unknown"
                faceLabelsByTrackingId[trackingId] = label

                if (match != null) maybeAnnounceFace(trackingId, match.first)
            } finally {
                faceResolutionInFlight.remove(trackingId)
            }
        }
    }

    private suspend fun maybeAnnounceFace(trackingId: Int, face: com.snapknow.app.database.entity.FaceMemory) {
        val shouldAnnounce = synchronized(recentlyAnnouncedFaces) {
            val now = System.currentTimeMillis()
            val lastAnnounced = recentlyAnnouncedFaces[trackingId] ?: 0L
            if (now - lastAnnounced > 8_000L) {
                recentlyAnnouncedFaces[trackingId] = now
                true
            } else {
                false
            }
        }

        if (shouldAnnounce) {
            withContext(Dispatchers.Main) {
                speak("That's ${formatFaceAnnouncement(face)}.")
            }
        }
    }

    private fun clearResolvedFaceCache() {
        faceLabelsByTrackingId.clear()
        faceResolutionInFlight.clear()
        synchronized(recentlyAnnouncedFaces) { recentlyAnnouncedFaces.clear() }
    }

    private fun formatFaceLabel(face: com.snapknow.app.database.entity.FaceMemory): String =
        if (face.relationship.isNotBlank()) "${face.name} · ${face.relationship}" else face.name

    private fun formatFaceAnnouncement(face: com.snapknow.app.database.entity.FaceMemory): String =
        if (face.relationship.isNotBlank()) "${face.name}, your ${face.relationship}" else face.name

    private fun buildSavedFacesSummary(faces: List<com.snapknow.app.database.entity.FaceMemory>): String {
        if (faces.isEmpty()) return "No faces saved yet."

        return buildString {
            append("Saved faces: ")
            faces.forEachIndexed { index, face ->
                if (index > 0) append(" ")
                append(formatMemoryDisplay(face))
                append(".")
            }
        }
    }

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L          -> "just now"
            diff < 3_600_000L       -> "${diff / 60_000}  minutes ago"
            diff < 86_400_000L      -> "${diff / 3_600_000} hours ago"
            diff < 604_800_000L     -> "${diff / 86_400_000} days ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    override fun onCleared() {
        super.onCleared()
        faceEmbeddingModel.close()
    }
}
