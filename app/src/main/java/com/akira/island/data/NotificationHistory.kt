package com.akira.island.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A searchable list of the notifications the island showed, kept in a database on this phone only (when switched on).
 * Calls block the caller briefly: use it off the main thread.
 */
object NotificationHistory {
    data class Entry(val id: Long, val time: Long, val pkg: String, val app: String, val title: String, val text: String)

    /** Older rows are dropped, so the list can't grow without bound. */
    const val KEEP = 1000

    private class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                "app TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var db: Db? = null
    private fun helper(context: Context): Db = db ?: synchronized(this) { db ?: Db(context).also { db = it } }

    fun add(context: Context, pkg: String, app: String, title: String, text: String, time: Long = System.currentTimeMillis()) {
        runCatching {
            val d = helper(context).writableDatabase
            d.insert("history", null, ContentValues().apply {
                put("time", time); put("pkg", pkg); put("app", app); put("title", title); put("text", text)
            })
            d.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT $KEEP)")
        }
    }

    /** Newest first; [query] matches the title, text or app name, case-insensitively. */
    fun search(context: Context, query: String, limit: Int = 200): List<Entry> = runCatching {
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Entry>()
        helper(context).readableDatabase.rawQuery(
            "SELECT id, time, pkg, app, title, text FROM history WHERE title LIKE ?1 ESCAPE '\\' OR text LIKE ?1 ESCAPE '\\' " +
                "OR app LIKE ?1 ESCAPE '\\' ORDER BY id DESC LIMIT $limit", arrayOf(like),
        ).use { c ->
            while (c.moveToNext()) out += Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
        }
        out
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { helper(context).writableDatabase.delete("history", null, null) }
    }
}
