package net.jurgensen.vw270telemetry.mqtt

import net.jurgensen.vw270telemetry.data.AppPrefs
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import javax.net.ssl.SSLSocketFactory

class MqttPublisher(private val prefs: AppPrefs) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var connectedConfig = ""
    private val discoveryPublished = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun publish(event: TelemetryEvent) {
        if (!prefs.mqttEnabled || prefs.mqttHost.isBlank()) return
        val rawDiagnostic = event.source == "shizuku_raw"
        if (rawDiagnostic && !prefs.publishRawDiagnostics) return

        try {
            ensureConnected()
            val prefix = prefs.mqttPrefix.ifBlank { "car/vw270" }.trim('/')
            val stateTopic = "$prefix/${event.source}/${event.key}"
            sendPublish(stateTopic, event.toJson().toString(), retain = true)
            sendPublish("$prefix/event", event.toJson().toString(), retain = false)
            publishDiscoveryIfUseful(prefix, stateTopic, event)
        } catch (_: Throwable) {
            close()
            try {
                ensureConnected()
                val prefix = prefs.mqttPrefix.ifBlank { "car/vw270" }.trim('/')
                val stateTopic = "$prefix/${event.source}/${event.key}"
                sendPublish(stateTopic, event.toJson().toString(), retain = true)
                publishDiscoveryIfUseful(prefix, stateTopic, event)
            } catch (_: Throwable) {
                close()
            }
        }
    }

    @Synchronized
    fun close() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        output = null
        connectedConfig = ""
        discoveryPublished.clear()
    }

    private fun ensureConnected() {
        val cfg = "${prefs.mqttHost}:${prefs.mqttPort}:${prefs.mqttTls}:${prefs.mqttUser}"
        if (socket?.isConnected == true && socket?.isClosed == false && cfg == connectedConfig) return
        close()

        val s = if (prefs.mqttTls) {
            SSLSocketFactory.getDefault().createSocket(prefs.mqttHost, prefs.mqttPort) as Socket
        } else {
            Socket(prefs.mqttHost, prefs.mqttPort)
        }
        s.soTimeout = 6000
        s.tcpNoDelay = true
        socket = s
        output = s.getOutputStream()

        val vh = ByteArrayOutputStream().apply {
            writeUtf("MQTT")
            write(4)
            var flags = 0x02 or 0x04 or 0x20
            if (prefs.mqttUser.isNotBlank()) flags = flags or 0x80
            if (prefs.mqttUser.isNotBlank() && prefs.mqttPassword.isNotBlank()) flags = flags or 0x40
            write(flags)
            write(0)
            write(30)
        }.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            writeUtf("vw270-${android.os.Build.MODEL.replace(" ", "-")}-${android.os.Process.myPid()}")
            val prefix = prefs.mqttPrefix.ifBlank { "car/vw270" }.trim('/')
            writeUtf("$prefix/availability")
            writeUtf("offline")
            if (prefs.mqttUser.isNotBlank()) writeUtf(prefs.mqttUser)
            if (prefs.mqttUser.isNotBlank() && prefs.mqttPassword.isNotBlank()) writeUtf(prefs.mqttPassword)
        }.toByteArray()
        writePacket(0x10, vh + payload)

        val input = DataInputStream(s.getInputStream())
        if (input.readUnsignedByte() != 0x20 || input.readUnsignedByte() != 0x02) error("Bad MQTT CONNACK")
        input.readUnsignedByte()
        val rc = input.readUnsignedByte()
        if (rc != 0) error("MQTT refused connection: $rc")
        connectedConfig = cfg
        val prefix = prefs.mqttPrefix.ifBlank { "car/vw270" }.trim('/')
        sendPublish("$prefix/availability", "online", retain = true)
    }

    private fun publishDiscoveryIfUseful(prefix: String, stateTopic: String, event: TelemetryEvent) {
        if (event.value !is Number && event.value !is String && event.value !is Boolean) return
        if (event.source !in setOf("fused", "aa", "system", "gnss")) return
        val objectId = sanitize("vw270_${event.source}_${event.key}")
        if (!discoveryPublished.add(objectId)) return
        val config = JSONObject().apply {
            put("name", "VW270 ${event.source} ${event.key}".replace('_', ' '))
            put("unique_id", objectId)
            put("state_topic", stateTopic)
            put("availability_topic", "$prefix/availability")
            put("value_template", "{{ value_json.value }}")
            put("json_attributes_topic", stateTopic)
            put("device", JSONObject().apply {
                put("identifiers", org.json.JSONArray().put("vw270_telemetry"))
                put("name", "VW270 Telemetry")
                put("manufacturer", "VW / Android Auto probe")
                put("model", "VW270")
            })
            SENSOR_META[event.key]?.forEach { (k, v) -> put(k, v) }
        }
        sendPublish("homeassistant/sensor/$objectId/config", config.toString(), retain = true)
    }

    private fun sanitize(s: String) = s.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

    private fun sendPublish(topic: String, text: String, retain: Boolean) {
        val body = ByteArrayOutputStream().apply {
            writeUtf(topic)
            write(text.toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()
        writePacket(if (retain) 0x31 else 0x30, body)
    }

    private fun writePacket(header: Int, body: ByteArray) {
        val out = output ?: error("MQTT not connected")
        out.write(header)
        var x = body.size
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        out.write(body)
        out.flush()
    }

    private fun ByteArrayOutputStream.writeUtf(s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        require(b.size <= 65535)
        write((b.size shr 8) and 0xff)
        write(b.size and 0xff)
        write(b)
    }

    companion object {
        private val SENSOR_META = mapOf(
            "speed_mps" to mapOf(
                "unit_of_measurement" to "m/s",
                "device_class" to "speed",
                "state_class" to "measurement",
            ),
        )
    }
}
