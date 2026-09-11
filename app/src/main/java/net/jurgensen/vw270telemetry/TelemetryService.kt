package net.jurgensen.vw270telemetry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import net.jurgensen.vw270telemetry.collectors.AaStateCollector
import net.jurgensen.vw270telemetry.collectors.ExlapVexCollector
import net.jurgensen.vw270telemetry.collectors.LocationCollector
import net.jurgensen.vw270telemetry.collectors.PhoneSensorCollector
import net.jurgensen.vw270telemetry.collectors.SystemCollector
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class TelemetryService : Service() {
    private var systemCollector: SystemCollector? = null
    private var aaCollector: AaStateCollector? = null
    private var exlapCollector: ExlapVexCollector? = null
    private var phoneSensors: PhoneSensorCollector? = null
    private var location: LocationCollector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var drivingCollectors = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteForeground(connected = false)

        systemCollector = SystemCollector(this).also { it.start() }
        aaCollector = AaStateCollector(this, ::onCarConnection).also { it.start() }
        exlapCollector = ExlapVexCollector(this).also { it.start() }
        Runtime.hub.emit(TelemetryEvent("system", "collector_service", "started"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Runtime.prefs.autoStart = true
        return START_STICKY
    }

    override fun onDestroy() {
        stopDrivingCollectors("service_destroyed")
        exlapCollector?.stop()
        aaCollector?.stop()
        systemCollector?.stop()
        exlapCollector = null
        aaCollector = null
        systemCollector = null
        Runtime.mqtt.close()
        Runtime.hub.emit(TelemetryEvent("system", "collector_service", "destroyed"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onCarConnection(type: Int) {
        if (type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE) {
            systemCollector?.snapshotNow()
            promoteForeground(connected = true)
            startDrivingCollectors(if (type == CarConnection.CONNECTION_TYPE_PROJECTION) "projection" else "native")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Android Auto conectado • ExLAP ativo"))
        } else {
            stopDrivingCollectors("car_disconnected")
            systemCollector?.snapshotNow()
            promoteForeground(connected = false)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Aguardando Android Auto / ExLAP"))
        }
    }

    private fun promoteForeground(connected: Boolean) {
        val notification = notification(if (connected) "Android Auto conectado • ExLAP ativo" else "Aguardando Android Auto / ExLAP")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (connected && hasBackgroundLocation()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } catch (t: Throwable) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                Runtime.hub.emit(
                    TelemetryEvent(
                        "system",
                        "foreground_type",
                        type,
                        "degraded",
                        attributes = mapOf("error" to "${t.javaClass.simpleName}: ${t.message}"),
                    )
                )
            }
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startDrivingCollectors(reason: String) {
        if (drivingCollectors) return
        drivingCollectors = true
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VW270Telemetry:drive").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
        }
        phoneSensors = PhoneSensorCollector(this).also { it.start() }
        location = LocationCollector(this).also { it.start() }
        Runtime.hub.emit(
            TelemetryEvent("system", "driving_collectors", true, attributes = mapOf("reason" to reason))
        )
    }

    private fun stopDrivingCollectors(reason: String) {
        if (!drivingCollectors) return
        drivingCollectors = false
        phoneSensors?.stop()
        location?.stop()
        phoneSensors = null
        location = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        Runtime.hub.emit(
            TelemetryEvent("system", "driving_collectors", false, attributes = mapOf("reason" to reason))
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "vw270_telemetry"
        private const val NOTIFICATION_ID = 270
    }
}
