package com.dynamicedgeai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ConversationRepository(context: Context) {

    private val dbHelper = ChatDbHelper.getInstance(context)

    suspend fun createConversation(
        title: String = "New Chat",
        selectedModel: String = "GEMMA_2B"
    ): ConversationEntity = withContext(Dispatchers.IO) {
        val conv = ConversationEntity(
            conversationId = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            selectedModel = selectedModel,
            summary = null,
            messageCount = 0
        )
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_CONV_ID, conv.conversationId)
            put(ChatDbHelper.COL_CONV_TITLE, conv.title)
            put(ChatDbHelper.COL_CONV_CREATED_AT, conv.createdAt)
            put(ChatDbHelper.COL_CONV_UPDATED_AT, conv.updatedAt)
            put(ChatDbHelper.COL_CONV_SELECTED_MODEL, conv.selectedModel)
            put(ChatDbHelper.COL_CONV_SUMMARY, conv.summary)
            put(ChatDbHelper.COL_CONV_MESSAGE_COUNT, conv.messageCount)
        }
        db.insert(ChatDbHelper.TABLE_CONVERSATIONS, null, values)
        conv
    }

    suspend fun getAllConversations(): List<ConversationEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ConversationEntity>()
        val db = dbHelper.readableDatabase
        val cursor: Cursor = db.query(
            ChatDbHelper.TABLE_CONVERSATIONS,
            null,
            null,
            null,
            null,
            null,
            "${ChatDbHelper.COL_CONV_UPDATED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToConversation(it))
            }
        }
        list
    }

    suspend fun getConversation(id: String): ConversationEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ChatDbHelper.TABLE_CONVERSATIONS,
            null,
            "${ChatDbHelper.COL_CONV_ID} = ?",
            arrayOf(id),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) cursorToConversation(it) else null
        }
    }

    suspend fun updateConversationTitle(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_CONV_TITLE, newTitle)
            put(ChatDbHelper.COL_CONV_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(ChatDbHelper.TABLE_CONVERSATIONS, values, "${ChatDbHelper.COL_CONV_ID} = ?", arrayOf(id))
    }

    suspend fun updateConversationSummary(id: String, summary: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_CONV_SUMMARY, summary)
            put(ChatDbHelper.COL_CONV_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(ChatDbHelper.TABLE_CONVERSATIONS, values, "${ChatDbHelper.COL_CONV_ID} = ?", arrayOf(id))
    }

    suspend fun deleteConversation(id: String): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        // Also delete associated messages
        db.delete(ChatDbHelper.TABLE_MESSAGES, "${ChatDbHelper.COL_MSG_CONV_ID} = ?", arrayOf(id))
        val count = db.delete(ChatDbHelper.TABLE_CONVERSATIONS, "${ChatDbHelper.COL_CONV_ID} = ?", arrayOf(id))
        count > 0
    }

    suspend fun addMessage(msg: MessageEntity): MessageEntity = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_MSG_ID, msg.messageId)
            put(ChatDbHelper.COL_MSG_CONV_ID, msg.conversationId)
            put(ChatDbHelper.COL_MSG_ROLE, msg.role)
            put(ChatDbHelper.COL_MSG_CONTENT, msg.content)
            put(ChatDbHelper.COL_MSG_TIMESTAMP, msg.timestamp)
            put(ChatDbHelper.COL_MSG_ATTACHMENT_PATH, msg.attachmentPath)
            put(ChatDbHelper.COL_MSG_ATTACHMENT_TYPE, msg.attachmentType)
            put(ChatDbHelper.COL_MSG_STATUS, msg.messageStatus)
        }
        db.insert(ChatDbHelper.TABLE_MESSAGES, null, values)

        // Increment conversation messageCount & touch updatedAt
        db.execSQL("""
            UPDATE ${ChatDbHelper.TABLE_CONVERSATIONS} 
            SET ${ChatDbHelper.COL_CONV_MESSAGE_COUNT} = ${ChatDbHelper.COL_CONV_MESSAGE_COUNT} + 1,
                ${ChatDbHelper.COL_CONV_UPDATED_AT} = ?
            WHERE ${ChatDbHelper.COL_CONV_ID} = ?
        """.trimIndent(), arrayOf<Any>(System.currentTimeMillis(), msg.conversationId))

        msg
    }

    suspend fun getMessages(conversationId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MessageEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ChatDbHelper.TABLE_MESSAGES,
            null,
            "${ChatDbHelper.COL_MSG_CONV_ID} = ?",
            arrayOf(conversationId),
            null,
            null,
            "${ChatDbHelper.COL_MSG_TIMESTAMP} ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToMessage(it))
            }
        }
        list
    }

    suspend fun getLastMessage(conversationId: String): MessageEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ChatDbHelper.TABLE_MESSAGES,
            null,
            "${ChatDbHelper.COL_MSG_CONV_ID} = ?",
            arrayOf(conversationId),
            null,
            null,
            "${ChatDbHelper.COL_MSG_TIMESTAMP} DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) cursorToMessage(it) else null
        }
    }

    suspend fun updateMessageContent(messageId: String, newContent: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_MSG_CONTENT, newContent)
            put(ChatDbHelper.COL_MSG_STATUS, "EDITED")
        }
        db.update(ChatDbHelper.TABLE_MESSAGES, values, "${ChatDbHelper.COL_MSG_ID} = ?", arrayOf(messageId))
    }

    suspend fun deleteMessagesAfter(conversationId: String, timestamp: Long): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val deleted = db.delete(
            ChatDbHelper.TABLE_MESSAGES,
            "${ChatDbHelper.COL_MSG_CONV_ID} = ? AND ${ChatDbHelper.COL_MSG_TIMESTAMP} > ?",
            arrayOf(conversationId, timestamp.toString())
        )
        // Recalculate messageCount
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${ChatDbHelper.TABLE_MESSAGES} WHERE ${ChatDbHelper.COL_MSG_CONV_ID} = ?",
            arrayOf(conversationId)
        )
        var count = 0
        cursor.use { if (it.moveToFirst()) count = it.getInt(0) }
        val values = ContentValues().apply {
            put(ChatDbHelper.COL_CONV_MESSAGE_COUNT, count)
            put(ChatDbHelper.COL_CONV_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(ChatDbHelper.TABLE_CONVERSATIONS, values, "${ChatDbHelper.COL_CONV_ID} = ?", arrayOf(conversationId))
        deleted
    }

    private fun cursorToConversation(c: Cursor): ConversationEntity {
        return ConversationEntity(
            conversationId = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_ID)),
            title = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_TITLE)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_CREATED_AT)),
            updatedAt = c.getLong(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_UPDATED_AT)),
            selectedModel = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_SELECTED_MODEL)),
            summary = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_SUMMARY)),
            messageCount = c.getInt(c.getColumnIndexOrThrow(ChatDbHelper.COL_CONV_MESSAGE_COUNT))
        )
    }

    private fun cursorToMessage(c: Cursor): MessageEntity {
        return MessageEntity(
            messageId = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_ID)),
            conversationId = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_CONV_ID)),
            role = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_ROLE)),
            content = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_CONTENT)),
            timestamp = c.getLong(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_TIMESTAMP)),
            attachmentPath = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_ATTACHMENT_PATH)),
            attachmentType = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_ATTACHMENT_TYPE)),
            messageStatus = c.getString(c.getColumnIndexOrThrow(ChatDbHelper.COL_MSG_STATUS))
        )
    }
}
