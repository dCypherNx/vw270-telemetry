package net.jurgensen.vw270telemetry.car

import android.location.Location
import androidx.annotation.OptIn
import androidx.car.app.CarContext
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.EvStatus
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import androidx.core.content.ContextCompat
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

@OptIn(ExperimentalCarApi::class)
class CarHardwareCollector(private val carContext: CarContext) {
    private val executor = ContextCompat.getMainExecutor(carContext)
    private var info: CarInfo? = null
    private var sensors: CarSensors? = null
    private var started = false

    private val modelListener = OnCarDataAvailableListener<Model> { onModel(it) }
    private val profileListener = OnCarDataAvailableListener<EnergyProfile> { onEnergyProfile(it) }
    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { onEnergy(it) }
    private val speedListener = OnCarDataAvailableListener<Speed> { onSpeed(it) }
    private val mileageListener = OnCarDataAvailableListener<Mileage> { onMileage(it) }
    private val tollListener = OnCarDataAvailableListener<TollCard> { onToll(it) }
    private val evListener = OnCarDataAvailableListener<EvStatus> { onEvStatus(it) }
    private val accelListener = OnCarDataAvailableListener<Accelerometer> { onAccelerometer(it) }
    private val gyroListener = OnCarDataAvailableListener<Gyroscope> { onGyroscope(it) }
    private val compassListener = OnCarDataAvailableListener<Compass> { onCompass(it) }
    private val locationListener = OnCarDataAvailableListener<CarHardwareLocation> { onCarLocation(it) }

