package net.jurgensen.vw270telemetry.collectors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.android.apps.auto.sdk.service.CarVendorExtensionManagerLoader
import com.google.android.apps.auto.sdk.service.vec.CarVendorExtensionManager
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only telemetry probe for the Volkswagen MIB2 ExLAP Android Auto vendor channel.
 *
 * The only outbound traffic is the ExLAP session/authentication/discovery/subscription handshake
 * required to receive telemetry. No vehicle actuator or configuration commands are implemented.
 */
class ExlapVexCollector(private val context: Context) {
    private enum class State {
        STOPPED,
        CONNECTING,
        WAIT_INIT,
        WAIT_CAPABILITIES,
        WAIT_AUTH_CHALLENGE,
        WAIT_AUTH_RESPONSE,
        WAIT_URL_LIST,
        WAIT_INTERFACES,
        ACTIVE,
        FAILED,
    }

    private data class Credential(val user: String, val password: String)
    private data class Schema(
        val rawUrl: String,
        val member: String,
        val type: String,
        val unit: String?,
        val description: String?,
    )

    private val thread = HandlerThread("VW270-ExLAP")
    private lateinit var handler: Handler
    private var car: Car? = null
    private var manager: CarVendorExtensionManager? = null
    private var state = State.STOPPED
    private var sessionId = ""
    private var nextRequestId = 42
    private var credentialIndex = 0
    private var urls = emptyList<String>()
    private var interfaceResponsesRemaining = 0
    private val schema = linkedMapOf<String, Schema>()
    private val random = SecureRandom()
    private val documentFactory = DocumentBuilderFactory.newInstance().apply {
        isIgnoringComments = false
        isIgnoringElementContentWhitespace = true
        isNamespaceAware = false
        isValidating = false
    }

    private val carCallback = object : CarConnectionCallback() {
        override fun onConnected(connectedCar: Car) {
            if (connectedCar !== car) return
            handler.post { openVendorChannel(connectedCar) }
        }

        override fun onDisconnected(disconnectedCar: Car) {
            if (disconnectedCar !== car) return
            handler.post {
                runCatching { manager?.release() }
                manager = null
                transition(State.STOPPED, "android_auto_disconnected")
                Runtime.hub.emit(
                    TelemetryEvent("exlap", "channel", false, "disconnected"),
                    mqtt = false,
                )
                handler.postDelayed({ connectCar() }, CAR_RETRY_MS)
            }
        }
    }

    private val vexListener = object : CarVendorExtensionManager.CarVendorExtensionListener {
        override fun onData(extensionManager: CarVendorExtensionManager, bytes: ByteArray) {
            val copy = bytes.copyOf()
            handler.post { processIncoming(String(copy, StandardCharsets.UTF_8)) }
        }
    }

