package net.jurgensen.vw270telemetry.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.TelemetryService
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Runtime.prefs.autoStart) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, TelemetryService::class.java))
        } catch (t: Throwable) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "system", "boot_autostart", null, "error",
                    attributes = mapOf("action" to intent.action, "error" to "${t.javaClass.simpleName}: ${t.message}")
                )
            )
        }
    }
}
