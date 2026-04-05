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

        // 1. Subscribe to events BEFORE calling load() to avoid the race.
        val monitorJob = scope.launch {
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
        }

        // 2. Call helper.load() — the native library is loaded here.
        //    Any synchronous exception means the native .so couldn't load.
        try {
            helper.load(
                path = modelPath,
                contextLength = 2048
            ) { contextId ->
                Log.i(tag, "Load callback: contextId=$contextId")
                isLoaded = true
                if (!result.isCompleted) result.complete("OK")
            }
        } catch (e: Throwable) {
            val errString = "${e.javaClass.name}: ${e.message}"
            Log.e(tag, "helper.load() threw synchronously: $errString", e)
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

    private fun buildPrompt(userText: String): String = when (model) {
        LocalModel.DEEPSEEK_R1 -> deepSeekPrompt(userText)
        LocalModel.TINY_LLAMA  -> tinyLlamaPrompt(userText)
        else                    -> userText
    }

    private fun tinyLlamaPrompt(q: String): String {
        val sysOpen = "\u003c|system|\u003e\n"
        val eos     = "\u003c/s\u003e\n"
        val usrOpen = "\u003c|user|\u003e\n"
        val astOpen = "\u003c|assistant|\u003e\n"
        return "${sysOpen}You are a helpful, concise assistant.${eos}${usrOpen}${q.trim()}${eos}${astOpen}"
    }

    private fun deepSeekPrompt(q: String): String {
        val start = "\u003c|im_start|\u003e"
        val end   = "\u003c|im_end|\u003e\n"
        return "${start}system\nYou are a helpful AI assistant.${end}" +
               "${start}user\n${q.trim()}${end}" +
               "${start}assistant\n"
    }

    // ── Output cleaning ───────────────────────────────────────────────────────

    private fun clean(raw: String): String {
        var r = raw
        r = r.replace(Regex("\u003cthink\u003e.*?\u003c/think\u003e", RegexOption.DOT_MATCHES_ALL), "")
        listOf(
            "\u003c/s\u003e",
            "\u003c|im_end|\u003e",
            "\u003c|user|\u003e",
            "\u003c|system|\u003e"
        ).forEach { tok ->
            val idx = r.indexOf(tok)
            if (idx != -1) r = r.substring(0, idx)
        }
        r = r.replace(Regex("\n{3,}"), "\n\n").trim()
        val paras = r.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        return if (paras.size >= 3 && paras.all { it.length <= 80 }) paras.first() else r
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(userPrompt: String): String {
        val prompt = buildPrompt(userPrompt)
        val result = CompletableDeferred<String>()
        val sb = StringBuilder()

        val collectJob = scope.launch {
            eventFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Started -> sb.clear()
                    is LlamaHelper.LLMEvent.Ongoing -> sb.append(event.word)
                    is LlamaHelper.LLMEvent.Done -> {
                        helper.stopPrediction()
                        if (!result.isCompleted) result.complete(clean(sb.toString()))
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        helper.stopPrediction()
                        if (!result.isCompleted) result.complete("Error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }

        helper.predict(prompt)

        val response = withTimeoutOrNull(120_000L) { result.await() }
            ?: "Response timed out."
        collectJob.cancel()
        return response
    }
}
