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
import net.jurgensen.vw270telemetry.collectors.ExlapVexCollector
import net.jurgensen.vw270telemetry.data.DiagnosticExporter
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class MainActivity : AppCompatActivity() {
    private lateinit var live: TextView
    private lateinit var mqttEnabled: CheckBox
    private lateinit var mqttTls: CheckBox
    private lateinit var mqttHost: EditText
    private lateinit var mqttPort: EditText
    private lateinit var mqttUser: EditText
    private lateinit var mqttPass: EditText
    private lateinit var mqttPrefix: EditText

    private val hubListener: (TelemetryEvent) -> Unit = { renderLive() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Runtime.hub.addListener(hubListener)
        renderLive()
    }

    override fun onDestroy() {
        Runtime.hub.removeListener(hubListener)
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
        root.addView(note("Telemetria somente leitura. Sem root e sem OBD; nenhum comando de atuação/configuração do veículo é implementado."))
        root.addView(note("0.1.4: prova direta do canal VAG MIB2 ExLAP com Android Auto. A sonda antiga de providers e o fallback Shizuku foram removidos."))
        root.addView(button("1. Conceder permissões do Android / ExLAP") { requestRuntimePermissions() })
        root.addView(button("2. Iniciar coletor persistente") { startCollector() })
        root.addView(button("Parar coletor") { stopCollector() })
        root.addView(button("Liberar otimização de bateria") { requestBatteryExemption() })
        root.addView(button("Localização em segundo plano (fallback GNSS)") { requestBackgroundLocation() })

        root.addView(title("ExLAP / MIB2"))
        root.addView(note("Canal alvo: ${ExlapVexCollector.VENDOR_CHANNEL}. O app negocia somente sessão, autenticação, descoberta e assinaturas necessárias para receber telemetria."))
        root.addView(note("O resultado decisivo aparece abaixo como exlap/channel, exlap/authentication, exlap/directory, exlap/schema e, se suportado pela sua central, valores exlap/* reais do veículo."))

        root.addView(title("Logs para análise"))
        root.addView(note("O ZIP registra a sequência do handshake ExLAP e os valores recebidos. Nonces e digests de autenticação são redigidos; as credenciais de protocolo não são exportadas."))
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
        root.addView(note("Valores exlap/* são publicáveis por MQTT; eventos internos de handshake ficam somente no diagnóstico local."))

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
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            ExlapVexCollector.PERMISSION_VEX,
        )
        if (Build.VERSION.SDK_INT >= 29) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 27)
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

    private fun renderLive() {
        if (!::live.isInitialized) return
        val important = Runtime.hub.latest().filter {
            it.source in setOf(
                "exlap", "aa", "fused", "usb", "bluetooth",
                "phone_location", "gnss", "system",
            )
        }.takeLast(180)
        live.text = important.joinToString("\n") { event ->
            "${event.id.padEnd(46)} ${event.status.padEnd(17)} ${short(event.value)}"
        }
    }

    private fun short(value: Any?): String = when (value) {
        null -> "—"
        else -> value.toString().replace('\n', ' ').take(180)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT_ZIP = 271
        private const val REQ_EXPORT_JSONL = 272
    }
}
