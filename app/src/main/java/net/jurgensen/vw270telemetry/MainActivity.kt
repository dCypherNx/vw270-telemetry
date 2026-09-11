package net.jurgensen.vw270telemetry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import net.jurgensen.vw270telemetry.shizuku.ShizukuProbe
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var live: TextView
    private lateinit var mqttEnabled: CheckBox
    private lateinit var mqttTls: CheckBox
    private lateinit var mqttHost: EditText
    private lateinit var mqttPort: EditText
    private lateinit var mqttUser: EditText
    private lateinit var mqttPass: EditText
    private lateinit var mqttPrefix: EditText
    private lateinit var shizukuState: TextView

    private val hubListener: (TelemetryEvent) -> Unit = { renderLive() }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { renderShizuku() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setContentView(buildUi())
        Runtime.hub.addListener(hubListener)
        renderLive()
        renderShizuku()
    }

    override fun onDestroy() {
        Runtime.hub.removeListener(hubListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        fun title(s: String) = TextView(this).apply { text = s; textSize = 21f; setPadding(0, dp(12), 0, dp(6)) }
        fun note(s: String) = TextView(this).apply { text = s; textSize = 13f; setPadding(0, 0, 0, dp(8)) }
        fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; setOnClickListener { action() } }
        fun input(hint: String, value: String = "") = EditText(this).apply { this.hint = hint; setText(value) }

        root.addView(title("VW270 Telemetry · PoC 0.1"))
        root.addView(note("Somente leitura. Sem root, sem OBD e sem qualquer comando ao veículo."))
        root.addView(button("1. Conceder permissões do Android") { requestRuntimePermissions() })
        root.addView(button("2. Iniciar coletor persistente") { startCollector() })
        root.addView(button("Parar coletor") { stopCollector() })
        root.addView(button("Liberar otimização de bateria") { requestBatteryExemption() })
        root.addView(button("Localização em segundo plano (zero toque)") { requestBackgroundLocation() })
        root.addView(button("Acesso às notificações (sonda AA)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Acesso de uso (sonda AA)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        root.addView(title("Shizuku · camada opcional sem root"))
        shizukuState = note("")
        root.addView(shizukuState)
        root.addView(button("Solicitar permissão Shizuku") { ShizukuProbe(this).requestPermission(); renderShizuku() })
        root.addView(button("Executar snapshot read-only") {
            Thread { ShizukuProbe(this).runSnapshot("manual_ui"); runOnUiThread { renderLive() } }.start()
        })
        root.addView(button("Exportar até 50 mil eventos (.jsonl)") { exportDiagnostics() })

        root.addView(title("MQTT / Home Assistant"))
        mqttEnabled = CheckBox(this).apply { text = "Publicar via MQTT"; isChecked = Runtime.prefs.mqttEnabled }
        mqttTls = CheckBox(this).apply { text = "TLS"; isChecked = Runtime.prefs.mqttTls }
        mqttHost = input("Broker (IP ou hostname)", Runtime.prefs.mqttHost)
        mqttPort = input("Porta", Runtime.prefs.mqttPort.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        mqttUser = input("Usuário", Runtime.prefs.mqttUser)
        mqttPass = input("Senha", Runtime.prefs.mqttPassword).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        mqttPrefix = input("Prefixo", Runtime.prefs.mqttPrefix)
        listOf(mqttEnabled, mqttTls, mqttHost, mqttPort, mqttUser, mqttPass, mqttPrefix).forEach(root::addView)
        root.addView(button("Salvar MQTT") { saveMqtt() })
        root.addView(note("Dumpsys/logcat brutos permanecem apenas no SQLite local por padrão."))

        root.addView(title("Estado ao vivo"))
        live = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(live)
        return ScrollView(this).apply { addView(root) }
    }

    private fun exportDiagnostics() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "vw270-telemetry-${System.currentTimeMillis()}.jsonl")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Android API; retained to keep this PoC dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                try {
                    val lines = Runtime.store.recent(50_000).asReversed()
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        lines.forEach { w.appendLine(it) }
                    }
                    Runtime.hub.emit(TelemetryEvent("system", "export", lines.size))
                } catch (t: Throwable) {
                    Runtime.hub.emit(TelemetryEvent("system", "export", null, "error", attributes = mapOf("error" to t.message)))
                }
            }.start()
        }
    }

    private fun requestRuntimePermissions() {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            "com.google.android.gms.permission.CAR_FUEL",
        )
        if (Build.VERSION.SDK_INT >= 29) p += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray(), 27)
    }

    private fun startCollector() {
        Runtime.prefs.autoStart = true
        ContextCompat.startForegroundService(this, Intent(this, TelemetryService::class.java))
    }

    private fun stopCollector() {
        Runtime.prefs.autoStart = false
        stopService(Intent(this, TelemetryService::class.java))
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 29)
        } else if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ deliberately requires the user to choose "Allow all the time" in app settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun saveMqtt() {
        Runtime.prefs.mqttEnabled = mqttEnabled.isChecked
        Runtime.prefs.mqttTls = mqttTls.isChecked
        Runtime.prefs.mqttHost = mqttHost.text.toString()
        Runtime.prefs.mqttPort = mqttPort.text.toString().toIntOrNull() ?: if (mqttTls.isChecked) 8883 else 1883
        Runtime.prefs.mqttUser = mqttUser.text.toString()
        Runtime.prefs.mqttPassword = mqttPass.text.toString()
        Runtime.prefs.mqttPrefix = mqttPrefix.text.toString().ifBlank { "car/vw270" }
        Runtime.mqtt.close()
        Runtime.hub.emit(TelemetryEvent("config", "mqtt_saved", true), mqtt = false)
        renderLive()
    }

    private fun renderShizuku() {
        val s = ShizukuProbe(this).state()
        shizukuState.text = "alive=${s["alive"]}  permission=${s["permission"]}  uid=${s["server_uid"]}"
    }

    private fun renderLive() {
        if (!::live.isInitialized) return
        val important = Runtime.hub.latest().filter {
            it.source in setOf("aa", "car", "fused", "shizuku", "phone_location", "gnss", "system", "bluetooth")
        }.takeLast(100)
        live.text = important.joinToString("\n") { e ->
            "${e.id.padEnd(34)} ${e.status.padEnd(13)} ${short(e.value)}"
        }
    }

    private fun short(v: Any?): String = when (v) {
        null -> "—"
        else -> v.toString().replace('\n', ' ').take(140)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val REQ_EXPORT = 271 }
}
