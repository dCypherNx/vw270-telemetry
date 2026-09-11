package net.jurgensen.vw270telemetry.collectors

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

/**
 * Minimal Android Auto connection detector.
 *
 * UsageStats and notification-listener probing were removed after the 0.1.2 real-car capture:
 * neither exposed vehicle telemetry, while CarConnection reliably reported projection state.
 */
class AaStateCollector(
    context: Context,
    private val onConnectionChanged: (Int) -> Unit = {},
) {
    private val carConnection = CarConnection(context)

    private val observer = Observer<Int> { type ->
        val name = when (type) {
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "not_connected"
            CarConnection.CONNECTION_TYPE_NATIVE -> "native"
            CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
            else -> "unknown_$type"
        }
        Runtime.hub.emit(
            TelemetryEvent(
                "aa",
                "connection_type",
                name,
                attributes = mapOf("raw" to type),
            )
        )
        onConnectionChanged(type)
    }

    fun start() {
        carConnection.type.observeForever(observer)
    }

    fun stop() {
        carConnection.type.removeObserver(observer)
    }

    companion object {
        const val AA_PACKAGE = "com.google.android.projection.gearhead"
    }
}
