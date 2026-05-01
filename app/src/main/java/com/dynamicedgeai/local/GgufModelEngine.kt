package com.dynamicedgeai.local

import android.content.ContentResolver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nehuatl.llamacpp.LlamaHelper

/**
 * On-device GGUF inference engine backed by llama.cpp via llamacpp-kotlin.
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

    suspend fun load(modelPath: String): String {
        val result = CompletableDeferred<String>()
        Log.d(tag, "Attempting to load model from: $modelPath")

        val monitorJob = scope.launch {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Loaded -> {
                            Log.i(tag, "LLMEvent.Loaded received")
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

        try {
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

        val finalMsg = withTimeoutOrNull(60_000L) { result.await() } ?: run {
            Log.e(tag, "Model load timed out")
            "Timeout waiting for model to load"
        }

        monitorJob.cancel()
        return finalMsg
    }

    // ── Release ───────────────────────────────────────────────────────────────

    fun release() {
        try {
            // Correct teardown order for llamacpp-kotlin
            helper.stopPrediction()
            helper.abort()
            helper.release()
        } catch (e: Throwable) {
            Log.e(tag, "Error during release: ${e.message}")
        } finally {
            isLoaded = false
            scope.cancel()
        }
    }

    // ── Chat templates ────────────────────────────────────────────────────────

    private fun buildPrompt(history: List<com.dynamicedgeai.router.ChatMessage>): String {
        val modelName = model.displayName.uppercase()
        return when {
            modelName.contains("DEEPSEEK") -> deepSeekPrompt(history)
            modelName.contains("TINYLLAMA") -> tinyLlamaPrompt(history)
            modelName.contains("PHI") -> phiPrompt(history)
            modelName.contains("QWEN") -> qwenPrompt(history)
            else -> history.lastOrNull()?.content ?: ""
        }
    }

    private fun phiPrompt(history: List<com.dynamicedgeai.router.ChatMessage>): String {
        val sb = StringBuilder("<s>")
        history.forEach { msg ->
            if (msg.role == "user") {
                sb.append("<|user|>\n${msg.content}<|end|>\n")
            } else {
                sb.append("<|assistant|>\n${msg.content}<|end|>\n")
            }
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun qwenPrompt(history: List<com.dynamicedgeai.router.ChatMessage>): String {
        val sb = StringBuilder("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n")
        history.forEach { msg ->
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun tinyLlamaPrompt(history: List<com.dynamicedgeai.router.ChatMessage>): String {
        val sb = StringBuilder("<s><|system|>\nYou are a helpful assistant.</s>\n")
        history.forEach { msg ->
            sb.append("<|${msg.role}|>\n${msg.content}</s>\n")
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun deepSeekPrompt(history: List<com.dynamicedgeai.router.ChatMessage>): String {
        val sb = StringBuilder()
        history.forEach { msg ->
            val role = if (msg.role == "user") "User" else "Assistant"
            sb.append("$role: ${msg.content}\n")
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    // ── Output cleaning ───────────────────────────────────────────────────────

    private fun clean(raw: String): String {
        var r = raw
        r = r.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        
        val stopTokens = listOf("</s>", "<|im_end|>", "<|user|>", "<|system|>", "<|assistant|>", "User:", "Assistant:", "<|end|>")
        for (tok in stopTokens) {
            val idx = r.indexOf(tok)
            if (idx != -1) r = r.substring(0, idx)
        }

        val cleaned = r.trim()
        return if (cleaned.isEmpty() && raw.isNotBlank()) {
            raw.take(300).trim() // Don't return blank if there's text
        } else if (cleaned.isEmpty()) {
            "..."
        } else {
            cleaned
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(history: List<com.dynamicedgeai.router.ChatMessage>): String = inferenceMutex.withLock {
        if (!isLoaded) return "Error: Model not loaded."

        try { helper.stopPrediction() } catch (_: Exception) {}

        val prompt = buildPrompt(history)
        val result = CompletableDeferred<String>()
        val sb = StringBuilder()

        val collectJob = scope.launch {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            sb.append(event.word)
                            if (sb.length > 2000) {
                                try { helper.stopPrediction() } catch (_: Exception) {}
                                if (!result.isCompleted) result.complete(clean(sb.toString()) + "... [Truncated]")
                            }
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            if (!result.isCompleted) result.complete(clean(sb.toString()))
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            if (!result.isCompleted) result.complete("Error: ${event.message}")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                if (!result.isCompleted) result.complete("Error: ${e.localizedMessage}")
            }
        }

        try {
            helper.predict(prompt)
        } catch (e: Throwable) {
            collectJob.cancel()
            return "Native Error: ${e.localizedMessage}"
        }

        val response = withTimeoutOrNull(180_000L) { result.await() } ?: "Timeout."
        collectJob.cancel()
        return response
    }
}
