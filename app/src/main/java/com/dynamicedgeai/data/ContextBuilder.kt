package com.dynamicedgeai.data

import com.dynamicedgeai.router.ChatMessage

class ContextBuilder(
    private val memoryManager: MemoryManager
) {
    companion object {
        const val MAX_RECENT_MESSAGES = 10
    }

    suspend fun buildInferenceHistory(
        recentMessages: List<MessageEntity>,
        summary: String? = null
    ): List<ChatMessage> {
        val history = mutableListOf<ChatMessage>()

        // 1. Long-term memory injection (if enabled and present)
        val memories = memoryManager.getEnabledMemories()
        if (memories.isNotEmpty()) {
            val memoryPrompt = buildString {
                appendLine("[System Context & Long-Term Memory]")
                memories.take(5).forEach {
                    appendLine("• ${it.keyFact}")
                }
            }.trim()
            history.add(ChatMessage("system", memoryPrompt))
        }

        // 2. Conversation summary injection (if present)
        if (!summary.isNullOrBlank()) {
            history.add(ChatMessage("system", "[Prior Conversation Summary]: $summary"))
        }

        // 3. Recent conversation messages
        val turnsToInclude = recentMessages.takeLast(MAX_RECENT_MESSAGES)
        turnsToInclude.forEach { msg ->
            history.add(ChatMessage(msg.role, msg.content))
        }

        return history
    }
}

