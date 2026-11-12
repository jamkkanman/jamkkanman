package app.jamkkanman.probe

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Step 1 check: calls every SDK and vehicle signal the app depends on and records
 * what the emulator actually returns. Results go to the screen and to logcat (tag JKM-PROBE).
 */
class ProbeRunner(private val context: Context) {

    enum class Status { WAIT, OK, FAIL, INFO }

    data class Item(val key: String, val title: String, val status: Status, val detail: String, val atMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private val startedAt = System.currentTimeMillis()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var navi: NaviHelper? = null
    private var fused: FusedLocation? = null

    private val lastDetail = HashMap<String, String>()

    fun put(key: String, title: String, status: Status, detail: String) {
        val item = Item(key, title, status, detail, System.currentTimeMillis() - startedAt)
        synchronized(lastDetail) {
            if (lastDetail[key] == "$status$detail") return
            lastDetail[key] = "$status$detail"
        }
        // Details can hold coordinates or recognized speech; release builds log only the outcome.
        if (app.jamkkanman.BuildConfig.DEBUG) Log.i(TAG, "[$status] $key | $title | $detail")
        else Log.i(TAG, "[$status] $key")
        main.post {
            val list = _items.value.toMutableList()
            val i = list.indexOfFirst { it.key == key }
            if (i >= 0) list[i] = item else list.add(item)
            _items.value = list
        }
    }

    fun runAll() {
        probeVehicle()
        probeCarProperties()
        probeFusedLocation()
        probeTts()
        probeNavi()
        // STT starts after TTS has had time to finish, so the microphone does not hear the test sentence.
        main.postDelayed({ probeStt() }, 6000)
    }

    private fun err(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

    // ---------------- Vehicle SDK ----------------
    private fun probeVehicle() {
        val v = try {
            Vehicle(context.applicationContext).also { it.initialize() }
        } catch (e: Throwable) {
            put("veh.init", "Vehicle SDK 초기화", Status.FAIL, err(e)); return
        }
        vehicle = v
        put("veh.init", "Vehicle SDK 초기화", Status.OK, "initialize() 반환")

        val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
        val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
        val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)

        safely("veh.door.get", "운전석 문 읽기 getCurrentDoorPosition") {
            v.getDoor().getCurrentDoorPosition(driverDoor,
                { put("veh.door.get", "운전석 문 읽기 getCurrentDoorPosition", Status.OK, "값=$it") },
                { put("veh.door.get", "운전석 문 읽기 getCurrentDoorPosition", Status.FAIL, err(it)) })
        }
        safely("veh.door.reg", "운전석 문 구독 registerDoorPosition") {
            v.getDoor().registerDoorPosition(driverDoor, object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) =
                    put("veh.door.reg", "운전석 문 구독 registerDoorPosition", Status.OK, "콜백 값=$pos")
                override fun onFailed(e: Exception) =
                    put("veh.door.reg", "운전석 문 구독 registerDoorPosition", Status.FAIL, err(e))
            })
            put("veh.door.reg", "운전석 문 구독 registerDoorPosition", Status.WAIT, "등록됨, 콜백 대기")
        }
        safely("veh.belt.get", "운전석 안전벨트 읽기") {
            v.getSeat().getCurrentSeatBeltBuckledState(driverSeat,
                { put("veh.belt.get", "운전석 안전벨트 읽기", Status.OK, "값=$it") },
                { put("veh.belt.get", "운전석 안전벨트 읽기", Status.FAIL, err(it)) })
        }
        safely("veh.belt.reg", "운전석 안전벨트 구독") {
            v.getSeat().registerSeatBeltBuckledState(driverSeat, object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(value: Boolean?) =
                    put("veh.belt.reg", "운전석 안전벨트 구독", Status.OK, "콜백 값=$value")
                override fun onFailed(e: Exception) =
                    put("veh.belt.reg", "운전석 안전벨트 구독", Status.FAIL, err(e))
            })
            put("veh.belt.reg", "운전석 안전벨트 구독", Status.WAIT, "등록됨, 콜백 대기")
        }
        safely("veh.occ.get", "조수석 점유 읽기") {
            v.getSeat().getCurrentSeatOccupancy(passengerSeat,
                { put("veh.occ.get", "조수석 점유 읽기", Status.OK, "값=$it") },
                { put("veh.occ.get", "조수석 점유 읽기", Status.FAIL, err(it)) })
        }
        safely("veh.gear.get", "기어 읽기 (Vehicle SDK Gear)") {
            v.getGear().getCurrentGearSelectionState(
                { put("veh.gear.get", "기어 읽기 (Vehicle SDK Gear)", Status.OK, "값=$it") },
                { put("veh.gear.get", "기어 읽기 (Vehicle SDK Gear)", Status.FAIL, err(it)) })
        }
        safely("veh.gear.reg", "기어 구독 (Vehicle SDK Gear)") {
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) =
                    put("veh.gear.reg", "기어 구독 (Vehicle SDK Gear)", Status.OK, "콜백 값=$gear")
                override fun onFailed(e: Exception) =
                    put("veh.gear.reg", "기어 구독 (Vehicle SDK Gear)", Status.FAIL, err(e))
            })
            put("veh.gear.reg", "기어 구독 (Vehicle SDK Gear)", Status.WAIT, "등록됨, 콜백 대기")
        }
        safely("veh.turn.reg", "방향지시등 구독") {
            v.getTurnSignal().getCurrentTurnSignalState(
                { put("veh.turn.get", "방향지시등 읽기", Status.OK, "값=$it") },
                { put("veh.turn.get", "방향지시등 읽기", Status.FAIL, err(it)) })
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) =
                    put("veh.turn.reg", "방향지시등 구독", Status.OK, "콜백 값=$value")
                override fun onFailed(e: Exception) =
                    put("veh.turn.reg", "방향지시등 구독", Status.FAIL, err(e))
            })
        }
    }

    // ---------------- AAOS CarPropertyManager ----------------
    private fun probeCarProperties() {
        try {
            // With a lifecycle listener the app survives a car service restart instead of being killed.
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) probeCarProperties(c) else put("aaos.car", "AAOS Car 연결", Status.INFO, "Car 서비스 재시작 대기")
            }
        } catch (e: Throwable) {
            put("aaos.car", "AAOS Car 연결", Status.FAIL, err(e))
        }
    }

    private fun probeCarProperties(c: Car) {
        val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (cpm == null) { put("aaos.car", "AAOS Car 연결", Status.FAIL, "PROPERTY_SERVICE 없음"); return }
        put("aaos.car", "AAOS Car 연결", Status.OK, "CarPropertyManager 확보")

        val props = listOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED to "속도 PERF_VEHICLE_SPEED",
            VehiclePropertyIds.GEAR_SELECTION to "기어 GEAR_SELECTION",
            VehiclePropertyIds.CURRENT_GEAR to "기어 CURRENT_GEAR",
            VehiclePropertyIds.HAZARD_LIGHTS_STATE to "비상등 HAZARD_LIGHTS_STATE",
            VehiclePropertyIds.HAZARD_LIGHTS_SWITCH to "비상등 스위치 HAZARD_LIGHTS_SWITCH",
            VehiclePropertyIds.TURN_SIGNAL_STATE to "방향지시등 TURN_SIGNAL_STATE",
            VehiclePropertyIds.PARKING_BRAKE_ON to "주차 브레이크 PARKING_BRAKE_ON",
            VehiclePropertyIds.IGNITION_STATE to "시동 IGNITION_STATE",
        )
        for ((id, title) in props) {
            val key = "aaos.$id"
            try {
                val config = cpm.getCarPropertyConfig(id)
                if (config == null) {
                    put(key, title, Status.FAIL, "config 없음 (미지원 또는 권한 없음)")
                    continue
                }
                val value: CarPropertyValue<*>? = try {
                    cpm.getProperty<Any>(id, 0)
                } catch (e: Throwable) {
                    put(key, title, Status.FAIL, "config 있음, 읽기 실패 ${err(e)}"); null
                }
                if (value != null) {
                    put(key, title, Status.OK, "값=${value.value} 상태=${value.status} 접근=${config.access}")
                }
                val cb = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: CarPropertyValue<*>) =
                        put("$key.cb", "$title 변경 콜백", Status.OK, "값=${v.value}")
                    override fun onErrorEvent(propId: Int, zone: Int) =
                        put("$key.cb", "$title 변경 콜백", Status.FAIL, "onErrorEvent zone=$zone")
                }
                val ok = cpm.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                if (!ok) put("$key.cb", "$title 변경 콜백", Status.FAIL, "registerCallback false")
            } catch (e: Throwable) {
                put(key, title, Status.FAIL, err(e))
            }
        }
    }

    // ---------------- FusedLocation ----------------
    private fun probeFusedLocation() {
        try {
            val f = FusedLocation(context.applicationContext)
            f.initialize()
            fused = f
            put("loc.init", "FusedLocation 초기화", Status.OK, "isSupported=${f.isSupported()}")
            val listener = object : FusedLocationListener {
                override fun onChanged(data: LocationData?) =
                    put("loc.cb", "FusedLocation 위치 콜백", Status.OK,
                        "lat=${data?.latitude} lon=${data?.longitude} conf=${data?.confidence} heading=${data?.heading}")
                override fun onError(e: Exception) =
                    put("loc.cb", "FusedLocation 위치 콜백", Status.FAIL, err(e))
            }
            f.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(data: LocationData?) =
                    put("loc.last", "FusedLocation 마지막 위치", Status.OK, "lat=${data?.latitude} lon=${data?.longitude} conf=${data?.confidence}")
                override fun onError(e: Exception) =
                    put("loc.last", "FusedLocation 마지막 위치", Status.FAIL, err(e))
            })
            f.registerFusedLocationCallback(listener)
            put("loc.cb", "FusedLocation 위치 콜백", Status.WAIT, "등록됨, 콜백 대기")
        } catch (e: Throwable) {
            put("loc.init", "FusedLocation 초기화", Status.FAIL, err(e))
        }
    }

    // ---------------- TextToSpeech ----------------
    private fun probeTts() {
        try {
            val t = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            tts = t
            // The SDK needs initialize() before addEventListener(); the other order throws.
            t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    put("tts.init", "TTS 초기화", Status.OK, "onSuccess")
                    t.addEventListener(object : EventListener {
                        override fun onStart() = put("tts.speak", "TTS 발화 (ON_DEVICE, ko-KR)", Status.OK, "onStart")
                        override fun onReady() = put("tts.ready", "TTS onReady", Status.INFO, "onReady")
                        override fun onDone() = put("tts.done", "TTS 발화 완료", Status.OK, "onDone")
                        override fun onError(e: ai.pleos.playground.tts.constant.ErrorType) =
                            put("tts.speak", "TTS 발화 (ON_DEVICE, ko-KR)", Status.FAIL, "onError $e")
                        override fun onStop() = put("tts.stop", "TTS onStop", Status.INFO, "onStop")
                        override fun onUpdatedRms(rms: Double) {}
                    })
                    t.setLocale(Locale.KOREA)
                    put("tts.speak", "TTS 발화 (ON_DEVICE, ko-KR)", Status.WAIT, "speak 호출")
                    t.speak("잠깐만 음성 점검입니다.", true)
                }
                override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) =
                    put("tts.init", "TTS 초기화", Status.FAIL, "onFailure $e")
            })
        } catch (e: Throwable) {
            put("tts.init", "TTS 초기화", Status.FAIL, err(e))
        }
    }

    // ---------------- SpeechToText ----------------
    private fun probeStt() {
        try {
            val s = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            stt = s
            s.addListener(object : ResultListener {
                override fun onUpdated(text: String, completed: Boolean) =
                    put("stt.result", "STT 인식 결과", Status.OK, "\"$text\" completed=$completed")
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() = put("stt.start", "STT 인식 시작", Status.OK, "onStartedRecognition")
                override fun onEndedRecognition() = put("stt.end", "STT 인식 종료", Status.INFO, "onEndedRecognition")
                override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) =
                    put("stt.result", "STT 인식 결과", Status.FAIL, "onError $e")
                override fun onReady() = put("stt.ready", "STT onReady", Status.INFO, "onReady")
                override fun onUpdatedRms(rms: Double) {}
            })
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() {
                    put("stt.init", "STT 초기화", Status.OK, "onSuccess")
                    s.setLocale(Locale.KOREA)
                    s.request()
                    put("stt.result", "STT 인식 결과", Status.WAIT, "request 호출, 마이크 입력 대기")
                }
                override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) =
                    put("stt.init", "STT 초기화", Status.FAIL, "onFailure $e")
            })
        } catch (e: Throwable) {
            put("stt.init", "STT 초기화", Status.FAIL, err(e))
        }
    }

    /**
     * Feeds a recorded 16 kHz 16-bit mono WAV from assets to STT with sendAudio(), 3,200 bytes per 100 ms,
     * the documented ON_DEVICE path that does not need the car microphone.
     */
    fun sttFromAsset(asset: String, withRequest: Boolean) {
        val s = stt ?: run { put("stt.file", "STT 녹음 파일 입력", Status.FAIL, "STT 미초기화"); return }
        Thread {
            try {
                runCatching { s.stop() }
                Thread.sleep(300)
                if (withRequest) s.request()
                val wav = context.assets.open(asset).use { it.readBytes() }
                val pcm = app.jamkkanman.live.Listener.pcmOf(wav)
                put("stt.file", "STT 녹음 파일 입력", Status.WAIT, "$asset ${pcm.size}바이트 전송 시작 (request=$withRequest)")
                var i = 0
                while (i < pcm.size) {
                    val end = minOf(i + 3200, pcm.size)
                    s.sendAudio(pcm.copyOfRange(i, end))
                    i = end
                    Thread.sleep(100)
                }
                // Trailing silence so end-point detection can close the utterance.
                repeat(5) { s.sendAudio(ByteArray(3200)); Thread.sleep(100) }
                s.completeAudioSend()
                put("stt.file", "STT 녹음 파일 입력", Status.OK, "$asset 전송 완료, completeAudioSend 호출")
            } catch (e: Throwable) {
                put("stt.file", "STT 녹음 파일 입력", Status.FAIL, err(e))
            }
        }.start()
    }

    // ---------------- NaviHelper ----------------
    private fun probeNavi() {
        try {
            val n = NaviHelper(context.applicationContext)
            navi = n
            n.initialize({
                put("navi.init", "NaviHelper 연결", Status.OK, "onConnected")
                n.addListener(naviListener)
                n.getRouteStateInfo()
                n.getCurrentLocationInfo()
                n.getDestinationInfo()
            }, { e ->
                put("navi.init", "NaviHelper 연결", Status.FAIL, err(e))
            })
            put("navi.init", "NaviHelper 연결", Status.WAIT, "initialize 호출")
        } catch (e: Throwable) {
            put("navi.init", "NaviHelper 연결", Status.FAIL, err(e))
        }
    }

    /** Asks the navigation app to route to a test point built only from public data fields. */
    fun requestTestRoute(lat: Double, lon: Double, name: String, poiId: String, address: String) {
        val n = navi ?: run { put("navi.route", "NaviHelper 경로 변경", Status.FAIL, "NaviHelper 미연결"); return }
        try {
            n.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiId = poiId, poiName = name, poiSubId = "0", address = address))
            put("navi.route", "NaviHelper 경로 변경 requestRoute", Status.WAIT, "요청 보냄: $name ($poiId)")
        } catch (e: Throwable) {
            put("navi.route", "NaviHelper 경로 변경 requestRoute", Status.FAIL, err(e))
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) =
            put("navi.route", "NaviHelper 경로 변경 requestRoute", Status.OK, "onRouteStarted $info")
        override fun onRoutingCompleted() = put("navi.routing", "NaviHelper 경로 탐색 완료", Status.OK, "onRoutingCompleted")
        override fun onError(code: NaviErrorCode) = put("navi.err", "NaviHelper 오류", Status.FAIL, "onError $code")
        override fun onDestinationArrived(info: DestinationArrivedInfo) =
            put("navi.arrived", "NaviHelper 목적지 도착", Status.OK, "onDestinationArrived $info")
        override fun onNavigationStatus(status: NavigationStatus) =
            put("navi.status", "NaviHelper 내비 상태", Status.INFO, "$status")
        override fun onRouteStateInfo(info: RouteStateInfo) =
            put("navi.state", "NaviHelper 경로 상태", Status.OK, "$info")
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) =
            put("navi.loc", "NaviHelper 현재 위치", Status.OK, "$info")
        override fun onRouteCancelled() = put("navi.cancel", "NaviHelper 경로 취소", Status.INFO, "onRouteCancelled")
    }

    private inline fun safely(key: String, title: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { put(key, title, Status.FAIL, err(e)) }
    }

    fun release() {
        runCatching { stt?.stop(); stt?.release() }
        runCatching { tts?.stop(); tts?.release() }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { fused?.release() }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
    }

    companion object { const val TAG = "JKM-PROBE" }
}
