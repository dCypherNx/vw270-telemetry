package net.jurgensen.vw270telemetry.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class VwCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Runtime.hub.emit(
            TelemetryEvent(
                "aa", "session_created", true,
                attributes = mapOf("session_info" to sessionInfo.toString())
            )
        )
        return object : Session() {
            private var collector: CarHardwareCollector? = null

            init {
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        collector?.stop()
                        collector = null
                        Runtime.hub.emit(TelemetryEvent("aa", "session_destroyed", true))
                    }
                })
            }

            override fun onCreateScreen(intent: Intent): Screen {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "aa", "screen_created", true,
                        attributes = mapOf("intent_action" to intent.action, "intent" to intent.toString())
                    )
                )
                if (collector == null) {
                    collector = CarHardwareCollector(carContext).also { it.start() }
                }
                return TelemetryScreen(carContext)
            }
        }
    }
}