    fun start() {
        if (started) return
        started = true
        emit("session", true, attributes = mapOf("car_app_api_level" to safe { carContext.carAppApiLevel }))

        try {
            val hw = carContext.getCarService(CarHardwareManager::class.java)
            info = hw.carInfo
            sensors = hw.carSensors
            emit("hardware_manager", "available")
        } catch (t: Throwable) {
            emit("hardware_manager", null, "error", mapOf("error" to t.compact()))
            return
        }

        val i = info
        if (i != null) {
            probe("model") { i.fetchModel(executor, modelListener) }
            probe("energy_profile") { i.fetchEnergyProfile(executor, profileListener) }
            probe("energy_level") { i.addEnergyLevelListener(executor, energyListener) }
            probe("speed") { i.addSpeedListener(executor, speedListener) }
            probe("mileage") { i.addMileageListener(executor, mileageListener) }
            probe("toll") { i.addTollListener(executor, tollListener) }
            probe("ev_status") { i.addEvStatusListener(executor, evListener) }
        }

        val s = sensors
        if (s != null) {
            val rate = CarSensors.UPDATE_RATE_FASTEST
            probe("accelerometer") { s.addAccelerometerListener(rate, executor, accelListener) }
            probe("gyroscope") { s.addGyroscopeListener(rate, executor, gyroListener) }
            probe("compass") { s.addCompassListener(rate, executor, compassListener) }
            probe("hardware_location") { s.addCarHardwareLocationListener(rate, executor, locationListener) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        info?.let { i ->
            safe { i.removeEnergyLevelListener(energyListener) }
            safe { i.removeSpeedListener(speedListener) }
            safe { i.removeMileageListener(mileageListener) }
            safe { i.removeTollListener(tollListener) }
            safe { i.removeEvStatusListener(evListener) }
        }
        sensors?.let { s ->
            safe { s.removeAccelerometerListener(accelListener) }
            safe { s.removeGyroscopeListener(gyroListener) }
            safe { s.removeCompassListener(compassListener) }
            safe { s.removeCarHardwareLocationListener(locationListener) }
        }
        info = null
        sensors = null
        emit("session", false)
    }

    private fun onModel(v: Model) {
        emitCarValue("model_manufacturer", v.manufacturer)
        emitCarValue("model_name", v.name)
        emitCarValue("model_year", v.year)
    }

    private fun onEnergyProfile(v: EnergyProfile) {
        emitCarValue("fuel_types", v.fuelTypes)
        emitCarValue("ev_connector_types", v.evConnectorTypes)
    }

    private fun onEnergy(v: EnergyLevel) {
        emitCarValue("battery_percent", v.batteryPercent)
        emitCarValue("fuel_percent", v.fuelPercent)
        emitCarValue("energy_is_low", v.energyIsLow)
        emitCarValue("range_remaining_m", v.rangeRemainingMeters)
        emitCarValue("distance_display_unit", v.distanceDisplayUnit)
    }

    private fun onSpeed(v: Speed) {
        emitCarValue("speed_raw_mps", v.rawSpeedMetersPerSecond)
        emitCarValue("speed_display_mps", v.displaySpeedMetersPerSecond)
        emitCarValue("speed_display_unit", v.speedDisplayUnit)
    }

    private fun onMileage(v: Mileage) {
        emitCarValue("odometer_m", v.odometerMeters)
        emitCarValue("odometer_display_unit", v.distanceDisplayUnit)
    }

    private fun onToll(v: TollCard) = emitCarValue("toll_card_state", v.cardState)

    private fun onEvStatus(v: EvStatus) {
        emitCarValue("ev_charge_port_open", v.evChargePortOpen)
        emitCarValue("ev_charge_port_connected", v.evChargePortConnected)
    }

    private fun onAccelerometer(v: Accelerometer) = emitCarValue("accelerometer_mps2", v.forces)
    private fun onGyroscope(v: Gyroscope) = emitCarValue("gyroscope_radps", v.rotations)
    private fun onCompass(v: Compass) = emitCarValue("compass_deg", v.orientations)

    private fun onCarLocation(v: CarHardwareLocation) {
        val cv = v.location
        val l = cv.value
        val value = if (l == null) null else locationMap(l)
        emitCarValue("location", cv, value)
    }

    private fun <T> emitCarValue(key: String, cv: CarValue<T>, overrideValue: Any? = cv.value) {
        emit(
            key = key,
            value = overrideValue,
            status = statusName(cv.status),
            attributes = mapOf(
                "car_value_status" to cv.status,
                "car_zones" to cv.carZones.map { it.toString() },
            ),
            sourceTs = cv.timestampMillis,
        )
    }

    private fun probe(name: String, block: () -> Unit) {
        try {
            block()
            emit("probe_$name", "registered")
        } catch (t: Throwable) {
            emit("probe_$name", null, "error", mapOf("error" to t.compact()))
        }
    }

    private fun emit(
        key: String,
        value: Any?,
        status: String = "success",
        attributes: Map<String, Any?> = emptyMap(),
        sourceTs: Long? = null,
    ) = Runtime.hub.emit(TelemetryEvent("car", key, value, status, sourceTs, attributes = attributes))

    private fun statusName(status: Int) = when (status) {
        CarValue.STATUS_SUCCESS -> "success"
        CarValue.STATUS_UNIMPLEMENTED -> "unimplemented"
        CarValue.STATUS_UNAVAILABLE -> "unavailable"
        CarValue.STATUS_UNKNOWN -> "unknown"
        else -> "status_$status"
    }

    private fun locationMap(l: Location) = buildMap<String, Any?> {
        put("provider", l.provider)
        put("lat", l.latitude)
        put("lon", l.longitude)
        put("accuracy_m", if (l.hasAccuracy()) l.accuracy else null)
        put("altitude_m", if (l.hasAltitude()) l.altitude else null)
        put("speed_mps", if (l.hasSpeed()) l.speed else null)
        put("bearing_deg", if (l.hasBearing()) l.bearing else null)
        put("time_ms", l.time)
        put("elapsed_realtime_ns", l.elapsedRealtimeNanos)
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
    private fun Throwable.compact() = "${javaClass.simpleName}: ${message ?: ""}".take(500)
}
