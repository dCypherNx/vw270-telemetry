package net.jurgensen.vw270telemetry.data

import org.json.JSONArray
import org.json.JSONObject

data class TelemetryEvent(
    val source: String,
    val key: String,
    val value: Any?,
    val status: String = "success",
    val sourceTimestampMs: Long? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, Any?> = emptyMap(),
) {
    val id: String get() = "$source/$key"

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("key", key)
        put("value", jsonValue(value))
        put("status", status)
        sourceTimestampMs?.let { put("source_timestamp_ms", it) }
        put("received_at_ms", receivedAtMs)
        put("attributes", jsonValue(attributes))
    }

    companion object {
        fun jsonValue(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray, is Number, is Boolean, is String -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (k, v) -> if (k != null) put(k.toString(), jsonValue(v)) }
            }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
            is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
            is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
            is IntArray -> JSONArray().apply { value.forEach { put(it) } }
            is LongArray -> JSONArray().apply { value.forEach { put(it) } }
            else -> value.toString()
        }
    }
}
