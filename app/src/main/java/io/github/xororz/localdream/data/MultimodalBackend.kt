package io.github.xororz.localdream.data

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the local C++ multimodal server (libstable_diffusion_core.so
 * launched with --multimodal_mode). Wraps the OpenAI-compatible endpoints the
 * native process exposes on loopback:
 *
 *   POST /v1/chat/completions      (OpenAI SSE token streaming)
 *   POST /v1/audio/transcriptions  (Whisper GGUF transcription)
 *   POST /v1/multimodal/config     (CPU threads / GPU offload / RAM budget)
 *   GET  /health                   (readiness probe)
 *
 * All functions are cancellation-friendly and never throw across the public
 * boundary except where documented (chat / transcription surface IO errors so
 * the UI can render them inline).
 */
object MultimodalBackend {
    private const val TAG = "MultimodalBackend"
    private const val BASE_URL = "http://127.0.0.1:8081"

    // BackendService --type value that starts the lightweight GGUF server
    // (no diffusion init) for chat / whisper workloads.
    const val MULTIMODAL_BACKEND_TYPE = "multimodal"

    // Logical whisper model identifier the voice studio asks the server to
    // resolve (mapped onto the first *.gguf in its model directory).
    const val WHISPER_MODEL_NAME = "whisper-tiny-q4_0"

    // Model id of the pre-registered whisper download entry, also used as the
    // BackendService modelId when the voice studio owns the backend.
    const val WHISPER_MODEL_ID = "whisper_tiny_q4_0"

    // SSE streams are unbounded: read timeout must be zero (infinite).
    private val streamClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val shortClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

    /**
     * POST /v1/multimodal/config. @return true when the backend acknowledged
     * the new CPU thread / GPU offload layer configuration.
     */
    suspend fun pushRuntimeConfig(
        numThreads: Int,
        nGpuLayers: Int,
        memoryLimitBytes: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
                .put("num_threads", numThreads)
                .put("n_gpu_layers", nGpuLayers)
            if (memoryLimitBytes != null && memoryLimitBytes > 0) {
                json.put("memory_limit_bytes", memoryLimitBytes)
            }
            val request = Request.Builder()
                .url("$BASE_URL/v1/multimodal/config")
                .post(json.toString().toRequestBody(jsonMedia))
                .build()
            shortClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "pushRuntimeConfig failed: ${e.message}")
            false
        }
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/health").get().build()
            shortClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Polls /health until the server answers or [timeoutMs] elapses. */
    suspend fun awaitHealthy(timeoutMs: Long, intervalMs: Long = 250L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return true
            delay(intervalMs)
        }
        return isHealthy()
    }

    /**
     * POST /v1/chat/completions with stream=true and replays the OpenAI SSE
     * stream: every `data: {choices:[{delta:{content:...}}]}` delta is pushed
     * into [onToken] as it arrives. @return the full accumulated response.
     * @throws IOException on transport / HTTP errors (UI renders these).
     */
    suspend fun streamChatCompletion(
        model: String,
        messages: List<Pair<String, String>>,
        numThreads: Int,
        nGpuLayers: Int,
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val msgArray = JSONArray()
        for ((role, content) in messages) {
            msgArray.put(JSONObject().put("role", role).put("content", content))
        }
        val bodyJson = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", msgArray)
            .put("num_threads", numThreads)
            .put("n_gpu_layers", nGpuLayers)

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .post(bodyJson.toString().toRequestBody(jsonMedia))
            .header("Accept", "text/event-stream")
            .build()

        val accumulated = StringBuilder()
        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty().take(300)
                throw IOException("Chat endpoint error HTTP ${response.code}: $errorBody")
            }
            val source = response.body?.source()
                ?: throw IOException("Chat endpoint returned an empty body")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substringAfter("data:").trim()
                if (payload == "[DONE]") break
                try {
                    val choices = JSONObject(payload).optJSONArray("choices") ?: continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val token = delta.optString("content", "")
                    if (token.isNotEmpty()) {
                        accumulated.append(token)
                        onToken(token)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ignoring malformed SSE chunk: ${e.message}")
                }
            }
        }
        accumulated.toString()
    }

    /**
     * POST /v1/audio/transcriptions against a PCM16 mono recording captured
     * by the voice studio. @return the transcription text.
     * @throws IOException on transport / HTTP errors.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        language: String,
        numThreads: Int,
        nGpuLayers: Int,
        model: String = WHISPER_MODEL_NAME,
    ): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject()
            .put("model", model)
            .put("language", language)
            .put("audio_path", audioFile.absolutePath)
            .put("num_threads", numThreads)
            .put("n_gpu_layers", nGpuLayers)
        val request = Request.Builder()
            .url("$BASE_URL/v1/audio/transcriptions")
            .post(bodyJson.toString().toRequestBody(jsonMedia))
            .build()
        shortClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Transcription error HTTP ${response.code}: ${body.take(300)}")
            }
            JSONObject(body).optString("text", "")
        }
    }

    /**
     * Declares that [modelId] should be served by the lightweight multimodal
     * backend. BackendService reconciles idempotently: an identical running
     * config is reused, anything else triggers a restart.
     */
    fun ensureBackend(context: Context, modelId: String) {
        try {
            val intent = Intent(context, BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("backendType", MULTIMODAL_BACKEND_TYPE)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "ensureBackend failed: ${e.message}")
        }
    }
}
