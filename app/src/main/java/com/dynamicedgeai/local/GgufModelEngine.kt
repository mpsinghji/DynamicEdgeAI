package com.dynamicedgeai.local

import android.content.ContentResolver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nehuatl.llamacpp.LlamaHelper

/**
 * On-device GGUF inference engine backed by llama.cpp via llamacpp-kotlin.
 *
 * KEY DESIGN: LlamaHelper.load() only calls the success callback on success.
 * On failure it emits LLMEvent.Error to the shared flow and never calls the
 * callback. We therefore subscribe to the event flow BEFORE calling load()
 * and race: whichever of (Loaded event / Error event / callback) fires first
 * completes the deferred.
 */
class GgufModelEngine(
    private val contentResolver: ContentResolver,
    private val model: LocalModel
) {
    private val tag = "GgufEngine[${model.displayName}]"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceMutex = Mutex()

    private val eventFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val helper: LlamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = eventFlow
        )
    }

    var isLoaded = false
        private set

    // ── Load (suspend) ────────────────────────────────────────────────────────

    /**
     * Loads the GGUF model. Suspends until loaded, an error is reported, or
     * the 60-second timeout elapses. Returns true on success.
     *
     * Subscribes to [eventFlow] FIRST so we see LLMEvent.Loaded / LLMEvent.Error
     * even if they fire before we await — which is required because the callback
     * is ONLY called on success, not on failure.
     */
    suspend fun load(modelPath: String): String {
        val result = CompletableDeferred<String>()
        Log.d(tag, "Attempting to load model from: $modelPath")

        // 1. Subscribe to events BEFORE calling load() to avoid the race.
        val monitorJob = scope.launch {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Loaded -> {
                            Log.i(tag, "LLMEvent.Loaded received for $modelPath")
                            isLoaded = true
                            if (!result.isCompleted) result.complete("OK")
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            Log.e(tag, "LLMEvent.Error during load: ${event.message}")
                            if (!result.isCompleted) result.complete(event.message)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Monitor job error", e)
            }
        }

        // 2. Call helper.load() — the native library is loaded here.
        try {
            // Using a conservative context length for large/reasoning models
            val modelName = model.displayName.uppercase()
            val safeContext = if (modelName.contains("DEEPSEEK") || 
                                 modelName.contains("QWEN") || 
                                 modelName.contains("PHI")) 512 else 1024
            
            helper.load(
                path = modelPath,
                contextLength = safeContext
            ) { contextId ->
                Log.i(tag, "Load callback successful: contextId=$contextId")
                isLoaded = true
                if (!result.isCompleted) result.complete("OK")
            }
        } catch (e: Throwable) {
            val errString = "Native load crash: ${e.message}"
            Log.e(tag, errString, e)
            monitorJob.cancel()
            return errString
        }

        // 3. Wait up to 60 seconds for the model to load from disk.
        val finalMsg = withTimeoutOrNull(60_000L) { result.await() } ?: run {
            Log.e(tag, "Model load timed out after 60 seconds")
            "Timeout waiting for model to load"
        }

        monitorJob.cancel()
        return finalMsg
    }

    // ── Release ───────────────────────────────────────────────────────────────

    fun release() {
        try { helper.abort() } catch (_: Exception) {}
        try { helper.release() } catch (_: Exception) {}
        isLoaded = false
        scope.cancel()
    }

    // ── Chat templates ────────────────────────────────────────────────────────

    private fun buildPrompt(userText: String): String {
        val modelName = model.displayName.uppercase()
        return when {
            modelName.contains("DEEPSEEK") -> deepSeekPrompt(userText)
            modelName.contains("TINYLLAMA") -> tinyLlamaPrompt(userText)
            modelName.contains("PHI") -> phiPrompt(userText)
            modelName.contains("QWEN") -> qwenPrompt(userText)
            else -> userText
        }
    }

    private fun phiPrompt(q: String): String {
        return "<s><|user|>\n${q.trim()}<|end|>\n<|assistant|>\n"
    }

    private fun qwenPrompt(q: String): String {
        return "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
               "<|im_start|>user\n${q.trim()}<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    private fun tinyLlamaPrompt(q: String): String {
        // Strict format for TinyLlama: BOS + System + User + Assistant tags
        return "<s><|system|>\nYou are a helpful assistant.</s>\n<|user|>\n${q.trim()}</s>\n<|assistant|>\n"
    }

    private fun deepSeekPrompt(q: String): String {
        // Fallback to a simpler prompt format. 
        // Some mobile ports of llama.cpp crash on complex ChatML tags if not perfectly aligned.
        return "User: ${q.trim()}\nAssistant:"
    }

    // ── Output cleaning ───────────────────────────────────────────────────────

    private fun clean(raw: String): String {
        var r = raw
        // 1. Remove reasoning blocks for DeepSeek
        r = r.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")

        // 2. Stop at the first control token or conversational turn
        val stopTokens = listOf(
            "</s>",
            "<|im_end|>",
            "<|user|>",
            "<|system|>",
            "<|assistant|>",
            "User:",
            "Assistant:"
        )

        for (tok in stopTokens) {
            val idx = r.indexOf(tok)
            if (idx != -1) {
                r = r.substring(0, idx)
            }
        }

        // 3. Final cleanup of whitespace
        return r.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(userPrompt: String): String = inferenceMutex.withLock {
        if (!isLoaded) return "Error: Model not loaded."

        // Flush any previous state
        try { helper.stopPrediction() } catch (_: Exception) {}

        val prompt = buildPrompt(userPrompt)
        Log.d(tag, "Sending prompt to native layer:\n$prompt")

        val result = CompletableDeferred<String>()
        val sb = StringBuilder()

        val collectJob = scope.launch {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Started -> {
                            sb.clear()
                            Log.d(tag, "Inference started")
                        }
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            sb.append(event.word)
                            // Truncate if the model starts hallucinating massive irrelevant text
                            if (sb.length > 2000) {
                                try { helper.stopPrediction() } catch (_: Exception) {}
                                if (!result.isCompleted) result.complete(clean(sb.toString()) + "... [Truncated]")
                            }
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            Log.d(tag, "Inference completed successfully")
                            try { helper.stopPrediction() } catch (e: Exception) { Log.e(tag, "stopPrediction failed", e) }
                            if (!result.isCompleted) result.complete(clean(sb.toString()))
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            Log.e(tag, "LLMEvent.Error during inference: ${event.message}")
                            try { helper.stopPrediction() } catch (e: Exception) { Log.e(tag, "stopPrediction failed", e) }
                            if (!result.isCompleted) result.complete("Error: ${event.message}")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Event collection exception", e)
                if (!result.isCompleted) result.complete("Error: ${e.localizedMessage}")
            }
        }

        try {
            helper.predict(prompt)
        } catch (e: Throwable) {
            Log.e(tag, "helper.predict threw exception", e)
            collectJob.cancel()
            return "Native Error: ${e.localizedMessage}"
        }

        val response = withTimeoutOrNull(180_000L) { result.await() }
            ?: "Response timed out (180s)."

        collectJob.cancel()
        return response
    }
}
