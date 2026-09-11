package net.jurgensen.vw270telemetry.collectors

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.media.AudioManager
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class SystemCollector(private val context: Context) {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val proxies = mutableListOf<BluetoothProfile>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { snapshotBattery(intent) }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies += proxy
            emitBluetoothProfile(profile, proxy)
        }
        override fun onServiceDisconnected(profile: Int) {
            Runtime.hub.emit(TelemetryEvent("bluetooth", "profile_$profile", null, "disconnected"))
        }
    }

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        snapshotNow()
        snapshotBluetooth()
    }

    fun snapshotNow() {
        snapshotSystem()
        snapshotAndroidAutoPackage()
        emitBluetoothAdapter()
        snapshotAudioUsbWifi()
    }

    fun stop() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        proxies.toList().forEach { p -> try { adapter?.closeProfileProxy(profileType(p), p) } catch (_: Throwable) {} }
        proxies.clear()
    }

    private fun snapshotBattery(intent: Intent?) {
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Runtime.hub.emit(
            TelemetryEvent(
                "system", "battery", level,
                attributes = mapOf(
                    "charging" to bm.isCharging,
                    "charge_counter_uah" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                    "current_now_ua" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                    "current_avg_ua" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                    "voltage_mv" to intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
                    "temperature_tenths_c" to intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
                    "plugged" to intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                )
            )
        )
    }

    private fun snapshotSystem() {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        Runtime.hub.emit(
            TelemetryEvent(
                "system", "device",
                mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "device" to Build.DEVICE,
                    "sdk" to Build.VERSION.SDK_INT,
                    "release" to Build.VERSION.RELEASE,
                    "thermal_status" to if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null,
                    "power_save" to pm.isPowerSaveMode,
                    "interactive" to pm.isInteractive,
                    "network_capabilities" to caps?.toString(),
                )
            )
        )
    }

    private fun snapshotAndroidAutoPackage() {
        val pkg = "com.google.android.projection.gearhead"
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0)
            }
            Runtime.hub.emit(
                TelemetryEvent(
                    "aa", "package",
                    mapOf("version_name" to pi.versionName, "version_code" to pi.longVersionCode)
                )
            )
        } catch (t: Throwable) {
            Runtime.hub.emit(TelemetryEvent("aa", "package", null, "unavailable", attributes = mapOf("error" to t.message)))
        }
    }

    private fun snapshotBluetooth() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Runtime.hub.emit(TelemetryEvent("bluetooth", "state", null, "permission_denied"))
            return
        }
        val a = adapter ?: return
        emitBluetoothAdapter()
        try { a.getProfileProxy(context, profileListener, BluetoothProfile.A2DP) } catch (_: Throwable) {}
        try { a.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET) } catch (_: Throwable) {}
    }

    private fun emitBluetoothAdapter() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Runtime.hub.emit(TelemetryEvent("bluetooth", "adapter", null, "permission_denied"))
            return
        }
        val a = adapter ?: return
        Runtime.hub.emit(
            TelemetryEvent(
                "bluetooth", "adapter",
                mapOf(
                    "enabled" to a.isEnabled,
                    "state" to a.state,
                    "bonded" to a.bondedDevices.map { d -> mapOf("name" to d.name, "address" to d.address, "type" to d.type) }
                )
            )
        )
    }

    private fun emitBluetoothProfile(profile: Int, proxy: BluetoothProfile) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        Runtime.hub.emit(
            TelemetryEvent(
                "bluetooth", "profile_$profile",
                proxy.connectedDevices.map { d -> mapOf("name" to d.name, "address" to d.address, "type" to d.type) }
            )
        )
    }

    private fun snapshotAudioUsbWifi() {
        try {
            val devices = (
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS).asList() +
                    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
                )
                .distinctBy { it.id }
                .map { d ->
                    mapOf(
                        "id" to d.id,
                        "type" to d.type,
                        "product_name" to d.productName?.toString(),
                        "address" to d.address,
                        "source" to d.isSource,
                        "sink" to d.isSink,
                    )
                }
            Runtime.hub.emit(
                TelemetryEvent(
                    "audio", "devices", devices,
                    attributes = mapOf("mode" to audio.mode, "speakerphone" to audio.isSpeakerphoneOn)
                )
            )
        } catch (t: Throwable) {
            Runtime.hub.emit(TelemetryEvent("audio", "devices", null, "error", attributes = mapOf("error" to t.message)))
        }

        try {
            val devices = usb.deviceList.values.map { d ->
                mapOf(
                    "device_id" to d.deviceId,
                    "vendor_id" to d.vendorId,
                    "product_id" to d.productId,
                    "device_class" to d.deviceClass,
                    "device_subclass" to d.deviceSubclass,
                    "device_protocol" to d.deviceProtocol,
                    "manufacturer" to runCatching { d.manufacturerName }.getOrNull(),
                    "product" to runCatching { d.productName }.getOrNull(),
                    "interface_count" to d.interfaceCount,
                )
            }
            Runtime.hub.emit(TelemetryEvent("usb", "devices", devices, attributes = mapOf("count" to devices.size)))
        } catch (t: Throwable) {
            Runtime.hub.emit(TelemetryEvent("usb", "devices", null, "error", attributes = mapOf("error" to t.message)))
        }

        try {
            @Suppress("DEPRECATION") val wi = wifi.connectionInfo
            Runtime.hub.emit(
                TelemetryEvent(
                    "wifi", "connection",
                    mapOf(
                        "ssid" to wi?.ssid,
                        "bssid" to wi?.bssid,
                        "rssi_dbm" to wi?.rssi,
                        "link_speed_mbps" to wi?.linkSpeed,
                        "frequency_mhz" to wi?.frequency,
                        "network_id" to wi?.networkId,
                    )
                )
            )
        } catch (t: Throwable) {
            Runtime.hub.emit(TelemetryEvent("wifi", "connection", null, "error", attributes = mapOf("error" to t.message)))
        }
    }

    private fun profileType(p: BluetoothProfile): Int = when (p) {
        is BluetoothA2dp -> BluetoothProfile.A2DP
        is BluetoothHeadset -> BluetoothProfile.HEADSET
        else -> -1
    }
}
