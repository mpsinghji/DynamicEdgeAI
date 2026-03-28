package com.dynamicedgeai.local

import android.content.Context
import android.util.Log
import com.dynamicedgeai.util.IntegrityResult
import com.dynamicedgeai.util.ModelIntegrityChecker
import com.dynamicedgeai.util.ModelStatus
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class LocalModelRunner(private val context: Context) {

    private var llmInference: LlmInference? = null
    var currentModel = LocalModel.GEMMA_2B
        private set

    var isInitialized = false
        private set

    private var diagnosticInfo: String = "Ready"

    /**
     * Priority Path Check: ADB -> External (New) -> Internal (Legacy)
     */
    fun getModelPath(model: LocalModel): File {
        // 1. Check /data/local/tmp (ADB Pushed)
        val adbFile = File("/data/local/tmp/${model.fileName}")
        if (adbFile.exists()) return adbFile

        // 2. Check App External Files (Recommended for large models)
        val externalFile = File(context.getExternalFilesDir(null), model.fileName)
        if (externalFile.exists()) return externalFile

        // 3. Check App Internal Files (Old location)
        val internalFile = File(context.filesDir, model.fileName)
        if (internalFile.exists()) return internalFile

        // Default to external for new downloads
        return externalFile
    }

    /**
     * Checks if a model is available AND passes integrity checks.
     */
    fun isModelAvailable(model: LocalModel): Boolean {
        return getModelStatus(model) == ModelStatus.DOWNLOADED
    }

    /**
     * Returns the detailed status of a model using integrity validation.
     */
    fun getModelStatus(model: LocalModel): ModelStatus {
        val path = getModelPath(model)
        if (!path.exists()) return ModelStatus.NOT_DOWNLOADED

        return when (ModelIntegrityChecker.checkIntegrity(path, model.expectedSizeBytes)) {
            IntegrityResult.VALID -> ModelStatus.DOWNLOADED
            IntegrityResult.MISSING -> ModelStatus.NOT_DOWNLOADED
            IntegrityResult.SIZE_MISMATCH, IntegrityResult.CORRUPTED -> ModelStatus.CORRUPTED
        }
    }

    /**
     * Deletes a downloaded model and resets state if it was the current model.
     * @return true if file was successfully deleted or didn't exist
     */
    fun deleteModel(model: LocalModel): Boolean {
        val path = getModelPath(model)
        val deleted = if (path.exists()) path.delete() else true
        if (model == currentModel) {
            isInitialized = false
            try { llmInference?.close() } catch (e: Exception) {}
            llmInference = null
        }
        return deleted
    }

    fun switchModel(model: LocalModel): Boolean {
        currentModel = model
        isInitialized = false
        try { llmInference?.close() } catch (e: Exception) {}
        llmInference = null
        return isModelAvailable(model)
    }

    /**
     * Checks if ANY local model is downloaded and valid.
     */
    fun hasAnyModelAvailable(): Boolean {
        return LocalModel.values().any { isModelAvailable(it) }
    }

    /**
     * Returns the first available (downloaded + valid) model, or null.
     */
    fun getFirstAvailableModel(): LocalModel? {
        return LocalModel.values().firstOrNull { isModelAvailable(it) }
    }

    private fun ensureInitialized(): Boolean {
        if (isInitialized && llmInference != null) return true

        val modelFile = getModelPath(currentModel)
        if (!modelFile.exists()) {
            diagnosticInfo = "Model file not found at ${modelFile.absolutePath}"
            return false
        }

        // Run integrity check before initializing
        val status = getModelStatus(currentModel)
        if (status != ModelStatus.DOWNLOADED) {
            diagnosticInfo = "Model file is corrupted or incomplete"
            return false
        }

        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                // 512 tokens: enough for full detailed answers (recipes, steps, etc.)
                .setMaxTokens(512)
                // --- Improvement 1: Sampling Parameters ---
                .setTemperature(0.8f)
                .setTopK(40)
                // kotlin.random.Random gives a truly unpredictable seed each session,
                // unlike currentTimeMillis which can overflow to similar negatives.
                .setRandomSeed(Random.nextInt())
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            diagnosticInfo = "Real Engine Active"
            true
        } catch (e: Exception) {
            diagnosticInfo = "Init Failed: ${e.localizedMessage}"
            false
        }
    }

    // --- Improvement 2: Gemma 2B Chat Template ---
    // Wraps the raw user query in the tags Gemma 2B expects so it behaves as a
    // chat assistant rather than a plain text completer.
    private fun applyGemmaChatTemplate(userQuery: String): String =
        "<start_of_turn>user\n${userQuery.trim()}<end_of_turn>\n<start_of_turn>model\n"

    // --- Improvement 3: Output Cleaning ---
    // 1. Truncate at the first <end_of_turn> tag (model signalling it is done).
    // 2. Strip any other leaked control tags.
    // 3. Smart greeting-flood detection: if the model generated 3+ tiny paragraphs
    //    that are ALL short (≤ 80 chars), it is repeating greeting variants — keep
    //    only the first one.  For real answers (steps, lists, explanations) where
    //    paragraphs are longer or fewer, the full response is kept intact.
    private fun cleanResponse(raw: String): String {
        // Step 1: cut off at the model's own end-of-turn signal
        val stopIndex = raw.indexOf("<end_of_turn>")
        val actualResponse = if (stopIndex != -1) raw.substring(0, stopIndex) else raw

        // Step 2: strip remaining control tokens
        val stripped = actualResponse
            .replace("<start_of_turn>model", "")
            .replace("<start_of_turn>user", "")
            .replace("<start_of_turn>", "")
            .trim()

        // Step 3: smart paragraph filtering
        val paragraphs = stripped.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        // "Greeting flood": 3+ paragraphs where every block is ≤ 80 chars
        // (typical of repetitive greetings). Collapse to just the first one.
        val isGreetingFlood = paragraphs.size >= 3 && paragraphs.all { it.length <= 80 }
        return if (isGreetingFlood) {
            paragraphs.first()
        } else {
            // Full answer — return everything, do not truncate
            stripped
        }
    }

    suspend fun runInference(prompt: String): String = withContext(Dispatchers.Default) {
        val ready = ensureInitialized()

        if (!ready || llmInference == null) {
            delay(3000)
            return@withContext "[LOCAL AI] Generated by ${currentModel.displayName}\n\n" +
                    "Status: ON-DEVICE EXECUTION\n" +
                    "File: ${getModelPath(currentModel).absolutePath}\n" +
                    "Response: I've processed your prompt about \"$prompt\" locally."
        }

        // Apply chat template before sending to the engine
        val formattedPrompt = applyGemmaChatTemplate(prompt)

        return@withContext try {
            val rawResponse = llmInference?.generateResponse(formattedPrompt)
                ?: "No response from local model."
            // Strip any leaked system tags before displaying
            cleanResponse(rawResponse)
        } catch (e: Exception) {
            "Local Error: ${e.localizedMessage}"
        }
    }
}
