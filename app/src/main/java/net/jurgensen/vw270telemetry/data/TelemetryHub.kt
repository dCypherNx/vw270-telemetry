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
    @Volatile private var lastCarSpeedSuccessMs = 0L
    @Volatile private var lastCarOdometerSuccessMs = 0L
    @Volatile private var lastPhoneTripM: Double? = null

    fun emit(event: TelemetryEvent, persist: Boolean = true, mqtt: Boolean = true) {
        latest[event.id] = event
        deriveFallbacks(event)
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

    private fun deriveFallbacks(event: TelemetryEvent) {
        if (event.source == "fused") return

        when (event.id) {
            "car/speed_raw_mps" -> {
                val speed = (event.value as? Number)?.toDouble()
                if (event.status == "success" && speed != null) {
                    lastCarSpeedSuccessMs = event.receivedAtMs
                    emit(
                        TelemetryEvent(
                            "fused", "speed_mps", speed, "measured", event.sourceTimestampMs,
                            attributes = mapOf("source" to "android_auto_car_raw")
                        )
                    )
                }
            }
            "car/speed_display_mps" -> {
                val speed = (event.value as? Number)?.toDouble()
                if (event.status == "success" && speed != null &&
                    event.receivedAtMs - lastCarSpeedSuccessMs > CAR_VALUE_STALE_MS
                ) {
                    lastCarSpeedSuccessMs = event.receivedAtMs
                    emit(
                        TelemetryEvent(
                            "fused", "speed_mps", speed, "measured", event.sourceTimestampMs,
                            attributes = mapOf("source" to "android_auto_car_display")
                        )
                    )
                }
            }
            "car/odometer_m" -> {
                val odometer = (event.value as? Number)?.toDouble()
                if (event.status == "success" && odometer != null) {
                    lastCarOdometerSuccessMs = event.receivedAtMs
                    prefs.virtualOdometerM = odometer
                    lastPhoneTripM = currentPhoneTripMeters()
                    emit(
                        TelemetryEvent(
                            "fused", "odometer_m", odometer, "measured", event.sourceTimestampMs,
                            attributes = mapOf("source" to "android_auto_car")
                        )
                    )
                }
            }
            "phone_location/fix" -> onPhoneLocation(event)
        }
    }

    private fun onPhoneLocation(event: TelemetryEvent) {
        @Suppress("UNCHECKED_CAST")
        val fix = event.value as? Map<String, Any?> ?: return
        val trip = (fix["trip_m"] as? Number)?.toDouble()
        if (trip != null) {
            val previousTrip = lastPhoneTripM
            if (previousTrip != null) {
                val delta = trip - previousTrip
                val base = prefs.virtualOdometerM
                if (base != null && delta in 0.0..1000.0) {
                    prefs.virtualOdometerM = base + delta
                }
            }
            lastPhoneTripM = trip
        }

        val now = event.receivedAtMs
        val gnssSpeed = (fix["speed_mps"] as? Number)?.toDouble()
        if (gnssSpeed != null && now - lastCarSpeedSuccessMs > CAR_VALUE_STALE_MS) {
            emit(
                TelemetryEvent(
                    "fused", "speed_mps", gnssSpeed, "estimated", event.sourceTimestampMs,
                    attributes = mapOf(
                        "source" to "phone_gnss",
                        "accuracy_m" to fix["accuracy_m"],
                        "speed_accuracy_mps" to fix["speed_accuracy_mps"],
                    )
                )
            )
        }

        val virtualOdometer = prefs.virtualOdometerM
        if (virtualOdometer != null && now - lastCarOdometerSuccessMs > CAR_VALUE_STALE_MS) {
            emit(
                TelemetryEvent(
                    "fused", "odometer_m", virtualOdometer, "estimated", event.sourceTimestampMs,
                    attributes = mapOf(
                        "source" to "last_car_odometer_plus_gnss",
                        "gnss_trip_m" to trip,
                        "accuracy_m" to fix["accuracy_m"],
                    )
                )
            )
        }
    }

    private fun currentPhoneTripMeters(): Double? {
        @Suppress("UNCHECKED_CAST")
        val fix = latest["phone_location/fix"]?.value as? Map<String, Any?> ?: return null
        return (fix["trip_m"] as? Number)?.toDouble()
    }

    fun latest(): List<TelemetryEvent> = latest.values.sortedBy { it.id }
    fun get(source: String, key: String): TelemetryEvent? = latest["$source/$key"]
    fun addListener(listener: (TelemetryEvent) -> Unit) { listeners += listener }
    fun removeListener(listener: (TelemetryEvent) -> Unit) { listeners -= listener }
    fun prune() = io.execute { store.prune() }

    companion object {
        private const val CAR_VALUE_STALE_MS = 2_500L
    }
}
