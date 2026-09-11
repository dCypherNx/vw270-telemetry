package net.jurgensen.vw270telemetry.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

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

    @Synchronized
    fun writeJsonl(output: OutputStream): Int {
        var count = 0
        val newline = byteArrayOf('\n'.code.toByte())
        readableDatabase.rawQuery(
            "SELECT payload FROM event ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                output.write(c.getString(0).toByteArray(Charsets.UTF_8))
                output.write(newline)
                count++
            }
        }
        output.flush()
        return count
    }

    @Synchronized
    fun summaryJson(): JSONObject {
        val summary = JSONObject()
        var total = 0L
        var firstTs: Long? = null
        var lastTs: Long? = null
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(received_ts), MAX(received_ts) FROM event",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getLong(0)
                if (!c.isNull(1)) firstTs = c.getLong(1)
                if (!c.isNull(2)) lastTs = c.getLong(2)
            }
        }
        summary.put("event_count", total)
        summary.put("first_received_ts", firstTs ?: JSONObject.NULL)
        summary.put("last_received_ts", lastTs ?: JSONObject.NULL)

        val bySourceStatus = JSONArray()
        readableDatabase.rawQuery(
            "SELECT source, status, COUNT(*) FROM event GROUP BY source, status ORDER BY source, status",
            null
        ).use { c ->
            while (c.moveToNext()) {
                bySourceStatus.put(JSONObject().apply {
                    put("source", c.getString(0))
                    put("status", c.getString(1))
                    put("count", c.getLong(2))
                })
            }
        }
        summary.put("by_source_status", bySourceStatus)

        val byKey = JSONArray()
        readableDatabase.rawQuery(
            "SELECT source, key_name, status, COUNT(*), MAX(received_ts) FROM event GROUP BY source, key_name, status ORDER BY source, key_name, status",
            null
        ).use { c ->
            while (c.moveToNext()) {
                byKey.put(JSONObject().apply {
                    put("source", c.getString(0))
                    put("key", c.getString(1))
                    put("status", c.getString(2))
                    put("count", c.getLong(3))
                    put("last_received_ts", c.getLong(4))
                })
            }
        }
        summary.put("by_key_status", byKey)
        return summary
    }
}
