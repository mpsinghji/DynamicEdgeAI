package com.dynamicedgeai.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MemoryManager(context: Context) {

    private val dbHelper = ChatDbHelper.getInstance(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("MemoryPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_MEMORY_ENABLED = "pref_memory_enabled"
    }

    var isMemoryEnabled: Boolean
        get() = prefs.getBoolean(PREF_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(PREF_MEMORY_ENABLED, value).apply()

    suspend fun saveMemory(
        keyFact: String,
        category: String = "PREFERENCE",
        sourceConversationId: String? = null
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val memory = MemoryEntity(
            memoryId = UUID.randomUUID().toString(),
            keyFact = keyFact.trim(),
            category = category,
            sourceConversationId = sourceConversationId,
            timestamp = System.currentTimeMillis(),
            isEnabled = true
        )
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_MEM_ID, memory.memoryId)
            put(ChatDbHelper.COL_MEM_KEY_FACT, memory.keyFact)
            put(ChatDbHelper.COL_MEM_CATEGORY, memory.category)
            put(ChatDbHelper.COL_MEM_SOURCE_CONV_ID, memory.sourceConversationId)
            put(ChatDbHelper.COL_MEM_TIMESTAMP, memory.timestamp)
            put(ChatDbHelper.COL_MEM_IS_ENABLED, if (memory.isEnabled) 1 else 0)
        }
        db.insert(ChatDbHelper.TABLE_MEMORIES, null, values)
        memory
    }

    suspend fun getEnabledMemories(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (!isMemoryEnabled) return@withContext emptyList()
        val list = mutableListOf<MemoryEntity>()
        val db = dbHelper.readableDatabase
        val cursor: Cursor = db.query(
            ChatDbHelper.TABLE_MEMORIES,
            null,
            "${ChatDbHelper.COL_MEM_IS_ENABLED} = 1",
            null,
            null,
            null,
            "${ChatDbHelper.COL_MEM_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToMemory(it))
            }
        }
        list
    }

    suspend fun getAllMemories(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MemoryEntity>()
        val db = dbHelper.readableDatabase
        val cursor: Cursor = db.query(
            ChatDbHelper.TABLE_MEMORIES,
            null,
            null,
            null,
            null,
            null,
            "${ChatDbHelper.COL_MEM_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToMemory(it))
            }
        }
        list
    }

    suspend fun deleteMemory(memoryId: String): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(ChatDbHelper.TABLE_MEMORIES, "${ChatDbHelper.COL_MEM_ID} = ?", arrayOf(memoryId)) > 0
    }

    suspend fun toggleMemory(memoryId: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_MEM_IS_ENABLED, if (isEnabled) 1 else 0)
        }
        db.update(ChatDbHelper.TABLE_MEMORIES, values, "${ChatDbHelper.COL_MEM_ID} = ?", arrayOf(memoryId))
    }

    /**
     * Inspects a user message for explicit persistent facts/preferences to store in long-term memory.
     * Prevents storing sensitive credentials/passwords.
     */
    suspend fun inspectAndExtractMemories(userMessage: String, conversationId: String) {
        if (!isMemoryEnabled) return
        val lower = userMessage.lowercase().trim()

        // Privacy safety filter: never store secrets, passwords, tokens, pins
        val sensitivePatterns = listOf("password", "secret", "token", "api_key", "apikey", "credential", "credit card", "pin ")
        if (sensitivePatterns.any { lower.contains(it) }) return

        val preferenceTriggers = listOf(
            "i prefer", "my preference is", "i like", "always answer in", "always respond in",
            "my name is", "call me", "i am working on", "my project is", "remember that", "remember:"
        )

        for (trigger in preferenceTriggers) {
            val idx = lower.indexOf(trigger)
            if (idx != -1) {
                val candidate = userMessage.substring(idx).trim().take(150)
                if (candidate.length > 8) {
                    // Check for existing duplicates
                    val existing = getAllMemories()
                    if (existing.none { it.keyFact.equals(candidate, ignoreCase = true) }) {
                        val category = when {
                            lower.contains("prefer") || lower.contains("like") -> "PREFERENCE"
                            lower.contains("project") || lower.contains("working on") -> "PROJECT"
                            lower.contains("always") || lower.contains("call me") -> "INSTRUCTION"
                            else -> "FACT"
                        }
                        saveMemory(candidate, category, conversationId)
                    }
                }
            }
        }
    }

    private fun cursorToMemory(c: Cursor): MemoryEntity {
        return MemoryEntity(
            memoryId = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_ID)),
            keyFact = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_KEY_FACT)),
            category = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_CATEGORY)),
            sourceConversationId = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_SOURCE_CONV_ID)),
            timestamp = c.getLong(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_TIMESTAMP)),
            isEnabled = c.getInt(c.getColumnIndexOrThrow(ChatDbHelper.COL_MEM_IS_ENABLED)) == 1
        )
    }
}

