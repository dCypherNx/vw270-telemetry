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
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class SystemCollector(private val context: Context) {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val proxies = mutableListOf<BluetoothProfile>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            snapshotBattery(intent)
        }
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
        snapshotBluetoothProfiles()
    }

    fun snapshotNow() {
        snapshotSystem()
        snapshotAndroidAutoPackage()
        emitBluetoothAdapter()
        snapshotUsb()
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Throwable) {
        }
        proxies.toList().forEach { proxy ->
            try {
                adapter?.closeProfileProxy(profileType(proxy), proxy)
            } catch (_: Throwable) {
            }
        }
        proxies.clear()
    }

    private fun snapshotBattery(intent: Intent?) {
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Runtime.hub.emit(
            TelemetryEvent(
                "system",
                "battery",
                level,
                attributes = mapOf(
                    "charging" to bm.isCharging,
                    "charge_counter_uah" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                    "current_now_ua" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                    "current_avg_ua" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                    "voltage_mv" to intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
                    "temperature_tenths_c" to intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
                    "plugged" to intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                ),
            )
        )
    }

    private fun snapshotSystem() {
        Runtime.hub.emit(
            TelemetryEvent(
                "system",
                "device",
                mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "device" to Build.DEVICE,
                    "sdk" to Build.VERSION.SDK_INT,
                    "release" to Build.VERSION.RELEASE,
                    "thermal_status" to if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null,
                    "power_save" to pm.isPowerSaveMode,
                    "interactive" to pm.isInteractive,
                ),
            )
        )
    }

    private fun snapshotAndroidAutoPackage() {
        val pkg = AaStateCollector.AA_PACKAGE
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            Runtime.hub.emit(
                TelemetryEvent(
                    "aa",
                    "package",
                    mapOf("version_name" to pi.versionName, "version_code" to pi.longVersionCode),
                )
            )
        } catch (t: Throwable) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "aa",
                    "package",
                    null,
                    "unavailable",
                    attributes = mapOf("error" to t.message),
                )
            )
        }
    }

    private fun snapshotBluetoothProfiles() {
        if (!hasBluetoothPermission()) {
            Runtime.hub.emit(TelemetryEvent("bluetooth", "state", null, "permission_denied"))
            return
        }
        val a = adapter ?: return
        emitBluetoothAdapter()
        try {
            a.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        } catch (_: Throwable) {
        }
        try {
            a.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        } catch (_: Throwable) {
        }
    }

    private fun emitBluetoothAdapter() {
        if (!hasBluetoothPermission()) {
            Runtime.hub.emit(TelemetryEvent("bluetooth", "adapter", null, "permission_denied"))
            return
        }
        val a = adapter ?: return
        val bonded = runCatching {
            a.bondedDevices.map { device ->
                mapOf(
                    "name" to device.name,
                    "type" to device.type,
                    "bond_state" to device.bondState,
                    "uuids" to device.uuids?.map { it.uuid.toString() }.orEmpty(),
                )
            }
        }.getOrElse { emptyList() }

        Runtime.hub.emit(
            TelemetryEvent(
                "bluetooth",
                "adapter",
                mapOf(
                    "enabled" to a.isEnabled,
                    "state" to a.state,
                    "bonded" to bonded,
                ),
            )
        )
    }

    private fun emitBluetoothProfile(profile: Int, proxy: BluetoothProfile) {
        if (!hasBluetoothPermission()) return
        val connected = runCatching {
            proxy.connectedDevices.map { device ->
                mapOf(
                    "name" to device.name,
                    "type" to device.type,
                    "uuids" to device.uuids?.map { it.uuid.toString() }.orEmpty(),
                )
            }
        }.getOrElse { emptyList() }
        Runtime.hub.emit(TelemetryEvent("bluetooth", "profile_$profile", connected))
    }

    private fun snapshotUsb() {
        try {
            val devices = usb.deviceList.values.map { device ->
                mapOf(
                    "device_id" to device.deviceId,
                    "vendor_id" to device.vendorId,
                    "product_id" to device.productId,
                    "device_class" to device.deviceClass,
                    "device_subclass" to device.deviceSubclass,
                    "device_protocol" to device.deviceProtocol,
                    "manufacturer" to runCatching { device.manufacturerName }.getOrNull(),
                    "product" to runCatching { device.productName }.getOrNull(),
                    "interface_count" to device.interfaceCount,
                    "has_permission" to usb.hasPermission(device),
                )
            }
            val accessories = usb.accessoryList.orEmpty().map { accessory ->
                mapOf(
                    "manufacturer" to accessory.manufacturer,
                    "model" to accessory.model,
                    "description" to accessory.description,
                    "version" to accessory.version,
                    "uri" to accessory.uri,
                    "has_permission" to usb.hasPermission(accessory),
                )
            }
            Runtime.hub.emit(
                TelemetryEvent(
                    "usb",
                    "transport",
                    mapOf("devices" to devices, "accessories" to accessories),
                    attributes = mapOf(
                        "device_count" to devices.size,
                        "accessory_count" to accessories.size,
                    ),
                ),
                mqtt = false,
            )
        } catch (t: Throwable) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "usb",
                    "transport",
                    null,
                    "error",
                    attributes = mapOf("error" to t.message),
                ),
                mqtt = false,
            )
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun profileType(profile: BluetoothProfile): Int = when (profile) {
        is BluetoothA2dp -> BluetoothProfile.A2DP
        is BluetoothHeadset -> BluetoothProfile.HEADSET
        else -> -1
    }
}
