package com.example.hellofly

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SessionDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSION (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                login TEXT NOT NULL,
                auth_header TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSION")
        onCreate(db)
    }

    fun saveSession(login: String, authHeader: String) {
        val values = ContentValues().apply {
            put(COLUMN_ID, SINGLE_ROW_ID)
            put(COLUMN_LOGIN, login)
            put(COLUMN_AUTH_HEADER, authHeader)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_SESSION, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSession(): StoredSession? {
        val query = "SELECT $COLUMN_LOGIN, $COLUMN_AUTH_HEADER, $COLUMN_CREATED_AT FROM $TABLE_SESSION WHERE $COLUMN_ID = ? LIMIT 1"
        val args = arrayOf(SINGLE_ROW_ID.toString())

        readableDatabase.rawQuery(query, args).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }

            val login = cursor.getString(0)
            val authHeader = cursor.getString(1)
            val createdAt = cursor.getLong(2)
            return StoredSession(login = login, authHeader = authHeader, createdAt = createdAt)
        }
    }

    fun clearSession() {
        writableDatabase.delete(TABLE_SESSION, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "litec_session.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SESSION = "session"
        private const val COLUMN_ID = "id"
        private const val COLUMN_LOGIN = "login"
        private const val COLUMN_AUTH_HEADER = "auth_header"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val SINGLE_ROW_ID = 1
    }
}

data class StoredSession(
    val login: String,
    val authHeader: String,
    val createdAt: Long
)
