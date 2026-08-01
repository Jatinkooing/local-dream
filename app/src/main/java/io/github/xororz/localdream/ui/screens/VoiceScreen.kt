package io.github.xororz.localdream.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import io.github.xororz.localdream.data.MultimodalBackend
import io.github.xororz.localdream.service.BackendService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE = 16000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var transcribedText by remember {
        mutableStateOf("Tap the microphone button below to start offline voice recording with Whisper GGUF...")
    }
    var selectedLanguage by remember { mutableStateOf("English (en)") }

    // Whisper is CPU-oriented on mobile: modest threads, no GPU layers.
    val cpuThreads = 4
    val gpuLayers = 0

    val backendState by BackendService.backendState.collectAsState()

    val capturing = remember { AtomicBoolean(false) }
    var pcmFile by remember { mutableStateOf<File?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    fun startRecording() {
        val file = File(context.cacheDir, "voice_input_${System.currentTimeMillis()}.pcm")
        capturing.set(true)
        isRecording = true
        transcribedText = "Recording... Speak clearly into the microphone."
        recordingJob = scope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minBuffer, 4096)
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone unavailable on this device")
                }
                recorder.startRecording()
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(bufferSize)
                    while (capturing.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                file.delete()
                withContext(Dispatchers.Main) {
                    transcribedText = "⚠️ Recording failed: ${e.message}"
                    isRecording = false
                }
            } finally {
                try {
                    recorder?.stop()
                } catch (e: Exception) {
                    android.util.Log.w("VoiceScreen", "recorder stop failed", e)
                }
                recorder?.release()
            }
        }
        pcmFile = file
    }

    fun stopRecordingAndTranscribe() {
        capturing.set(false)
        isRecording = false
        isTranscribing = true
        scope.launch {
            recordingJob?.join()
            recordingJob = null
            val recording = pcmFile
            try {
                // The voice engine runs in the same lightweight multimodal
                // server; whisper weights are resolved from its model dir.
                MultimodalBackend.ensureBackend(context, MultimodalBackend.WHISPER_MODEL_ID)
                MultimodalBackend.awaitHealthy(15_000)
                MultimodalBackend.pushRuntimeConfig(cpuThreads, gpuLayers)

                transcribedText = when {
                    recording == null || !recording.exists() || recording.length() == 0L ->
                        "(no audio captured — try again and speak for a moment longer)"

                    else -> {
                        val text = MultimodalBackend.transcribeAudio(
                            audioFile = recording,
                            language = selectedLanguage.substringAfter("(").substringBefore(")"),
                            numThreads = cpuThreads,
                            nGpuLayers = gpuLayers,
                        )
                        text.ifBlank { "(no speech detected)" }
                    }
                }
            } catch (e: Exception) {
                transcribedText = "⚠️ Transcription failed: ${e.message ?: "unknown error"}\n\n" +
                    "Make sure the Whisper Tiny GGUF model is downloaded from the model list."
            } finally {
                isTranscribing = false
                // Recorded PCM is single-use: keep the cache (and RAM on
                // 5GB devices) clean after every transcription.
                recording?.delete()
                pcmFile = null
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required for speech-to-text", Toast.LENGTH_LONG).show()
        }
    }

    // Pulsing animation when recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_scale",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "airound Offline Speech-to-Text",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Whisper GGUF (tiny-q4_0) • " + when (backendState) {
                                is BackendService.BackendState.Running -> "engine live"
                                is BackendService.BackendState.Starting -> "engine starting…"
                                is BackendService.BackendState.Error -> "engine error"
                                else -> "100% Private"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Transcription", transcribedText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(
                        onClick = {
                            transcribedText = ""
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Language selector & model info banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Active Whisper Engine",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "whisper-tiny-q4_0.gguf (~39MB)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = selectedLanguage,
                            modifier = Modifier.padding(6.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            // Transcription Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Transcription Output:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isTranscribing) {
                            "Processing audio through offline Whisper GGUF neural network..."
                        } else {
                            transcribedText
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isTranscribing) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // Record / Stop Button
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .clickable {
                        if (isTranscribing) return@clickable

                        if (!isRecording) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                startRecording()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            stopRecordingAndTranscribe()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }

            Text(
                text = if (isRecording) "Tap to stop recording & transcribe" else "Tap microphone to record voice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
