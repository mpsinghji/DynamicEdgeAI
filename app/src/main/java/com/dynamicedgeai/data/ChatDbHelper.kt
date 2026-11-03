package com.dynamicedgeai.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatDbHelper private constructor(context: Context) : 
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "dynamicedgeai_chat.db"
        const val DATABASE_VERSION = 1

        const val TABLE_CONVERSATIONS = "conversations"
        const val COL_CONV_ID = "conversation_id"
        const val COL_CONV_TITLE = "title"
        const val COL_CONV_CREATED_AT = "created_at"
        const val COL_CONV_UPDATED_AT = "updated_at"
        const val COL_CONV_SELECTED_MODEL = "selected_model"
        const val COL_CONV_SUMMARY = "summary"
        const val COL_CONV_MESSAGE_COUNT = "message_count"

        const val TABLE_MESSAGES = "messages"
        const val COL_MSG_ID = "message_id"
        const val COL_MSG_CONV_ID = "conversation_id"
        const val COL_MSG_ROLE = "role"
        const val COL_MSG_CONTENT = "content"
        const val COL_MSG_TIMESTAMP = "timestamp"
        const val COL_MSG_ATTACHMENT_PATH = "attachment_path"
        const val COL_MSG_ATTACHMENT_TYPE = "attachment_type"
        const val COL_MSG_STATUS = "message_status"

        const val TABLE_MEMORIES = "memories"
        const val COL_MEM_ID = "memory_id"
        const val COL_MEM_KEY_FACT = "key_fact"
        const val COL_MEM_CATEGORY = "category"
        const val COL_MEM_SOURCE_CONV_ID = "source_conversation_id"
        const val COL_MEM_TIMESTAMP = "timestamp"
        const val COL_MEM_IS_ENABLED = "is_enabled"

        @Volatile
        private var instance: ChatDbHelper? = null

        fun getInstance(context: Context): ChatDbHelper {
            return instance ?: synchronized(this) {
                instance ?: ChatDbHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                $COL_CONV_ID TEXT PRIMARY KEY,
                $COL_CONV_TITLE TEXT NOT NULL,
                $COL_CONV_CREATED_AT INTEGER NOT NULL,
                $COL_CONV_UPDATED_AT INTEGER NOT NULL,
                $COL_CONV_SELECTED_MODEL TEXT NOT NULL,
                $COL_CONV_SUMMARY TEXT,
                $COL_CONV_MESSAGE_COUNT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_conv_updated_at 
            ON $TABLE_CONVERSATIONS($COL_CONV_UPDATED_AT DESC)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_CONV_ID TEXT NOT NULL,
                $COL_MSG_ROLE TEXT NOT NULL,
                $COL_MSG_CONTENT TEXT NOT NULL,
                $COL_MSG_TIMESTAMP INTEGER NOT NULL,
                $COL_MSG_ATTACHMENT_PATH TEXT,
                $COL_MSG_ATTACHMENT_TYPE TEXT,
                $COL_MSG_STATUS TEXT NOT NULL DEFAULT 'SENT',
                FOREIGN KEY($COL_MSG_CONV_ID) REFERENCES $TABLE_CONVERSATIONS($COL_CONV_ID) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_msg_conv_time 
            ON $TABLE_MESSAGES($COL_MSG_CONV_ID, $COL_MSG_TIMESTAMP ASC)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                $COL_MEM_ID TEXT PRIMARY KEY,
                $COL_MEM_KEY_FACT TEXT NOT NULL,
                $COL_MEM_CATEGORY TEXT NOT NULL,
                $COL_MEM_SOURCE_CONV_ID TEXT,
                $COL_MEM_TIMESTAMP INTEGER NOT NULL,
                $COL_MEM_IS_ENABLED INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_mem_enabled 
            ON $TABLE_MEMORIES($COL_MEM_IS_ENABLED)
        """.trimIndent())
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe incremental migrations when updating database version
    }
}

