package com.dynamicedgeai.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.dynamicedgeai.util.IntegrityResult
import com.dynamicedgeai.util.ModelIntegrityChecker
import com.dynamicedgeai.util.ModelStatus
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Unified local inference runner.
 *
 * Routes by model type:
 *  - Gemma 2B (.bin)  -> MediaPipe LlmInference
 *  - TinyLlama (.gguf) / DeepSeek (.gguf) -> GgufModelEngine (llama.cpp)
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

    // MediaPipe engine — used for Gemma 2B .bin only
    private var llmInference: LlmInference? = null

    // llama.cpp engine — used for all .gguf models
    private var ggufEngine: GgufModelEngine? = null

    private var diagnosticInfo: String = "Ready"

    // ── Path resolution ───────────────────────────────────────────────────────

    fun getModelPath(model: LocalModel): File {
        val adb = File("/data/local/tmp/${model.fileName}")
        if (adb.exists()) return adb
        val ext = File(appContext.getExternalFilesDir(null), model.fileName)
        if (ext.exists()) return ext
        val int_ = File(appContext.filesDir, model.fileName)
        if (int_.exists()) return int_
        return ext   // canonical "expected" path even if absent
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
            Log.e(tag, "Model file not found: ${modelFile.absolutePath}")
            return false
        }
        if (getModelStatus(currentModel) != ModelStatus.DOWNLOADED) {
            Log.e(tag, "Model status not DOWNLOADED for ${currentModel.displayName}")
            return false
        }

        return if (currentModel.isGguf) {
            initGgufEngine(modelFile)
        } else {
            initMediaPipeEngine(modelFile)
        }
    }

    // LocalModelRunner.kt — replace initGgufEngine entirely

    private suspend fun initGgufEngine(modelFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val engine = GgufModelEngine(appContext.contentResolver, currentModel)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    modelFile
                )

                val result: String = engine.load(uri.toString()) // returns "OK" or error message

                if (result == "OK") {
                    ggufEngine = engine
                    isInitialized = true
                    Log.i(tag, "GGUF engine ready: ${currentModel.displayName}")
                    true
                } else {
                    engine.release()
                    diagnosticInfo = result  // contains the actual error string
                    Log.e(tag, "GGUF engine failed: $result")
                    false
                }
            } catch (e: Exception) {
                diagnosticInfo = e.localizedMessage ?: "Unknown error"
                Log.e(tag, "GGUF engine exception: ${e.localizedMessage}", e)
                false
            }
        }
    }

    private fun initMediaPipeEngine(modelFile: File): Boolean {
        return try {
            Log.i(tag, "Loading Gemma via MediaPipe from ${modelFile.absolutePath}")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.7f)
                .setTopK(40)
                .setRandomSeed(Random.nextInt())
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)
            isInitialized = true
            Log.i(tag, "MediaPipe engine ready: ${currentModel.displayName}")
            true
        } catch (e: Exception) {
            Log.e(tag, "MediaPipe engine failed: ${e.localizedMessage}", e)
            false
        }
    }

    // ── Gemma chat template (MediaPipe only) ──────────────────────────────────

    private fun applyGemmaTemplate(q: String): String {
        val usrOpen  = "\u003cstart_of_turn\u003euser\n"
        val usrClose = "\u003cend_of_turn\u003e\n"
        val mdlOpen  = "\u003cstart_of_turn\u003emodel\n"
        return "$usrOpen${q.trim()}$usrClose$mdlOpen"
    }

    private fun cleanGemmaResponse(raw: String): String {
        var r = raw
        listOf(
            "\u003cend_of_turn\u003e",
            "\u003cstart_of_turn\u003emodel",
            "\u003cstart_of_turn\u003euser",
            "\u003cstart_of_turn\u003e"
        ).forEach { r = r.replace(it, "") }
        r = r.replace(Regex("\n{3,}"), "\n\n").trim()
        val paras = r.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        return if (paras.size >= 3 && paras.all { it.length <= 80 }) paras.first() else r
    }

    // ── Public inference entry-point ──────────────────────────────────────────

    suspend fun runInference(history: List<com.dynamicedgeai.router.ChatMessage>): String = withContext(Dispatchers.Default) {
        val ready = ensureInitialized()
        val latestPrompt = history.lastOrNull()?.content ?: ""

        if (!ready) {
            val errorMsg = "⚠ Model not ready: ${currentModel.displayName}\n" +
                    "File: ${getModelPath(currentModel).absolutePath}\n" +
                    "Reason: $diagnosticInfo"
            return@withContext errorMsg
        }

        return@withContext try {
            if (currentModel.isGguf) {
                // llama.cpp path
                ggufEngine?.generate(history) ?: "GGUF engine not initialized."
            } else {
                // MediaPipe path (Gemma 2B)
                val formatted = applyGemmaTemplate(latestPrompt)
                val raw = llmInference?.generateResponse(formatted)
                    ?: "No response from MediaPipe engine."
                cleanGemmaResponse(raw)
            }
        } catch (e: Exception) {
            Log.e(tag, "Inference error", e)
            "Local inference error: ${e.localizedMessage}"
        }
    }
}
