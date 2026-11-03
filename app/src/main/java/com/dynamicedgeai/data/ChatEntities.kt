package com.dynamicedgeai.data

import java.util.UUID

data class ConversationEntity(
    val conversationId: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val selectedModel: String,
    val summary: String? = null,
    val messageCount: Int = 0
)

data class MessageEntity(
    val messageId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentPath: String? = null,
    val attachmentType: String? = null, // "image", null
    val messageStatus: String = "SENT" // "SENT", "EDITED", "ERROR"
)

data class MemoryEntity(
    val memoryId: String = UUID.randomUUID().toString(),
    val keyFact: String,
    val category: String = "PREFERENCE", // "PREFERENCE", "PROJECT", "INSTRUCTION", "FACT"
    val sourceConversationId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = true
)

