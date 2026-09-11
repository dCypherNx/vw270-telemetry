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
import net.jurgensen.vw270telemetry.data.DiagnosticExporter
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

        root.addView(title("VW270 Telemetry · ${BuildConfig.VERSION_NAME}"))
        root.addView(note("Somente leitura. Sem root, sem OBD e sem qualquer comando ao veículo."))
        root.addView(note("0.1.3: sonda focada nos providers exportados do Android Auto; redes, áudio, input genérico e Car App host foram removidos do caminho investigativo."))
        root.addView(button("1. Conceder permissões do Android") { requestRuntimePermissions() })
        root.addView(button("2. Iniciar coletor persistente") { startCollector() })
        root.addView(button("Parar coletor") { stopCollector() })
        root.addView(button("Liberar otimização de bateria") { requestBatteryExemption() })
        root.addView(button("Localização em segundo plano (zero toque)") { requestBackgroundLocation() })

        root.addView(title("Shizuku · fallback opcional"))
        shizukuState = note("")
        root.addView(shizukuState)
        root.addView(note("Não é necessário para a rodada 0.1.3. Mantido apenas como próxima camada, caso os providers públicos não sejam suficientes."))
        root.addView(button("Solicitar permissão Shizuku") { ShizukuProbe(this).requestPermission(); renderShizuku() })
        root.addView(button("Executar snapshot read-only") {
            Thread { ShizukuProbe(this).runSnapshot("manual_ui"); runOnUiThread { renderLive() } }.start()
        })

        root.addView(title("Logs para análise"))
        root.addView(note("O ZIP inclui a sequência cronológica e os resultados dos providers. Valores com aparência de credencial são redigidos antes de serem persistidos."))
        root.addView(button("Exportar pacote de diagnóstico (.zip)") { exportDiagnosticBundle() })
        root.addView(button("Exportar eventos brutos (.jsonl)") { exportRawEvents() })
        root.addView(note("Retenção local: até ~250 mil eventos e 7 dias. O arquivo é salvo pelo seletor de documentos do Android."))

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
        root.addView(note("Diagnósticos aa_provider/probe/usb permanecem somente no SQLite local por padrão."))

        root.addView(title("Estado ao vivo"))
        live = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(live)
        return ScrollView(this).apply { addView(root) }
    }

    private fun exportDiagnosticBundle() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "vw270-diagnostics-${System.currentTimeMillis()}.zip")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT_ZIP)
    }

    private fun exportRawEvents() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "vw270-events-${System.currentTimeMillis()}.jsonl")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT_JSONL)
    }

    @Deprecated("Deprecated in Android API; retained to keep this PoC dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_EXPORT_ZIP -> exportZipTo(uri)
            REQ_EXPORT_JSONL -> exportJsonlTo(uri)
        }
    }

    private fun exportZipTo(uri: Uri) {
        Thread {
            try {
                val result = contentResolver.openOutputStream(uri)?.use { output ->
                    DiagnosticExporter(this).writeZip(output)
                } ?: error("Unable to open destination")
                Runtime.hub.emit(
                    TelemetryEvent(
                        "system", "diagnostic_export", result.eventCount, "success",
                        attributes = mapOf("format" to "zip", "uri" to uri.toString())
                    )
                )
            } catch (t: Throwable) {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "system", "diagnostic_export", null, "error",
                        attributes = mapOf("format" to "zip", "error" to t.message)
                    )
                )
            }
        }.start()
    }

    private fun exportJsonlTo(uri: Uri) {
        Thread {
            try {
                val count = contentResolver.openOutputStream(uri)?.use { output ->
                    Runtime.store.writeJsonl(output)
                } ?: error("Unable to open destination")
                Runtime.hub.emit(
                    TelemetryEvent(
                        "system", "diagnostic_export", count, "success",
                        attributes = mapOf("format" to "jsonl", "uri" to uri.toString())
                    )
                )
            } catch (t: Throwable) {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "system", "diagnostic_export", null, "error",
                        attributes = mapOf("format" to "jsonl", "error" to t.message)
                    )
                )
            }
        }.start()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(
            permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray(),
            27,
        )
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
        val state = ShizukuProbe(this).state()
        shizukuState.text = "alive=${state["alive"]}  permission=${state["permission"]}  uid=${state["server_uid"]}"
    }

    private fun renderLive() {
        if (!::live.isInitialized) return
        val important = Runtime.hub.latest().filter {
            it.source in setOf(
                "aa", "aa_provider", "fused", "probe", "usb", "bluetooth", "shizuku",
                "phone_location", "gnss", "system",
            )
        }.takeLast(120)
        live.text = important.joinToString("\n") { event ->
            "${event.id.padEnd(38)} ${event.status.padEnd(17)} ${short(event.value)}"
        }
    }

    private fun short(value: Any?): String = when (value) {
        null -> "—"
        else -> value.toString().replace('\n', ' ').take(160)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT_ZIP = 271
        private const val REQ_EXPORT_JSONL = 272
    }
}
