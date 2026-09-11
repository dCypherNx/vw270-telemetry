package net.jurgensen.vw270telemetry.shizuku

import android.content.Context
import android.content.pm.PackageManager
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import rikka.shizuku.Shizuku

/**
 * No-root Shizuku capability probe.
 *
 * This first public PoC deliberately does not expose a generic privileged shell executor.
 * It records whether the Shizuku binder is alive, permission state and server UID so later
 * diagnostic collectors can be added with narrowly scoped binder calls.
 */
class ShizukuProbe(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    fun state(): Map<String, Any?> {
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val permission = if (alive) {
            try { Shizuku.checkSelfPermission() } catch (_: Throwable) { PackageManager.PERMISSION_DENIED }
        } else {
            PackageManager.PERMISSION_DENIED
        }
        val uid = if (alive) try { Shizuku.getUid() } catch (_: Throwable) { -1 } else -1
        val version = if (alive) try { Shizuku.getVersion() } catch (_: Throwable) { -1 } else -1
        return mapOf(
            "alive" to alive,
            "permission" to permission,
            "server_uid" to uid,
            "server_version" to version,
        )
    }

    fun requestPermission(requestCode: Int = 270) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "shizuku", "permission_request", null, "error",
                    attributes = mapOf("error" to "${t.javaClass.simpleName}: ${t.message ?: ""}")
                )
            )
        }
    }

    fun runSnapshot(reason: String = "manual") {
        val current = state()
        Runtime.hub.emit(
            TelemetryEvent(
                "shizuku", "capability_snapshot", current,
                status = if (current["alive"] == true) "success" else "unavailable",
                attributes = mapOf("reason" to reason)
            )
        )
    }
}
