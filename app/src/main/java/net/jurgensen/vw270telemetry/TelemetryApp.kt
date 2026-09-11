package net.jurgensen.vw270telemetry

import android.app.Application
import net.jurgensen.vw270telemetry.data.AppPrefs
import net.jurgensen.vw270telemetry.data.TelemetryHub
import net.jurgensen.vw270telemetry.data.TelemetryStore
import net.jurgensen.vw270telemetry.mqtt.MqttPublisher

class TelemetryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Runtime.app = this
        Runtime.prefs = AppPrefs(this)
        Runtime.store = TelemetryStore(this)
        Runtime.mqtt = MqttPublisher(Runtime.prefs)
        Runtime.hub = TelemetryHub(Runtime.store, Runtime.mqtt, Runtime.prefs)
        Runtime.hub.prune()
    }
}

object Runtime {
    lateinit var app: TelemetryApp
    lateinit var prefs: AppPrefs
    lateinit var store: TelemetryStore
    lateinit var mqtt: MqttPublisher
    lateinit var hub: TelemetryHub
}
