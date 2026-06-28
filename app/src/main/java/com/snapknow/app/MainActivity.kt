package com.snapknow.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.snapknow.app.camera.CameraHelper
import com.snapknow.app.camera.FaceAnalyzer
import com.snapknow.app.database.entity.FaceMemory
import com.snapknow.app.databinding.ActivityMainBinding
import com.snapknow.app.voice.TtsManager
import com.snapknow.app.voice.VoiceRecognitionManager
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var tts: TtsManager
    private lateinit var voice: VoiceRecognitionManager
    private lateinit var cameraHelper: CameraHelper
    private var isCameraStarted = false

    // Track latest face bitmaps so the "remember face" action captures the right frame
    private var latestFaceBitmaps: List<Pair<Int?, Bitmap>> = emptyList()
    private var lastFaceImageDims = Pair(1, 1)

    // Prevent TTS from re-firing on every StateFlow emission while isSpeaking=true
    private var lastSpokenResponse = ""

    // ─── Permission launcher ──────────────────────────────────────────────────

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            setupAfterPermissions()
        } else {
            Toast.makeText(this, "Camera and Microphone access are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTts()
        setupButtons()
        observeViewModel()

        if (hasRequiredPermissions()) {
            setupAfterPermissions()
        } else {
            permissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onResume()  { super.onResume();  if (hasRequiredPermissions() && ::voice.isInitialized) voice.unmute() }
    override fun onPause()   { super.onPause();   if (::voice.isInitialized) voice.mute() }
    override fun onDestroy() {
        super.onDestroy()
        if (::voice.isInitialized) voice.stop()
        tts.shutdown()
        if (::cameraHelper.isInitialized) cameraHelper.stop()
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupTts() {
        tts = TtsManager(
            context = this,
            onStart = {
                if (::voice.isInitialized) voice.mute()
            },
            onDone  = {
                viewModel.onSpeakingDone()
                // Small delay so the mic doesn't immediately catch end-of-speech noise
                binding.root.postDelayed({
                    if (::voice.isInitialized) voice.unmute()
                }, 600)
            }
        )
    }

    private fun setupAfterPermissions() {
        setupVoiceRecognition()
        setupCamera()
        updateCameraControls()
        updateMicControls(isListening = false)
    }

    private fun setupVoiceRecognition() {
        voice = VoiceRecognitionManager(
            context = this,
            onResult = { text ->
                Log.d(TAG, "Voice result: $text")
                runOnUiThread { viewModel.onVoiceInput(text) }
            },
            onListeningStateChanged = { listening ->
                runOnUiThread {
                    viewModel.onListeningChanged(listening)
                    updateMicControls(listening)
                }
            }
        )
    }

    private fun setupCamera() {
        val analyzer = FaceAnalyzer { detectedFaces, imgWidth, imgHeight ->
            // Store latest frames for the "remember this face" action
            latestFaceBitmaps = detectedFaces.map { Pair(it.trackingId, it.bitmap) }
            lastFaceImageDims  = Pair(imgWidth, imgHeight)

            // Send to ViewModel for recognition
            val labelled = viewModel.onFacesDetected(latestFaceBitmaps)

            // Build overlay data: match label to face rect
            val overlayData = detectedFaces.mapIndexed { i, face ->
                val label = labelled.getOrNull(i)?.second ?: "Unknown"
                Pair(face.bounds, label)
            }

            runOnUiThread {
                binding.faceOverlay.updateFaces(overlayData, imgWidth, imgHeight)
                // Show the "remember face" button only when a face is in frame
                binding.rememberFaceButton.visibility =
                    if (isCameraStarted && detectedFaces.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        cameraHelper = CameraHelper(
            context       = this,
            lifecycleOwner = this,
            previewView    = binding.cameraPreview,
            faceAnalyzer   = analyzer
        )
    }

    private fun setupButtons() {
        binding.startCameraButton.setOnClickListener {
            if (!::cameraHelper.isInitialized || isCameraStarted) return@setOnClickListener
            cameraHelper.start()
            isCameraStarted = true
            binding.startCameraButton.visibility = View.GONE
            updateCameraControls()
        }

        binding.startMicButton.setOnClickListener {
            if (!::voice.isInitialized) return@setOnClickListener
            voice.start()
        }

        binding.flipCameraButton.setOnClickListener {
            if (!::cameraHelper.isInitialized || !isCameraStarted) return@setOnClickListener
            cameraHelper.flipCamera()
        }

        binding.stopMicButton.setOnClickListener {
            if (!::voice.isInitialized) return@setOnClickListener
            voice.stop()
        }

        binding.showSavedFacesButton.setOnClickListener {
            viewModel.showSavedFaces(speakResult = true)
        }

        binding.manageSavedFacesButton.setOnClickListener {
            openFaceMemoryManager()
        }

        binding.rememberFaceButton.setOnClickListener {
            if (!isCameraStarted) {
                tts.speak("Please start the camera first.")
                return@setOnClickListener
            }
            val faces = latestFaceBitmaps
            if (faces.isEmpty()) {
                tts.speak("I don't see a face in the camera right now.")
                return@setOnClickListener
            }
            val bestFace = faces.maxByOrNull { it.second.width * it.second.height }
            if (bestFace != null) {
                viewModel.startFaceCapture(bestFace.second)
            }
        }
    }

    private fun openFaceMemoryManager() {
        lifecycleScope.launch {
            val faces = viewModel.getSavedFacesForManagement()
            if (faces.isEmpty()) {
                Toast.makeText(this@MainActivity, "No saved faces yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = faces.map(viewModel::formatMemoryDisplay).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Manage face memories")
                .setItems(items) { _, which ->
                    showFaceMemoryActions(faces[which])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showFaceMemoryActions(face: FaceMemory) {
        val descriptor = face.relationship.ifBlank { face.notes }.ifBlank { "add relation/description" }
        val message = "Talk about ${face.name} to remember them by.\nDisplayed as: ${face.name}.$descriptor"
        AlertDialog.Builder(this)
            .setTitle(face.name)
            .setMessage(message)
            .setPositiveButton("Edit details") { _, _ ->
                showEditFaceDialog(face)
            }
            .setNeutralButton("Remove") { _, _ ->
                confirmDeleteFace(face)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showEditFaceDialog(face: FaceMemory) {
        val relationInput = EditText(this).apply {
            hint = "Relation (example: sister, coworker)"
            setText(face.relationship)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val notesInput = EditText(this).apply {
            hint = "Description to remember (example: works with me on AI)"
            setText(face.notes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(relationInput)
            addView(notesInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit ${face.name}")
            .setMessage("Talk about ${face.name} so the app remembers them better.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    viewModel.updateFaceDetails(
                        id = face.id,
                        relationship = relationInput.text.toString(),
                        notes = notesInput.text.toString()
                    )
                    val updatedLine = viewModel.formatMemoryDisplay(
                        face.copy(
                            relationship = relationInput.text.toString().trim(),
                            notes = notesInput.text.toString().trim()
                        )
                    )
                    Toast.makeText(this@MainActivity, "Saved: $updatedLine", Toast.LENGTH_SHORT).show()
                    openFaceMemoryManager()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFace(face: FaceMemory) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${face.name}?")
            .setMessage("This will delete ${face.name}'s memory and photo.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteFaceById(face.id)
                    Toast.makeText(this@MainActivity, "Removed ${face.name}", Toast.LENGTH_SHORT).show()
                    openFaceMemoryManager()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.statusText.text = if (state.awaitingFaceNameForBitmap != null) {
                        "Tap Start Mic, then say the person's name and relationship."
                    } else if (state.isListening) {
                        state.status
                    } else if (isCameraStarted) {
                        "Camera is on. Tap Start Mic when you want to speak."
                    } else {
                        "Start the camera or tap Start Mic when you're ready"
                    }
                    binding.responseText.text = state.response
                    binding.modelStatusChip.text = state.embeddingModelStatus
                    binding.voicePromptText.text = if (state.awaitingFaceNameForBitmap != null) {
                        "Example: This is John, my son. He helps me with groceries."
                    } else if (isCameraStarted) {
                        "Use Remember Face for a person, or Start Mic for another request."
                    } else {
                        "Start the camera when you want live face preview, or use Start Mic for voice-only help."
                    }

                    // Speak new responses — guard against duplicate calls on every state update
                    val resp = state.response
                    if (state.isSpeaking && resp.isNotBlank() && resp != lastSpokenResponse) {
                        lastSpokenResponse = resp
                        tts.speak(resp)
                    }
                    if (!state.isSpeaking) lastSpokenResponse = ""
                }
            }
        }
    }

    private fun updateCameraControls() {
        binding.flipCameraButton.visibility = if (isCameraStarted) View.VISIBLE else View.GONE
        if (!isCameraStarted) {
            binding.rememberFaceButton.visibility = View.GONE
            binding.faceOverlay.updateFaces(emptyList(), lastFaceImageDims.first, lastFaceImageDims.second)
        }
    }

    private fun updateMicControls(isListening: Boolean) {
        binding.startMicButton.isEnabled = !isListening
        binding.stopMicButton.isEnabled = isListening
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun hasRequiredPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audio  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return camera == PackageManager.PERMISSION_GRANTED &&
               audio  == PackageManager.PERMISSION_GRANTED
    }
}
