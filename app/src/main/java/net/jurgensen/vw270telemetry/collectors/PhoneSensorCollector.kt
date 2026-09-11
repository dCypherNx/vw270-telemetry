package net.jurgensen.vw270telemetry.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import java.util.concurrent.ConcurrentHashMap

class PhoneSensorCollector(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val registered = ConcurrentHashMap.newKeySet<Sensor>()
    private val lastEmittedNs = ConcurrentHashMap<Int, Long>()

    fun start() {
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        Runtime.hub.emit(
            TelemetryEvent(
                "phone", "sensor_inventory",
                sensors.map { sensorInfo(it) },
                attributes = mapOf("count" to sensors.size)
            )
        )
        sensors.forEach { sensor ->
            try {
                val ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (ok) registered += sensor
                Runtime.hub.emit(
                    TelemetryEvent(
                        "phone_probe", sensorKey(sensor), ok,
                        if (ok) "success" else "unavailable",
                        attributes = sensorInfo(sensor)
                    )
                )
            } catch (t: Throwable) {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "phone_probe", sensorKey(sensor), null, "error",
                        attributes = sensorInfo(sensor) + ("error" to t.compact())
                    )
                )
            }
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        registered.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = event.sensor
        val minIntervalNs = if (s.type in HIGH_RATE_TYPES) 100_000_000L else 1_000_000_000L
        val last = lastEmittedNs[s.id] ?: Long.MIN_VALUE
        if (event.timestamp - last < minIntervalNs) return
        lastEmittedNs[s.id] = event.timestamp
        Runtime.hub.emit(
            TelemetryEvent(
                source = "phone_sensor",
                key = sensorKey(s),
                value = event.values.toList(),
                sourceTimestampMs = event.timestamp / 1_000_000L,
                attributes = mapOf(
                    "type" to s.type,
                    "string_type" to s.stringType,
                    "accuracy" to event.accuracy,
                    "name" to s.name,
                    "vendor" to s.vendor,
                    "wake_up" to s.isWakeUpSensor,
                    "timestamp_clock" to "elapsed_realtime",
                    "registered_delay" to "FASTEST",
                    "persisted_max_hz" to if (s.type in HIGH_RATE_TYPES) 10 else 1,
                )
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Runtime.hub.emit(
            TelemetryEvent("phone_sensor", "${sensorKey(sensor)}_accuracy", accuracy)
        )
    }

    private fun sensorInfo(s: Sensor): Map<String, Any?> = mapOf(
        "name" to s.name,
        "vendor" to s.vendor,
        "version" to s.version,
        "type" to s.type,
        "string_type" to s.stringType,
        "max_range" to s.maximumRange,
        "resolution" to s.resolution,
        "power_ma" to s.power,
        "min_delay_us" to s.minDelay,
        "max_delay_us" to s.maxDelay,
        "fifo_max" to s.fifoMaxEventCount,
        "fifo_reserved" to s.fifoReservedEventCount,
        "wake_up" to s.isWakeUpSensor,
        "reporting_mode" to s.reportingMode,
        "id" to s.id,
    )

    private fun sensorKey(s: Sensor): String =
        "${s.type}_${s.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    private fun Throwable.compact() = "${javaClass.simpleName}: ${message ?: ""}".take(500)

    companion object {
        private val HIGH_RATE_TYPES = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        )
    }
}
