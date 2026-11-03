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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private suspend fun ensureInitialized(): Boolean {
        if (isInitialized) return true

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
            initMediaPipeEngine(modelFile)
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

    private fun initMediaPipeEngine(modelFile: File): Boolean {
        if (!modelFile.exists() || modelFile.length() < 1000L) {
            diagnosticInfo = "Stage: Model File Check\nError: File is missing or incomplete (${modelFile.length()} bytes).\nSuggestion: Re-download the model from the Model Manager."
            return false
        }

        return try {
            Log.i(tag, "Loading Gemma via MediaPipe/LiteRT 0.10.35 from ${modelFile.absolutePath}")
            
            // First attempt: GPU Backend with multimodal configuration
            try {
                val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(2048)
                    .setMaxNumImages(if (currentModel.isLiteRtLm) 1 else 0)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()
                llmInference = LlmInference.createFromOptions(appContext, gpuOptions)
                isInitialized = true
                Log.i(tag, "MediaPipe/LiteRT engine ready with GPU backend: ${currentModel.displayName}")
                return true
            } catch (gpuError: Throwable) {
                Log.w(tag, "GPU initialization failed, falling back to CPU: ${gpuError.message}")
            }

            // Fallback: CPU Backend
            val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .setMaxNumImages(if (currentModel.isLiteRtLm) 1 else 0)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, cpuOptions)
            isInitialized = true
            Log.i(tag, "MediaPipe/LiteRT engine ready with CPU fallback: ${currentModel.displayName}")
            true
        } catch (e: Throwable) {
            diagnosticInfo = "Stage: LiteRT-LM Engine Initialization\n" +
                    "Error: ${e.localizedMessage ?: "Failed to initialize engine"}\n" +
                    "Suggestion: Verify model file matches ${currentModel.fileSizeLabel} and that phone has at least 4GB free RAM."
            Log.e(tag, "MediaPipe engine failed: ${e.localizedMessage}", e)
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

    private fun cleanGemmaResponse(raw: String): String {
        var r = raw
        listOf(
            "<end_of_turn>",
            "<start_of_turn>model",
            "<start_of_turn>user",
            "<start_of_turn>"
        ).forEach { r = r.replace(it, "") }
        r = r.replace(Regex("\n{3,}"), "\n\n").trim()
        val paras = r.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        return if (paras.size >= 3 && paras.all { it.length <= 80 }) paras.first() else r
    }

    // ── Public inference entry-point ──────────────────────────────────────────

    suspend fun runInference(
        history: List<ChatMessage>,
        imagePath: String? = null
    ): String = withContext(Dispatchers.Default) {
        val ready = ensureInitialized()
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
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.7f)
                    .setTopK(40)
                    .setRandomSeed(Random.nextInt())
                    .build()

                val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
                try {
                    // If image is provided and model is multimodal (Gemma 4)
                    if (!imagePath.isNullOrBlank() && currentModel.isLiteRtLm) {
                        val imgFile = File(imagePath)
                        if (imgFile.exists() && imgFile.canRead()) {
                            val bitmap = BitmapFactory.decodeFile(imagePath)
                            if (bitmap != null) {
                                val mpImage = BitmapImageBuilder(bitmap).build()
                                session.addImage(mpImage)
                            }
                        }
                    }

                    val formatted = applyGemmaTemplate(latestPrompt)
                    session.addQueryChunk(formatted)
                    val raw = session.generateResponse()
                    cleanGemmaResponse(raw)
                } finally {
                    session.close()
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Inference error", e)
            "Local inference error: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }
}
