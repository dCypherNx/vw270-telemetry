package net.jurgensen.vw270telemetry.data

import android.content.Context

class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("vw270", Context.MODE_PRIVATE)

    var autoStart: Boolean
        get() = p.getBoolean("auto_start", true)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    var mqttEnabled: Boolean
        get() = p.getBoolean("mqtt_enabled", false)
        set(v) = p.edit().putBoolean("mqtt_enabled", v).apply()

    var mqttHost: String
        get() = p.getString("mqtt_host", "") ?: ""
        set(v) = p.edit().putString("mqtt_host", v.trim()).apply()

    var mqttPort: Int
        get() = p.getInt("mqtt_port", 1883)
        set(v) = p.edit().putInt("mqtt_port", v).apply()

    var mqttTls: Boolean
        get() = p.getBoolean("mqtt_tls", false)
        set(v) = p.edit().putBoolean("mqtt_tls", v).apply()

    var mqttUser: String
        get() = p.getString("mqtt_user", "") ?: ""
        set(v) = p.edit().putString("mqtt_user", v).apply()

    var mqttPassword: String
        get() = p.getString("mqtt_password", "") ?: ""
        set(v) = p.edit().putString("mqtt_password", v).apply()

    var mqttPrefix: String
        get() = p.getString("mqtt_prefix", "car/vw270") ?: "car/vw270"
        set(v) = p.edit().putString("mqtt_prefix", v.trim().trim('/')).apply()

    var publishRawDiagnostics: Boolean
        get() = p.getBoolean("mqtt_raw_diag", false)
        set(v) = p.edit().putBoolean("mqtt_raw_diag", v).apply()
}
