package app.jamkkanman.live

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.jamkkanman.core.Gear
import app.jamkkanman.core.Signal
import kotlin.math.abs

/** How each input is obtained on this vehicle, shown on screen and in the README. */
data class SourceStatus(val name: String, val api: String, val state: String, val ok: Boolean)

/**
 * Live vehicle signals for the engine:
 * speed and gear from AAOS CarPropertyManager (PERF_VEHICLE_SPEED, GEAR_SELECTION), hazard lights from AAOS
 * when the platform grants it, driver door and belt from the Pleos Vehicle SDK, location from Pleos FusedLocation,
 * and navigation arrival from NaviHelper (the [naviListener] is attached by the app's navigator).
 */
class VehicleSignalSource(
    private val context: Context,
    private val emit: (Signal) -> Unit,
    private val onStatus: (List<SourceStatus>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val status = LinkedHashMap<String, SourceStatus>()
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var fused: FusedLocation? = null
    private var lastSpeedKmh: Double? = null
    private var lastSpeedEmitAt = 0L
    private var started = false

    private fun now() = SystemClock.elapsedRealtime()

    private fun post(s: Signal) = main.post { emit(s) }

    private fun setStatus(key: String, api: String, state: String, ok: Boolean) {
        main.post {
            status[key] = SourceStatus(key, api, state, ok)
            onStatus(status.values.toList())
        }
    }

    fun start() {
        if (started) return
        started = true
        startCarProperties()
        startVehicleSdk()
        startLocation()
    }

    // ---- AAOS: speed, gear, hazard ----
    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            when (v.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val kmh = ((v.value as? Float) ?: return) * 3.6
                    val t = now()
                    val last = lastSpeedKmh
                    // The emulator repeats the same value many times a second; forward changes and a 1 s heartbeat.
                    if (last == null || abs(kmh - last) >= 0.5 || t - lastSpeedEmitAt >= 1000) {
                        lastSpeedKmh = kmh
                        lastSpeedEmitAt = t
                        post(Signal.Speed(t, kmh))
                    }
                }
                VehiclePropertyIds.GEAR_SELECTION -> post(Signal.GearSel(now(), gearOf(v.value as? Int)))
                VehiclePropertyIds.HAZARD_LIGHTS_STATE -> post(Signal.Hazard(now(), (v.value as? Int) == 1))
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "property error $propId zone=$zone")
        }
    }

    private fun gearOf(v: Int?): Gear = when (v) {
        4 -> Gear.PARK
        2 -> Gear.REVERSE
        1 -> Gear.NEUTRAL
        8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096 -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    /**
     * Connects with a lifecycle listener: when the car service restarts, the car library would otherwise kill
     * this process ("Client is not handling it"). On every (re)connection the callbacks are registered again.
     */
    private fun startCarProperties() {
        try {
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) {
                    registerCarProperties(c)
                } else {
                    cpm = null
                    setStatus("속도", "AAOS PERF_VEHICLE_SPEED", "Car 서비스 재시작 대기", false)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "car properties", e)
            setStatus("속도", "AAOS PERF_VEHICLE_SPEED", "연결 실패: ${e.message}", false)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCarProperties(c: Car) {
        try {
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm = m
            fun reg(id: Int, key: String, api: String, rate: Float) {
                if (m.getCarPropertyConfig(id) == null) {
                    setStatus(key, api, "읽을 수 없음 (일반 앱 권한 밖)", false)
                    return
                }
                val ok = m.registerCallback(propCallback, id, rate)
                setStatus(key, api, if (ok) "구독 중" else "구독 실패", ok)
            }
            reg(VehiclePropertyIds.PERF_VEHICLE_SPEED, "속도", "AAOS PERF_VEHICLE_SPEED", CarPropertyManager.SENSOR_RATE_NORMAL)
            reg(VehiclePropertyIds.GEAR_SELECTION, "기어", "AAOS GEAR_SELECTION", CarPropertyManager.SENSOR_RATE_ONCHANGE)
            reg(VehiclePropertyIds.HAZARD_LIGHTS_STATE, "비상등", "AAOS HAZARD_LIGHTS_STATE", CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (e: Throwable) {
            Log.e(TAG, "car properties", e)
            setStatus("속도", "AAOS PERF_VEHICLE_SPEED", "연결 실패: ${e.message}", false)
        }
    }

    // ---- Pleos Vehicle SDK: driver door and belt ----
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            if (pos != null) post(Signal.DriverDoor(now(), pos > 0))
        }
        override fun onFailed(e: Exception) = setStatus("운전석 문", "Vehicle SDK registerDoorPosition", "실패: ${e.message}", false)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(value: Boolean?) {
            if (value != null) post(Signal.DriverBelt(now(), value))
        }
        override fun onFailed(e: Exception) = setStatus("운전석 벨트", "Vehicle SDK registerSeatBeltBuckledState", "실패: ${e.message}", false)
    }

    private fun startVehicleSdk() {
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            v.getDoor().registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), doorListener)
            setStatus("운전석 문", "Vehicle SDK registerDoorPosition", "구독 중", true)
            v.getSeat().registerSeatBeltBuckledState(SeatArea(SeatValue.ROW_1_LEFT), beltListener)
            setStatus("운전석 벨트", "Vehicle SDK registerSeatBeltBuckledState", "구독 중 (해제 순간만 사용)", true)
        } catch (e: Throwable) {
            Log.e(TAG, "vehicle sdk", e)
            setStatus("운전석 문", "Vehicle SDK", "초기화 실패: ${e.message}", false)
        }
    }

    // ---- Pleos FusedLocation ----
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val l = location ?: return
            // LocationData has no accuracy in meters; confidence is used as the error margin when it is 1 to 50.
            post(Signal.Location(now(), l.latitude, l.longitude, l.confidence.toDouble()))
        }
        override fun onError(e: Exception) = setStatus("위치", "FusedLocation", "오류: ${e.message}", false)
    }

    private fun startLocation() {
        try {
            val f = FusedLocation(context.applicationContext)
            f.initialize()
            fused = f
            if (!f.isSupported()) {
                setStatus("위치", "FusedLocation", "지원 안 함", false)
                return
            }
            f.registerFusedLocationCallback(locationListener)
            setStatus("위치", "FusedLocation registerFusedLocationCallback", "구독 중", true)
        } catch (e: Throwable) {
            Log.e(TAG, "fused location", e)
            setStatus("위치", "FusedLocation", "초기화 실패: ${e.message}", false)
        }
    }

    /** Attached to NaviHelper by the navigator: arrival is an intent signal. */
    val naviListener = object : NaviHelperEventListener {
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            post(Signal.NaviArrived(now(), info.name))
        }
    }

    fun naviStatus(ok: Boolean, state: String) = setStatus("내비 도착", "NaviHelper onDestinationArrived", state, ok)

    fun stop() {
        if (!started) return
        started = false
        runCatching { cpm?.unregisterCallback(propCallback) }
        runCatching { car?.disconnect() }
        runCatching { vehicle?.getDoor()?.unregisterDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), doorListener) }
        runCatching { vehicle?.getSeat()?.unregisterSeatBeltBuckledState(SeatArea(SeatValue.ROW_1_LEFT), beltListener) }
        runCatching { vehicle?.release() }
        runCatching { fused?.unregisterFusedLocationCallback(locationListener); fused?.release() }
    }

    companion object { const val TAG = "JKM-LIVE" }
}
