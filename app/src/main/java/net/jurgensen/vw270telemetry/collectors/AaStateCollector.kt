package net.jurgensen.vw270telemetry.collectors

import android.app.UiModeManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import net.jurgensen.vw270telemetry.shizuku.ShizukuProbe

class AaStateCollector(private val context: Context, private val onConnectionChanged: (Int) -> Unit = {}) {
    private val carConnection = CarConnection(context)
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val uiMode = context.getSystemService(UiModeManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastConnectionType: Int? = null

    private val observer = Observer<Int> { type ->
        lastConnectionType = type
        val name = when (type) {
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "not_connected"
            CarConnection.CONNECTION_TYPE_NATIVE -> "native"
            CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
            else -> "unknown_$type"
        }
        Runtime.hub.emit(TelemetryEvent("aa", "connection_type", name, attributes = mapOf("raw" to type)))
        onConnectionChanged(type)
        if (type == CarConnection.CONNECTION_TYPE_PROJECTION && Runtime.prefs.shizukuAutoProbe) {
            Thread { ShizukuProbe(context).runSnapshot("aa_projection_connected") }.start()
        }
    }

    private val usagePoll = object : Runnable {
        override fun run() {
            pollUsageEvents()
            Runtime.hub.emit(
                TelemetryEvent(
                    "aa", "environment",
                    mapOf(
                        "car_connection_type" to lastConnectionType,
                        "ui_mode_type" to uiMode.currentModeType,
                        "ui_mode" to uiMode.nightMode,
                    )
                )
            )
            handler.postDelayed(this, 5000L)
        }
    }

    fun start() {
        carConnection.type.observeForever(observer)
        handler.post(usagePoll)
    }

    fun stop() {
        carConnection.type.removeObserver(observer)
        handler.removeCallbacks(usagePoll)
    }

    private fun pollUsageEvents() {
        val end = System.currentTimeMillis()
        val events = try { usage.queryEvents(end - 15_000L, end) } catch (_: Throwable) { null } ?: return
        val e = UsageEvents.Event()
        var matched = 0
        var lastType: Int? = null
        var lastTs: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == AA_PACKAGE) {
                matched++
                lastType = e.eventType
                lastTs = e.timeStamp
            }
        }
        if (matched > 0) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "aa", "usage_event",
                    lastType,
                    sourceTimestampMs = lastTs,
                    attributes = mapOf("events_in_window" to matched)
                )
            )
        }
    }

    companion object { const val AA_PACKAGE = "com.google.android.projection.gearhead" }
}