    fun start() {
        if (state != State.STOPPED || car != null) return
        if (ContextCompat.checkSelfPermission(context, PERMISSION_VEX) != PackageManager.PERMISSION_GRANTED) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "exlap",
                    "permission",
                    false,
                    "permission_denied",
                    attributes = mapOf("permission" to PERMISSION_VEX),
                ),
                mqtt = false,
            )
            return
        }

        thread.start()
        handler = Handler(thread.looper)
        Runtime.hub.emit(TelemetryEvent("exlap", "permission", true), mqtt = false)
        car = Car.createCar(context, carCallback, handler)
        handler.post { connectCar() }
    }

    fun stop() {
        if (!::handler.isInitialized) return
        handler.post {
            state = State.STOPPED
            runCatching { manager?.release() }
            manager = null
            runCatching { if (car?.isConnected == true) car?.disconnect() }
            car = null
            thread.quitSafely()
        }
    }

    private fun connectCar() {
        val c = car ?: return
        if (runCatching { c.isConnected }.getOrDefault(false)) return
        Runtime.hub.emit(TelemetryEvent("exlap", "car_api", "connecting"), mqtt = false)
        runCatching { c.connect() }
            .onFailure { emitError("car_api", it) }
    }

    private fun openVendorChannel(connectedCar: Car) {
        try {
            val loader = connectedCar.getCarManager(
                CarVendorExtensionManagerLoader.VENDOR_EXTENSION_LOADER_SERVICE
            ) as CarVendorExtensionManagerLoader
            val vex = loader.getManager(VENDOR_CHANNEL)
            if (vex == null) {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "exlap",
                        "channel",
                        false,
                        "unavailable",
                        attributes = mapOf("vendor_channel" to VENDOR_CHANNEL),
                    ),
                    mqtt = false,
                )
                transition(State.FAILED, "vendor_channel_not_advertised")
                return
            }
            manager = vex
            vex.registerListener(vexListener)
            Runtime.hub.emit(
                TelemetryEvent(
                    "exlap",
                    "channel",
                    true,
                    "success",
                    attributes = mapOf("vendor_channel" to VENDOR_CHANNEL),
                ),
                mqtt = false,
            )
            credentialIndex = 0
            beginSession("vendor_channel_open")
        } catch (t: Throwable) {
            emitError("channel", t)
            transition(State.FAILED, "vendor_channel_open_failed")
        }
    }

    private fun beginSession(reason: String) {
        schema.clear()
        urls = emptyList()
        interfaceResponsesRemaining = 0
        nextRequestId = 42
        sessionId = randomSessionId()
        transition(State.CONNECTING, reason)
        sendXml("<ExlapConnectionRequest session_id=\"$sessionId\" />")
    }

    private fun processIncoming(xml: String) {
        try {
            val doc = documentFactory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val root = doc.documentElement ?: return
            if (root.tagName == "ExlapBeacon") return
            if (root.getAttribute("session_id") != sessionId) return

            if (state != State.ACTIVE) {
                Runtime.hub.emit(
                    TelemetryEvent(
                        "exlap",
                        "protocol_rx",
                        safeXml(xml).take(900),
                        attributes = mapOf("state" to state.name, "tag" to root.tagName),
                    ),
                    mqtt = false,
                )
            }

            if (state == State.CONNECTING && root.tagName == "ExlapConnectionReturn") {
                if (root.getAttribute("connected") == "true") {
                    transition(State.WAIT_INIT, "server_connected")
                } else {
                    transition(State.FAILED, "server_rejected_connection")
                }
                return
            }

            if (root.tagName == "ExlapConnectionClosed") {
                handler.postDelayed({ beginSession("server_closed_session") }, SESSION_RETRY_MS)
                return
            }

            if (root.tagName != "ExlapStatement") return
            directElements(root).forEach(::handleStatement)
        } catch (t: Throwable) {
            emitError("protocol_parse", t, mapOf("sample" to safeXml(xml).take(500)))
        }
    }

    private fun handleStatement(element: Element) {
        when (state) {
            State.WAIT_INIT -> {
                if (element.tagName == "Status" && element.getElementsByTagName("Init").length > 0) {
                    transition(State.WAIT_CAPABILITIES, "init")
                    sendRequest("<Protocol version=\"1\" returnCapabilities=\"true\"/>")
                }
            }

            State.WAIT_CAPABILITIES -> {
                if (element.tagName == "Rsp" && element.getElementsByTagName("Capabilities").length > 0) {
                    transition(State.WAIT_AUTH_CHALLENGE, "capabilities")
                    // Current MIB2 implementations expect the requested digest algorithm explicitly.
                    sendRequest("<Authenticate phase=\"challenge\" useHash=\"sha256\"/>")
                }
            }

            State.WAIT_AUTH_CHALLENGE -> {
                if (element.tagName == "Rsp" && element.getElementsByTagName("Challenge").length > 0) {
                    val challenge = element.getElementsByTagName("Challenge").item(0) as Element
                    val nonce = Base64.decode(challenge.getAttribute("nonce"), Base64.DEFAULT)
                    val cnonce = ByteArray(16).also(random::nextBytes)
                    val credential = CREDENTIALS[credentialIndex]
                    val digest = computeDigest(credential, nonce, cnonce)
                    transition(State.WAIT_AUTH_RESPONSE, "challenge")
                    sendRequest(
                        "<Authenticate phase=\"response\" user=\"${xmlEscape(credential.user)}\" " +
                            "cnonce=\"${Base64.encodeToString(cnonce, Base64.NO_WRAP)}\" " +
                            "digest=\"${Base64.encodeToString(digest, Base64.NO_WRAP)}\"/>"
                    )
                }
            }

            State.WAIT_AUTH_RESPONSE -> {
                if (element.tagName == "Rsp") {
                    val failed = directElements(element).isNotEmpty() ||
                        element.getElementsByTagName("Error").length > 0
                    if (failed) {
                        if (credentialIndex + 1 < CREDENTIALS.size) {
                            credentialIndex += 1
                            Runtime.hub.emit(
                                TelemetryEvent(
                                    "exlap",
                                    "authentication",
                                    "retry",
                                    "estimated",
                                    attributes = mapOf("credential_index" to credentialIndex),
                                ),
                                mqtt = false,
                            )
                            handler.postDelayed({ beginSession("auth_retry") }, 300L)
                        } else {
                            Runtime.hub.emit(
                                TelemetryEvent("exlap", "authentication", false, "error"),
                                mqtt = false,
                            )
                            transition(State.FAILED, "authentication_failed")
                        }
                    } else {
                        Runtime.hub.emit(
                            TelemetryEvent("exlap", "authentication", true, "success"),
                            mqtt = false,
                        )
                        transition(State.WAIT_URL_LIST, "authenticated")
                        sendRequest("<Dir/>")
                    }
                }
            }

            State.WAIT_URL_LIST -> {
                if (element.tagName == "Rsp" && element.getElementsByTagName("UrlList").length > 0) {
                    val matches = element.getElementsByTagName("Match")
                    urls = (0 until matches.length)
                        .mapNotNull { i -> (matches.item(i) as? Element)?.getAttribute("url") }
                        .filter { it.isNotBlank() }
                        .distinct()
                    Runtime.hub.emit(
                        TelemetryEvent(
                            "exlap",
                            "directory",
                            urls.size,
                            if (urls.isEmpty()) "unavailable" else "success",
                            attributes = mapOf("urls" to urls.take(80)),
                        ),
                        mqtt = false,
                    )
                    if (urls.isEmpty()) {
                        transition(State.FAILED, "empty_directory")
                    } else {
                        transition(State.WAIT_INTERFACES, "directory_ready")
                        interfaceResponsesRemaining = urls.size
                        urls.forEach { sendRequest("<Interface url=\"${xmlEscape(it)}\"/>") }
                    }
                }
            }

            State.WAIT_INTERFACES -> {
                if (element.tagName == "Rsp") {
                    captureSchemas(element)
                    interfaceResponsesRemaining = (interfaceResponsesRemaining - 1).coerceAtLeast(0)
                    if (interfaceResponsesRemaining == 0) {
                        Runtime.hub.emit(
                            TelemetryEvent(
                                "exlap",
                                "schema",
                                schema.size,
                                "success",
                                attributes = mapOf("keys" to schema.keys.take(120)),
                            ),
                            mqtt = false,
                        )
                        transition(State.ACTIVE, "subscribing")
                        urls.forEach { sendRequest("<Subscribe url=\"${xmlEscape(it)}\" timeStamp=\"true\"/>") }
                    }
                }
            }

            State.ACTIVE -> {
                if (element.tagName == "Dat") {
                    handleDat(element)
                } else {
                    val dats = element.getElementsByTagName("Dat")
                    for (i in 0 until dats.length) (dats.item(i) as? Element)?.let(::handleDat)
                }
            }

            else -> Unit
        }
    }

    private fun captureSchemas(response: Element) {
        val objects = response.getElementsByTagName("Object")
        for (i in 0 until objects.length) {
            val obj = objects.item(i) as? Element ?: continue
            val url = obj.getAttribute("url").takeIf { it.isNotBlank() } ?: continue
            directElements(obj).forEach { member ->
                val name = member.getAttribute("name").takeIf { it.isNotBlank() } ?: return@forEach
                val key = keyFor(url, name)
                schema[key] = Schema(
                    rawUrl = url,
                    member = name,
                    type = member.tagName,
                    unit = member.getAttribute("unit").takeIf { it.isNotBlank() },
                    description = null,
                )
            }
        }
    }

    private fun handleDat(dat: Element) {
        val url = dat.getAttribute("url")
        val timestamp = dat.getAttribute("timeStamp").takeIf { it.isNotBlank() }
        directElements(dat).forEach { valueElement ->
            val name = valueElement.getAttribute("name").takeIf { it.isNotBlank() } ?: return@forEach
            val key = keyFor(url, name)
            val stateValue = valueElement.getAttribute("state")
            val raw = valueElement.getAttribute("val").takeIf { valueElement.hasAttribute("val") }
            val status = when (stateValue.lowercase(Locale.ROOT)) {
                "error" -> "error"
                "nodata" -> "unavailable"
                else -> "success"
            }
            val metadata = schema[key]
            val value = if (status == "success") parseValue(raw, metadata?.type) else null
            Runtime.hub.emit(
                TelemetryEvent(
                    "exlap",
                    key,
                    value,
                    status,
                    attributes = mapOf(
                        "url" to url,
                        "member" to name,
                        "exlap_state" to stateValue.takeIf { it.isNotBlank() },
                        "timestamp" to timestamp,
                        "type" to metadata?.type,
                        "unit" to metadata?.unit,
                    ),
                )
            )
        }
    }

    private fun parseValue(raw: String?, declaredType: String?): Any? {
        raw ?: return null
        return when (declaredType) {
            "Absolute", "Relative" -> raw.toDoubleOrNull() ?: raw
            "Activity" -> when (raw.lowercase(Locale.ROOT)) {
                "true", "1" -> true
                "false", "0" -> false
                else -> raw
            }
            else -> raw.toLongOrNull()
                ?: raw.toDoubleOrNull()
                ?: when (raw.lowercase(Locale.ROOT)) {
                    "true" -> true
                    "false" -> false
                    else -> raw
                }
        }
    }

    private fun sendRequest(payload: String) {
        val id = nextRequestId++
        sendXml("<ExlapStatement session_id=\"$sessionId\"><Req id=\"$id\">$payload</Req></ExlapStatement>")
    }

    private fun sendXml(xml: String) {
        try {
            manager?.sendData(xml.toByteArray(StandardCharsets.UTF_8))
                ?: throw IllegalStateException("ExLAP vendor manager unavailable")
            Runtime.hub.emit(
                TelemetryEvent(
                    "exlap",
                    "protocol_tx",
                    protocolLabel(xml),
                    attributes = mapOf("state" to state.name),
                ),
                mqtt = false,
            )
        } catch (t: Throwable) {
            emitError("protocol_tx", t, mapOf("phase" to protocolLabel(xml)))
        }
    }

    private fun transition(next: State, reason: String) {
        state = next
        Runtime.hub.emit(
            TelemetryEvent(
                "exlap",
                "state",
                next.name.lowercase(Locale.ROOT),
                if (next == State.FAILED) "error" else "success",
                attributes = mapOf("reason" to reason),
            ),
            mqtt = false,
        )
    }

    private fun computeDigest(credential: Credential, nonce: ByteArray, cnonce: ByteArray): ByteArray {
        val material = String.format(
            Locale.ROOT,
            "%.44s:%.44s:%.44s:%.44s",
            credential.user,
            credential.password,
            Base64.encodeToString(nonce, Base64.NO_WRAP),
            Base64.encodeToString(cnonce, Base64.NO_WRAP),
        )
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun directElements(parent: Element): List<Element> = buildList {
        var child: Node? = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
            child = child.nextSibling
        }
    }

    private fun keyFor(url: String, member: String): String {
        val base = sanitize(url.trim('/')).ifBlank { "unknown" }
        val part = sanitize(member).ifBlank { "value" }
        return if (part == "value" || base.endsWith(part, ignoreCase = true)) base else "${base}_$part"
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.-]+"), "_")
        .trim('_')
        .lowercase(Locale.ROOT)
        .take(180)

    private fun safeXml(xml: String): String = xml
        .replace(Regex("(?i)(nonce|cnonce|digest)=\"[^\"]*\"")) { "${it.groupValues[1]}=\"[redacted]\"" }

    private fun protocolLabel(xml: String): String = when {
        "ExlapConnectionRequest" in xml -> "connection_request"
        "<Protocol " in xml -> "protocol"
        "phase=\"challenge\"" in xml -> "auth_challenge"
        "phase=\"response\"" in xml -> "auth_response"
        "<Dir" in xml -> "directory"
        "<Interface " in xml -> "interface"
        "<Subscribe " in xml -> "subscribe"
        else -> "statement"
    }

    private fun randomSessionId(): String = buildString(32) {
        repeat(32) { append(SESSION_ALPHABET[random.nextInt(SESSION_ALPHABET.length)]) }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun emitError(key: String, t: Throwable, attributes: Map<String, Any?> = emptyMap()) {
        Runtime.hub.emit(
            TelemetryEvent(
                "exlap",
                key,
                null,
                "error",
                attributes = attributes + mapOf(
                    "error_type" to t.javaClass.name,
                    "error" to t.message,
                ),
            ),
            mqtt = false,
        )
    }

    companion object {
        const val PERMISSION_VEX = "com.google.android.gms.permission.CAR_VENDOR_EXTENSION"
        const val VENDOR_CHANNEL = "com.vwag.infotainment.gal.exlap"
        private const val CAR_RETRY_MS = 5_000L
        private const val SESSION_RETRY_MS = 1_000L
        private const val SESSION_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        // Public VAG ExLAP client credentials used by known MIB2 implementations. They are protocol
        // credentials, not user/account secrets, and are never written to diagnostics or MQTT.
        private val CREDENTIALS = listOf(
            Credential("Test_TB-105000", "s4T2K6BAv0a7LQvrv3vdaUl17xEl2WJOpTmAThpRZe0=="),
            Credential("RSE_L-CA2000", "T53Facvq51jO8vQJrBNx3MqLWmPcHf/hkow7yLu7SuA=="),
            Credential("RSE_3-DE1400", "KozPo8iE0j72pkbWXKcP0QihpxgML3Opp8fNJZ0wN24=="),
            Credential("ML_74-125000", "Fo7arEpPhAgMMznzxRlV8B7eeZgNDIYQcy0Gr7Ad1Fg=="),
        )
    }
}
