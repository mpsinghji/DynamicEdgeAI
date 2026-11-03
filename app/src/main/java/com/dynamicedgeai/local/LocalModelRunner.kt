package com.dynamicedgeai.local

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Log
import com.dynamicedgeai.router.ChatMessage
import com.dynamicedgeai.util.IntegrityResult
import com.dynamicedgeai.util.ModelIntegrityChecker
import com.dynamicedgeai.util.ModelStatus
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.random.Random

/**
 * Unified local inference runner.
 *
 * Routes by model type:
 *  - Gemma 2B (.bin) / Gemma 4 (.litertlm) -> MediaPipe / LiteRT LlmInference & LlmInferenceSession
 *  - TinyLlama (.gguf) / Qwen (.gguf)      -> GgufModelEngine (llama.cpp)
 */
class LocalModelRunner private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val tag = "LocalModelRunner"

    // ── Persistence ───────────────────────────────────────────────────────────

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("LocalModelPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_KEY_MODEL = "selected_model"

        @Volatile
        private var instance: LocalModelRunner? = null

        fun getInstance(context: Context): LocalModelRunner {
            return instance ?: synchronized(this) {
                instance ?: LocalModelRunner(context).also { instance = it }
            }
        }
    }

    private fun restoreSavedModel(): LocalModel {
        val saved = prefs.getString(PREF_KEY_MODEL, null)
        return LocalModel.values().firstOrNull { it.name == saved } ?: LocalModel.GEMMA_2B
    }

    private fun saveModel(model: LocalModel) {
        prefs.edit().putString(PREF_KEY_MODEL, model.name).apply()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    var currentModel: LocalModel = restoreSavedModel()
        private set

    var isInitialized = false
        private set

    // MediaPipe / LiteRT engine — used for Gemma models (.bin and .litertlm)
    private var llmInference: LlmInference? = null

    // llama.cpp engine — used for all .gguf models
    private var ggufEngine: GgufModelEngine? = null

    var diagnosticInfo: String = "Ready"
        private set

    // ── Path resolution ───────────────────────────────────────────────────────

    fun getModelPath(model: LocalModel): File {
        val adb = File("/data/local/tmp/${model.fileName}")
        if (adb.exists()) return adb
        val ext = File(appContext.getExternalFilesDir(null), model.fileName)
        if (ext.exists()) return ext
        val int_ = File(appContext.filesDir, model.fileName)
        if (int_.exists()) return int_
        return ext   // canonical expected path
    }

    fun isModelAvailable(model: LocalModel): Boolean =
        getModelStatus(model) == ModelStatus.DOWNLOADED

    fun getModelStatus(model: LocalModel): ModelStatus {
        val path = getModelPath(model)
        if (!path.exists()) return ModelStatus.NOT_DOWNLOADED
        return when (ModelIntegrityChecker.checkIntegrity(path, model.expectedSizeBytes)) {
            IntegrityResult.VALID                                      -> ModelStatus.DOWNLOADED
            IntegrityResult.MISSING                                    -> ModelStatus.NOT_DOWNLOADED
            IntegrityResult.SIZE_MISMATCH, IntegrityResult.CORRUPTED  -> ModelStatus.CORRUPTED
        }
    }

    fun deleteModel(model: LocalModel): Boolean {
        val path = getModelPath(model)
        val deleted = if (path.exists()) path.delete() else true
        if (model == currentModel) tearDown()
        return deleted
    }

    fun switchModel(model: LocalModel): Boolean {
        tearDown()
        currentModel = model
        saveModel(model)
        return isModelAvailable(model)
    }

    fun hasAnyModelAvailable() = LocalModel.values().any { isModelAvailable(it) }
    fun getFirstAvailableModel() = LocalModel.values().firstOrNull { isModelAvailable(it) }

    // ── Engine lifecycle ──────────────────────────────────────────────────────

    private fun tearDown() {
        isInitialized = false
        try { llmInference?.close() } catch (e: Exception) {}
        llmInference = null
        ggufEngine?.release()
        ggufEngine = null
    }

    /**
     * Ensures the correct engine is loaded for [currentModel].
     * @return true if inference can proceed.
     */
    private suspend fun ensureInitialized(hasImage: Boolean = false): Boolean {
        if (isInitialized && (llmInference != null || ggufEngine != null)) return true

        val modelFile = getModelPath(currentModel)
        if (!modelFile.exists()) {
            diagnosticInfo = "Stage: File Verification\nError: Model file does not exist on disk.\nSuggestion: Download the model in Model Manager."
            Log.e(tag, "Model file not found: ${modelFile.absolutePath}")
            return false
        }
        if (getModelStatus(currentModel) != ModelStatus.DOWNLOADED) {
            diagnosticInfo = "Stage: Integrity Check\nError: Model file size or checksum mismatch.\nSuggestion: Use the Repair button in Model Manager."
            Log.e(tag, "Model status not DOWNLOADED for ${currentModel.displayName}")
            return false
        }

        return if (currentModel.isGguf) {
            initGgufEngine(modelFile)
        } else {
            initMediaPipeEngine(modelFile, hasImage)
        }
    }

    private suspend fun initGgufEngine(modelFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val engine = GgufModelEngine(appContext.contentResolver, currentModel)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    modelFile
                )

                val result: String = engine.load(uri.toString())

                if (result == "OK") {
                    ggufEngine = engine
                    isInitialized = true
                    Log.i(tag, "GGUF engine ready: ${currentModel.displayName}")
                    true
                } else {
                    engine.release()
                    diagnosticInfo = "Stage: GGUF Model Loading\nError: $result\nSuggestion: Ensure sufficient free RAM is available."
                    Log.e(tag, "GGUF engine failed: $result")
                    false
                }
            } catch (e: Throwable) {
                diagnosticInfo = "Stage: GGUF Exception\nError: ${e.localizedMessage ?: "Unknown error"}\nSuggestion: Restart the app or check logcat."
                Log.e(tag, "GGUF engine exception: ${e.localizedMessage}", e)
                false
            }
        }
    }

    private fun initMediaPipeEngine(modelFile: File, hasImage: Boolean = false): Boolean {
        if (!modelFile.exists() || modelFile.length() < 1000L) {
            diagnosticInfo = "Stage: Model File Check\nError: File is missing or incomplete (${modelFile.length()} bytes).\nSuggestion: Re-download the model from the Model Manager."
            return false
        }

        // Release any existing instances to reclaim native memory
        try { llmInference?.close() } catch (_: Exception) {}
        llmInference = null
        isInitialized = false
        System.gc()

        val isLargeModel = currentModel.expectedSizeBytes > 2_000_000_000L // e.g. Gemma 4 E4B-it (3.4 GB)
        val maxTokens = if (isLargeModel) 1024 else 2048
        val localSupportsImage = ModelCapabilityRegistry.supportsImage(currentModel)
        val numImages = if (hasImage && localSupportsImage) 1 else 0

        Log.i(tag, "Loading Gemma via MediaPipe from ${modelFile.absolutePath} (largeModel=$isLargeModel, maxTokens=$maxTokens, numImages=$numImages)")

        // For large models (3.4GB), use CPU with XNNPACK to prevent GPU OpenCL buffer duplication
        // which causes Linux LowMemoryKiller (MMUD > 7.4GB SIGKILL).
        val preferredBackend = if (isLargeModel) LlmInference.Backend.CPU else LlmInference.Backend.GPU

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxNumImages(numImages)
                .setPreferredBackend(preferredBackend)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)
            isInitialized = true
            Log.i(tag, "MediaPipe engine ready ($preferredBackend): ${currentModel.displayName}")
            return true
        } catch (err: Throwable) {
            Log.w(tag, "Primary backend $preferredBackend failed: ${err.message}, attempting conservative CPU fallback...")
        }

        // Fallback: CPU Backend with conservative 768 tokens
        return try {
            val fallbackOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(if (isLargeModel) 768 else 1024)
                .setMaxNumImages(numImages)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, fallbackOptions)
            isInitialized = true
            Log.i(tag, "MediaPipe engine ready with conservative CPU fallback: ${currentModel.displayName}")
            true
        } catch (e: Throwable) {
            diagnosticInfo = "Stage: LiteRT-LM Engine Initialization\n" +
                    "Error: ${e.localizedMessage ?: "Failed to initialize engine"}\n" +
                    "Suggestion: Free up RAM by closing background apps before running ${currentModel.displayName}."
            Log.e(tag, "MediaPipe engine failed completely: ${e.localizedMessage}", e)
            false
        }
    }

    // ── Gemma chat template (MediaPipe / LiteRT) ──────────────────────────────

    private fun applyGemmaTemplate(q: String): String {
        val usrOpen  = "<start_of_turn>user\n"
        val usrClose = "<end_of_turn>\n"
        val mdlOpen  = "<start_of_turn>model\n"
        return "$usrOpen${q.trim()}$usrClose$mdlOpen"
    }

    private val STOP_TOKENS = listOf("<end_of_turn>", "<turn|>", "<eos>", "<start_of_turn>", "<bos>", "<pad>")

    /**
     * Detects degenerative repetition loops in generated text.
     * When LLMs lose coherence, they frequently repeat headers (e.g. multiple "####"),
     * bullet points, or identical short phrases in a runaway loop.
     */
    private fun detectRepetitionLoop(text: String): Int {
        if (text.length < 60) return -1

        // 1. Repeating markdown header lines (3+ headers with only whitespace between them)
        val headerMatch = Regex("(?:\n\\s*#{2,6}[^\n]*\\s*){3,}").find(text)
        if (headerMatch != null && headerMatch.range.first > 30) {
            return headerMatch.range.first
        }

        // 2. Line repetition: same non-empty line (>= 3 chars) appears 3+ times in the last 6 non-empty lines
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size >= 5) {
            val recent = lines.takeLast(6)
            for (line in recent) {
                if (line.length >= 3 && recent.count { it == line } >= 3) {
                    val lastIdx = text.lastIndexOf(line)
                    val firstIdx = text.indexOf(line, maxOf(0, lastIdx - 400))
                    if (firstIdx > 30) {
                        return firstIdx
                    }
                }
            }
        }

        // 3. Repeated short phrase in trailing 200 chars
        val tailLen = minOf(text.length, 200)
        val tail = text.substring(text.length - tailLen)
        for (len in 16 downTo 8) {
            if (tail.length >= len * 3) {
                val chunk = tail.substring(tail.length - len)
                val count = tail.split(chunk).size - 1
                if (count >= 3) {
                    val firstInTail = tail.indexOf(chunk)
                    val cut = text.length - tailLen + firstInTail
                    if (cut > 30) return cut
                }
            }
        }

        return -1
    }

    private fun cleanGemmaResponse(raw: String): String {
        var r = raw
        // Truncate at the very first occurrence of any stop/control token
        for (st in STOP_TOKENS) {
            val idx = r.indexOf(st)
            if (idx != -1) {
                r = r.substring(0, idx)
            }
        }
        val repCut = detectRepetitionLoop(r)
        if (repCut != -1) {
            r = r.substring(0, repCut)
        }
        STOP_TOKENS.forEach { r = r.replace(it, "") }
        return r.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ── Public inference entry-point ──────────────────────────────────────────

    suspend fun runInference(
        history: List<ChatMessage>,
        imagePath: String? = null,
        onPartialResponse: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        val hasImage = !imagePath.isNullOrBlank()
        val ready = ensureInitialized(hasImage)
        val latestPrompt = history.lastOrNull()?.content ?: ""

        if (!ready) {
            return@withContext "⚠ Model not ready: ${currentModel.displayName}\n" +
                    "File: ${getModelPath(currentModel).absolutePath}\n" +
                    "Details:\n$diagnosticInfo"
        }

        return@withContext try {
            if (currentModel.isGguf) {
                // llama.cpp path
                ggufEngine?.generate(history) ?: "GGUF engine not initialized."
            } else {
                // MediaPipe / LiteRT-LM path
                val engine = llmInference ?: return@withContext "Engine not initialized."

                if (!hasImage) {
                    val formatted = applyGemmaTemplate(latestPrompt)
                    runGemmaSessionInference(engine, formatted, null, onPartialResponse)
                } else {
                    // Multimodal path (with image)
                    val formatted = applyGemmaTemplate(latestPrompt)
                    runGemmaSessionInference(engine, formatted, imagePath, onPartialResponse)
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Inference error", e)
            "Local inference error: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Executes Gemma inference with realtime token streaming and stop-sequence cancellation.
     * Cancels generation instantly when <turn|>, <eos>, or <end_of_turn> is emitted,
     * preventing runaway token loops while streaming live tokens to UI.
     */
    private suspend fun runGemmaSessionInference(
        engine: LlmInference,
        formattedPrompt: String,
        imagePath: String?,
        onPartialResponse: ((String) -> Unit)? = null
    ): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.6f)
            .setTopK(40)
            .setTopP(0.85f)
            .setRandomSeed(Random.nextInt())
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

        return try {
            if (!imagePath.isNullOrBlank() && ModelCapabilityRegistry.supportsImage(currentModel)) {
                val paths = imagePath.split("|").filter { it.isNotBlank() }
                for (path in paths) {
                    val imgFile = File(path)
                    if (imgFile.exists() && imgFile.canRead()) {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            val mpImage = BitmapImageBuilder(bitmap).build()
                            session.addImage(mpImage)
                        }
                    }
                }
            }

            session.addQueryChunk(formattedPrompt)

            // Asynchronous token generation with early stop-token cancellation and live streaming
            val accumulated = StringBuilder()
            val deferred = CompletableDeferred<String>()

            try {
                session.generateResponseAsync { partialResult, done ->
                    if (!partialResult.isNullOrEmpty()) {
                        accumulated.append(partialResult)
                        val textSoFar = accumulated.toString()

                        for (stopToken in STOP_TOKENS) {
                            val idx = textSoFar.indexOf(stopToken)
                            if (idx != -1) {
                                // Stop token emitted! Terminate native generation immediately!
                                try { session.cancelGenerateResponseAsync() } catch (_: Throwable) {}
                                val clean = textSoFar.substring(0, idx)
                                if (!deferred.isCompleted) {
                                    deferred.complete(clean)
                                }
                                return@generateResponseAsync
                            }
                        }

                        // Check degenerative repetition loops (prevents 2+ minute stalls on repetitive #### etc.)
                        val repCut = detectRepetitionLoop(textSoFar)
                        if (repCut != -1) {
                            try { session.cancelGenerateResponseAsync() } catch (_: Throwable) {}
                            val clean = textSoFar.substring(0, repCut).trimEnd()
                            if (!deferred.isCompleted) {
                                deferred.complete(clean)
                            }
                            return@generateResponseAsync
                        }

                        // Stream clean partial text live to UI listener
                        onPartialResponse?.invoke(cleanGemmaResponse(textSoFar))
                    }
                    if (done && !deferred.isCompleted) {
                        deferred.complete(cleanGemmaResponse(accumulated.toString()))
                    }
                }

                // Generous 180s (3-minute) timeout for full long-form responses without premature cutoff
                val raw = withTimeout(180_000L) {
                    deferred.await()
                }
                cleanGemmaResponse(raw)
            } catch (asyncErr: Throwable) {
                Log.w(tag, "Async stream completed or timeout: ${asyncErr.message}, cleaning result")
                if (accumulated.isNotEmpty()) {
                    cleanGemmaResponse(accumulated.toString())
                } else {
                    // Fallback to synchronous generateResponse with strict post-truncation
                    val fallbackRaw = session.generateResponse()
                    cleanGemmaResponse(fallbackRaw)
                }
            }
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }
}
