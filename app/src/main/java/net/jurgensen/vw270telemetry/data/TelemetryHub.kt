package net.jurgensen.vw270telemetry.data

import android.os.Handler
import android.os.Looper
import net.jurgensen.vw270telemetry.mqtt.MqttPublisher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class TelemetryHub(
    private val store: TelemetryStore,
    private val mqtt: MqttPublisher,
    private val prefs: AppPrefs,
) {
    private val latest = ConcurrentHashMap<String, TelemetryEvent>()
    private val listeners = CopyOnWriteArrayList<(TelemetryEvent) -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lastPublish = ConcurrentHashMap<String, Long>()

    fun emit(event: TelemetryEvent, persist: Boolean = true, mqtt: Boolean = true) {
        latest[event.id] = event
        derivePhoneEstimates(event)
        listeners.forEach { listener -> main.post { listener(event) } }
        io.execute {
            if (persist) store.insert(event)
            if (mqtt && prefs.mqttEnabled) {
                val now = System.currentTimeMillis()
                val last = lastPublish[event.id] ?: 0L
                val nominal = event.status in setOf("success", "measured", "estimated")
                if (now - last >= 200L || !nominal) {
                    lastPublish[event.id] = now
                    this.mqtt.publish(event)
                }
            }
        }
    }

    private fun derivePhoneEstimates(event: TelemetryEvent) {
        if (event.source == "fused" || event.id != "phone_location/fix") return

        @Suppress("UNCHECKED_CAST")
        val fix = event.value as? Map<String, Any?> ?: return
        val speed = (fix["speed_mps"] as? Number)?.toDouble() ?: return
        emit(
            TelemetryEvent(
                "fused",
                "speed_mps",
                speed,
                "estimated",
                event.sourceTimestampMs,
                attributes = mapOf(
                    "source" to "phone_gnss",
                    "accuracy_m" to fix["accuracy_m"],
                    "speed_accuracy_mps" to fix["speed_accuracy_mps"],
                ),
            )
        )
    }

    fun latest(): List<TelemetryEvent> = latest.values.sortedBy { it.id }
    fun get(source: String, key: String): TelemetryEvent? = latest["$source/$key"]
    fun addListener(listener: (TelemetryEvent) -> Unit) { listeners += listener }
    fun removeListener(listener: (TelemetryEvent) -> Unit) { listeners -= listener }
    fun prune() = io.execute { store.prune() }
}
