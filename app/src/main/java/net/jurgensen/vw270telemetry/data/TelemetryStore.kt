package net.jurgensen.vw270telemetry.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TelemetryStore(context: Context) : SQLiteOpenHelper(context, "telemetry.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE event(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                key_name TEXT NOT NULL,
                status TEXT NOT NULL,
                source_ts INTEGER,
                received_ts INTEGER NOT NULL,
                payload TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX ix_event_time ON event(received_ts)")
        db.execSQL("CREATE INDEX ix_event_key ON event(source,key_name,received_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(event: TelemetryEvent) {
        writableDatabase.insert("event", null, ContentValues().apply {
            put("source", event.source)
            put("key_name", event.key)
            put("status", event.status)
            event.sourceTimestampMs?.let { put("source_ts", it) }
            put("received_ts", event.receivedAtMs)
            put("payload", event.toJson().toString())
        })
    }

    @Synchronized
    fun prune(maxRows: Int = 250_000, maxAgeDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        writableDatabase.delete("event", "received_ts < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL(
            "DELETE FROM event WHERE id < (SELECT COALESCE(MAX(id)-?, 0) FROM event)",
            arrayOf(maxRows)
        )
    }

    fun recent(limit: Int = 200): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT payload FROM event ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 50_000).toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }
}
