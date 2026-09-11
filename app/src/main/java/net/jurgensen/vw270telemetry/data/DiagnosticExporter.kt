package net.jurgensen.vw270telemetry.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.Runtime
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticExporter(private val context: Context) {
    data class Result(val eventCount: Int)

    fun writeZip(output: OutputStream): Result {
        var eventCount = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            putText(zip, "manifest.json", manifestJson().toString(2))
            putText(zip, "summary.json", Runtime.store.summaryJson().toString(2))
            putText(zip, "latest.json", latestJson().toString(2))
            putText(zip, "README.txt", readme())

            zip.putNextEntry(ZipEntry("events.jsonl"))
            eventCount = Runtime.store.writeJsonl(zip)
            zip.closeEntry()
        }
        return Result(eventCount)
    }

    private fun manifestJson(): JSONObject = JSONObject().apply {
        put("format", "vw270-telemetry-diagnostics-v2")
        put("created_at_ms", System.currentTimeMillis())
        put("app", appJson())
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("sdk", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE)
            put("security_patch", Build.VERSION.SECURITY_PATCH)
        })
        put("android_auto", androidAutoJson())
        put("permissions", permissionsJson())
        put("privacy", JSONObject().apply {
            put("mqtt_credentials_omitted", true)
            put("provider_credential_like_values_redacted_before_storage", true)
            put("provider_blob_contents_omitted", true)
        })
        put("settings", JSONObject().apply {
            put("auto_start", Runtime.prefs.autoStart)
            put("mqtt_enabled", Runtime.prefs.mqttEnabled)
            put("mqtt_tls", Runtime.prefs.mqttTls)
            put("mqtt_port", Runtime.prefs.mqttPort)
            put("mqtt_prefix", Runtime.prefs.mqttPrefix)
            put("publish_raw_diagnostics", Runtime.prefs.publishRawDiagnostics)
            put("shizuku_auto_probe", Runtime.prefs.shizukuAutoProbe)
            put("virtual_odometer_m", Runtime.prefs.virtualOdometerM ?: JSONObject.NULL)
        })
    }

    private fun appJson(): JSONObject {
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return JSONObject().apply {
            put("package", context.packageName)
            put("version_name", pi.versionName)
            put("version_code", pi.longVersionCode)
        }
    }

    private fun androidAutoJson(): JSONObject {
        val pkg = "com.google.android.projection.gearhead"
        return try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            JSONObject().apply {
                put("installed", true)
                put("version_name", pi.versionName)
                put("version_code", pi.longVersionCode)
            }
        } catch (_: Throwable) {
            JSONObject().put("installed", false)
        }
    }

    private fun permissionsJson(): JSONObject {
        val names = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return JSONObject().apply {
            names.forEach { permission ->
                put(permission, ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
            }
        }
    }

    private fun latestJson(): JSONArray = JSONArray().apply {
        Runtime.hub.latest().forEach { put(it.toJson()) }
    }

    private fun putText(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readme(): String = """
        VW270 Telemetry diagnostic bundle v2
        ====================================

        manifest.json  app/device/Android Auto/permission metadata and privacy flags.
        summary.json   event counts and availability/status summary.
        latest.json    latest value/status known for every telemetry key.
        events.jsonl   complete chronological event stream currently retained by the app.

        aa_provider/* contains only read-only getType/query results from selected exported Android
        Auto content providers. Credential-like values are redacted before persistence and BLOB
        contents are never stored. MQTT credentials are never exported.

        Local retention is capped by the app at approximately 250,000 events and 7 days.
        The collector sends no vehicle or Android Auto commands.
    """.trimIndent()
}
