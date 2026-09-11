package net.jurgensen.vw270telemetry.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class LocationCollector(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val lm = context.getSystemService(LocationManager::class.java)
    private var lastLocation: Location? = null
    private var tripMeters = 0.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val constellations = mutableMapOf<Int, Int>()
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                val c = status.getConstellationType(i)
                constellations[c] = (constellations[c] ?: 0) + 1
            }
            Runtime.hub.emit(
                TelemetryEvent(
                    "gnss", "satellites", status.satelliteCount,
                    attributes = mapOf("used_in_fix" to used, "constellations" to constellations)
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasLocation()) {
            Runtime.hub.emit(TelemetryEvent("phone", "location", null, "permission_denied"))
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(250L)
            .setMaxUpdateDelayMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, context.mainLooper)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssCallback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            }
            Runtime.hub.emit(TelemetryEvent("phone", "location_collector", true))
        } catch (t: Throwable) {
            Runtime.hub.emit(
                TelemetryEvent(
                    "phone",
                    "location_collector",
                    false,
                    "start_failed",
                    attributes = mapOf("error" to (t.message ?: t.javaClass.simpleName)),
                )
            )
        }
    }

    fun stop() {
        fused.removeLocationUpdates(callback)
        try { lm.unregisterGnssStatusCallback(gnssCallback) } catch (_: Throwable) {}
    }

    private fun onLocation(l: Location) {
        val prev = lastLocation
        if (prev != null && l.accuracy <= 30f && prev.accuracy <= 30f) {
            val d = prev.distanceTo(l).toDouble()
            if (d in 0.3..500.0) tripMeters += d
        }
        lastLocation = l
        Runtime.hub.emit(
            TelemetryEvent(
                "phone_location", "fix",
                mapOf(
                    "provider" to l.provider,
                    "lat" to l.latitude,
                    "lon" to l.longitude,
                    "accuracy_m" to if (l.hasAccuracy()) l.accuracy else null,
                    "vertical_accuracy_m" to if (android.os.Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
                    "altitude_m" to if (l.hasAltitude()) l.altitude else null,
                    "speed_mps" to if (l.hasSpeed()) l.speed else null,
                    "speed_accuracy_mps" to if (android.os.Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                    "bearing_deg" to if (l.hasBearing()) l.bearing else null,
                    "bearing_accuracy_deg" to if (android.os.Build.VERSION.SDK_INT >= 26 && l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
                    "time_ms" to l.time,
                    "elapsed_realtime_ns" to l.elapsedRealtimeNanos,
                    "trip_m" to tripMeters,
                ),
                sourceTimestampMs = l.time
            )
        )
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
