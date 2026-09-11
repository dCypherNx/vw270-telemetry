package net.jurgensen.vw270telemetry.collectors

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

/**
 * Read-only probe for surfaces on the phone that can change when Android Auto projection starts.
 *
 * It deliberately uses only public Android APIs. No shell execution, root, OBD or vehicle control.
 * The intent is to compare disconnected vs projected states and discover which transport/audio/
 * display/input/package surfaces become visible while the real VW head unit is connected.
 */
class ProjectionProbeCollector(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mediaRouter = context.getSystemService(MediaRouter::class.java)

    @Volatile private var running = false
    private var sequence = 0L

    private val periodic = object : Runnable {
        override fun run() {
            if (!running) return
            snapshotAsync("periodic")
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = snapshotAsync("display_added:$displayId")
        override fun onDisplayRemoved(displayId: Int) = snapshotAsync("display_removed:$displayId")
        override fun onDisplayChanged(displayId: Int) = snapshotAsync("display_changed:$displayId")
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            emitTransition("audio_devices_added", addedDevices.map(::audioDevice))
            snapshotAsync("audio_devices_added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            emitTransition("audio_devices_removed", removedDevices.map(::audioDevice))
            snapshotAsync("audio_devices_removed")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitTransition("network_available", network.toString())
            snapshotAsync("network_available")
        }

        override fun onLost(network: Network) {
            emitTransition("network_lost", network.toString())
            snapshotAsync("network_lost")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "probe", "network_capabilities_changed",
                    mapOf("network" to network.toString(), "capabilities" to caps.toString())
                ),
                mqtt = false,
            )
        }
    }

    fun start(reason: String = "projection") {
        if (running) return
        running = true
        Runtime.hub.emit(
            TelemetryEvent("probe", "collector", "started", attributes = mapOf("reason" to reason)),
            mqtt = false,
        )
        snapshotAndroidAutoSurface(reason)
        try { displayManager.registerDisplayListener(displayListener, handler) } catch (t: Throwable) { emitError("display_listener", t) }
        try { audio.registerAudioDeviceCallback(audioCallback, handler) } catch (t: Throwable) { emitError("audio_callback", t) }
        try {
            val request = NetworkRequest.Builder().build()
            connectivity.registerNetworkCallback(request, networkCallback)
        } catch (t: Throwable) {
            emitError("network_callback", t)
        }
        snapshotAsync("start")
        handler.postDelayed({ snapshotAsync("t_plus_5s") }, 5_000L)
        handler.postDelayed({ snapshotAsync("t_plus_15s") }, 15_000L)
        handler.postDelayed(periodic, PERIOD_MS)
    }

    fun stop(reason: String = "disconnect") {
        if (!running) return
        snapshotOnce("before_stop:$reason")
        running = false
        handler.removeCallbacksAndMessages(null)
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        try { audio.unregisterAudioDeviceCallback(audioCallback) } catch (_: Throwable) {}
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Throwable) {}
        Runtime.hub.emit(
            TelemetryEvent("probe", "collector", "stopped", attributes = mapOf("reason" to reason)),
            mqtt = false,
        )
    }

    fun snapshotOnce(reason: String = "manual") {
        val seq = ++sequence
        val started = System.currentTimeMillis()
        val payload = linkedMapOf<String, Any?>(
            "sequence" to seq,
            "reason" to reason,
            "displays" to snapshotDisplays(),
            "input_devices" to snapshotInputDevices(),
            "audio" to snapshotAudio(),
            "usb" to snapshotUsb(),
            "networks" to snapshotNetworks(),
            "media_routes" to snapshotMediaRoutes(),
            "car_permissions" to snapshotCarPermissions(),
        )
        Runtime.hub.emit(
            TelemetryEvent(
                "probe", "projection_snapshot", payload,
                attributes = mapOf("elapsed_ms" to (System.currentTimeMillis() - started))
            ),
            mqtt = false,
        )
    }

    private fun snapshotAsync(reason: String) {
        Thread({ snapshotOnce(reason) }, "vw270-probe").start()
    }

    private fun snapshotAndroidAutoSurface(reason: String) {
        Thread({
            try {
                val pm = context.packageManager
                val flags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_META_DATA
                val pi = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(AA_PACKAGE, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(AA_PACKAGE, flags)
                }

                Runtime.hub.emit(
                    TelemetryEvent(
                        "probe", "android_auto_surface",
                        mapOf(
                            "version_name" to pi.versionName,
                            "version_code" to pi.longVersionCode,
                            "activities" to pi.activities.orEmpty().map {
                                mapOf("name" to it.name, "exported" to it.exported, "permission" to it.permission, "process" to it.processName)
                            },
                            "services" to pi.services.orEmpty().map {
                                mapOf(
                                    "name" to it.name,
                                    "exported" to it.exported,
                                    "permission" to it.permission,
                                    "process" to it.processName,
                                    "foreground_service_type" to if (Build.VERSION.SDK_INT >= 29) it.foregroundServiceType else null,
                                )
                            },
                            "receivers" to pi.receivers.orEmpty().map {
                                mapOf("name" to it.name, "exported" to it.exported, "permission" to it.permission, "process" to it.processName)
                            },
                            "providers" to pi.providers.orEmpty().map {
                                mapOf(
                                    "name" to it.name,
                                    "authority" to it.authority,
                                    "exported" to it.exported,
                                    "read_permission" to it.readPermission,
                                    "write_permission" to it.writePermission,
                                    "process" to it.processName,
                                )
                            },
                            "requested_permissions" to requestedPermissions(pi),
                        ),
                        attributes = mapOf("reason" to reason)
                    ),
                    mqtt = false,
                )
            } catch (t: Throwable) {
                emitError("android_auto_surface", t)
            }
        }, "vw270-aa-surface").start()
    }

    private fun requestedPermissions(pi: PackageInfo): List<Map<String, Any?>> {
        val names: Array<String> = pi.requestedPermissions ?: emptyArray()
        val permissionFlags: IntArray = pi.requestedPermissionsFlags ?: IntArray(0)
        return names.mapIndexed { index, name ->
            val granted = index < permissionFlags.size &&
                permissionFlags[index].and(PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            mapOf("name" to name, "granted" to granted)
        }
    }

    private fun snapshotDisplays(): List<Map<String, Any?>> = runCatching {
        displayManager.displays.map { d ->
            val mode = d.mode
            mapOf(
                "id" to d.displayId,
                "name" to d.name,
                "state" to d.state,
                "flags" to d.flags,
                "rotation" to d.rotation,
                "width" to mode.physicalWidth,
                "height" to mode.physicalHeight,
                "refresh_hz" to mode.refreshRate,
                "is_valid" to d.isValid,
            )
        }
    }.getOrElse { listOf(mapOf("error" to errorText(it))) }

    private fun snapshotInputDevices(): List<Map<String, Any?>> = runCatching {
        inputManager.inputDeviceIds.toList().mapNotNull { id -> inputManager.getInputDevice(id) }.map { d ->
            mapOf(
                "id" to d.id,
                "name" to d.name,
                "descriptor" to d.descriptor,
                "vendor_id" to d.vendorId,
                "product_id" to d.productId,
                "sources" to d.sources,
                "keyboard_type" to d.keyboardType,
                "virtual" to d.isVirtual,
                "enabled" to d.isEnabled,
            )
        }
    }.getOrElse { listOf(mapOf("error" to errorText(it))) }

    private fun snapshotAudio(): Map<String, Any?> = runCatching {
        val devices = (
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).asList() +
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
            ).distinctBy { it.id }.map(::audioDevice)
        mapOf(
            "mode" to audio.mode,
            "music_active" to audio.isMusicActive,
            "devices" to devices,
            "communication_device" to if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.let(::audioDevice) else null,
        )
    }.getOrElse { mapOf("error" to errorText(it)) }

    private fun audioDevice(d: AudioDeviceInfo): Map<String, Any?> = mapOf(
        "id" to d.id,
        "type" to d.type,
        "product_name" to d.productName?.toString(),
        "address" to d.address,
        "source" to d.isSource,
        "sink" to d.isSink,
        "sample_rates" to d.sampleRates.toList(),
        "channel_counts" to d.channelCounts.toList(),
        "encodings" to d.encodings.toList(),
    )

    private fun snapshotUsb(): Map<String, Any?> = runCatching {
        val devices = usb.deviceList.values.map { d ->
            mapOf(
                "device_id" to d.deviceId,
                "device_name" to d.deviceName,
                "vendor_id" to d.vendorId,
                "product_id" to d.productId,
                "class" to d.deviceClass,
                "subclass" to d.deviceSubclass,
                "protocol" to d.deviceProtocol,
                "manufacturer" to runCatching { d.manufacturerName }.getOrNull(),
                "product" to runCatching { d.productName }.getOrNull(),
                "interfaces" to (0 until d.interfaceCount).map { i ->
                    d.getInterface(i).let { intf ->
                        mapOf(
                            "id" to intf.id,
                            "name" to intf.name,
                            "class" to intf.interfaceClass,
                            "subclass" to intf.interfaceSubclass,
                            "protocol" to intf.interfaceProtocol,
                            "endpoint_count" to intf.endpointCount,
                        )
                    }
                },
            )
        }
        val accessories = usb.accessoryList.orEmpty().map { a ->
            mapOf(
                "manufacturer" to a.manufacturer,
                "model" to a.model,
                "description" to a.description,
                "version" to a.version,
                "uri" to a.uri,
                "serial" to runCatching { a.serial }.getOrNull(),
            )
        }
        mapOf("devices" to devices, "accessories" to accessories)
    }.getOrElse { mapOf("error" to errorText(it)) }

    private fun snapshotNetworks(): List<Map<String, Any?>> = runCatching {
        connectivity.allNetworks.map { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            val lp = connectivity.getLinkProperties(network)
            mapOf(
                "network" to network.toString(),
                "transports" to transportNames(caps),
                "capabilities" to caps?.toString(),
                "interface" to lp?.interfaceName,
                "link_addresses" to lp?.linkAddresses?.map { it.toString() },
                "dns" to lp?.dnsServers?.map { it.hostAddress },
                "routes" to lp?.routes?.map { it.toString() },
                "domains" to lp?.domains,
                "mtu" to lp?.mtu,
            )
        }
    }.getOrElse { listOf(mapOf("error" to errorText(it))) }

    private fun transportNames(caps: NetworkCapabilities?): List<String> {
        if (caps == null) return emptyList()
        val out = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "cellular"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "wifi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out += "bluetooth"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += "ethernet"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += "vpn"
        if (Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) out += "usb"
        return out
    }

    private fun snapshotMediaRoutes(): Map<String, Any?> = runCatching {
        val selected = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO or MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        mapOf(
            "selected" to selected?.let { r ->
                mapOf(
                    "name" to r.name?.toString(),
                    "description" to r.description?.toString(),
                    "device_type" to r.deviceType,
                    "playback_type" to r.playbackType,
                    "playback_stream" to r.playbackStream,
                    "volume" to r.volume,
                    "volume_max" to r.volumeMax,
                    "volume_handling" to r.volumeHandling,
                    "presentation_display_id" to r.presentationDisplay?.displayId,
                )
            }
        )
    }.getOrElse { mapOf("error" to errorText(it)) }

    private fun snapshotCarPermissions(): Map<String, Any?> = CAR_PERMISSIONS.associateWith { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun emitTransition(key: String, value: Any?) {
        Runtime.hub.emit(TelemetryEvent("probe", key, value), mqtt = false)
    }

    private fun emitError(key: String, t: Throwable) {
        Runtime.hub.emit(
            TelemetryEvent(
                "probe", key, null, "error",
                attributes = mapOf("error" to errorText(t))
            ),
            mqtt = false,
        )
    }

    private fun errorText(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: ""}"

    companion object {
        private const val AA_PACKAGE = "com.google.android.projection.gearhead"
        private const val PERIOD_MS = 30_000L
        private val CAR_PERMISSIONS = listOf(
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            "com.google.android.gms.permission.CAR_FUEL",
        )
    }
}
