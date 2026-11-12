package app.jamkkanman.core

/**
 * Stop state machine (plan section 5).
 *
 * | phase      | entered when                                                        |
 * |------------|---------------------------------------------------------------------|
 * | DRIVING    | speed above 5 km/h for 5 s; every per-stop state is reset            |
 * | STOPPED    | speed 0 for 3 s, or gear P (P needs a standstill)                    |
 * | INTENT     | while stopped: gear P, hazard lights on, or navigation arrival      |
 * | ALIGHTING  | while stopped: driver door open, or the driver belt was released    |
 *
 * A traffic light wait stays in STOPPED: gear D, door closed, no hazard, no arrival.
 * The belt counts only as a release (buckled, then unbuckled), so a sensor that always reads
 * "unbuckled" never looks like a driver getting out.
 */
enum class Phase(val label: String) {
    UNKNOWN("확인 중"),
    DRIVING("주행"),
    STOPPED("멈춤"),
    INTENT("세우려는 의도"),
    ALIGHTING("내리기 직전"),
}

data class Transition(val t: Long, val from: Phase, val to: Phase, val reason: String)

data class StopConfig(
    val stopSpeedKmh: Double = 0.5,
    val stopHoldMs: Long = 3_000,
    val driveSpeedKmh: Double = 5.0,
    val driveHoldMs: Long = 5_000,
    /** How long a navigation arrival keeps counting as an intent signal. */
    val arrivalValidMs: Long = 120_000,
)

class StopMachine(private val cfg: StopConfig = StopConfig()) {
    var phase: Phase = Phase.UNKNOWN; private set
    var speedKmh: Double? = null; private set
    var gear: Gear = Gear.UNKNOWN; private set
    var hazard: Boolean? = null; private set
    var doorOpen: Boolean = false; private set
    var belt: Boolean? = null; private set
    var arrivedAt: Long? = null; private set
    /** Increments on every new stop, so per-stop rules can tell stops apart. */
    var stopId: Int = 0; private set
    /** True when the current stop began before any driving was seen (app started in a parked car). */
    var stopStartedAtLaunch: Boolean = false; private set

    private var zeroSince: Long? = null
    private var fastSince: Long? = null
    private var beltReleasedAt: Long? = null

    fun onSignal(s: Signal): List<Transition> {
        when (s) {
            is Signal.Speed -> {
                speedKmh = s.kmh
                when {
                    s.kmh < cfg.stopSpeedKmh -> { if (zeroSince == null) zeroSince = s.t; fastSince = null }
                    s.kmh > cfg.driveSpeedKmh -> { if (fastSince == null) fastSince = s.t; zeroSince = null }
                    else -> { zeroSince = null; fastSince = null }
                }
            }
            is Signal.GearSel -> gear = s.gear
            is Signal.Hazard -> hazard = s.on
            is Signal.DriverDoor -> doorOpen = s.open
            is Signal.DriverBelt -> {
                if (belt == true && !s.buckled) beltReleasedAt = s.t
                belt = s.buckled
            }
            is Signal.NaviArrived -> arrivedAt = s.t
            else -> Unit
        }
        return evaluate(s.t)
    }

    fun onTick(now: Long): List<Transition> = evaluate(now)

    private fun isFast(now: Long) = fastSince?.let { now - it >= cfg.driveHoldMs } == true

    private fun stoppedReason(now: Long): String? {
        val z = zeroSince
        return when {
            gear == Gear.PARK -> "기어 P"
            z != null && now - z >= cfg.stopHoldMs -> "속도 0이 ${cfg.stopHoldMs / 1000}초"
            // Without any speed signal, an open door means a stop (doors do not open while driving).
            speedKmh == null && doorOpen -> "속도 신호 없음, 문 열림"
            else -> null
        }
    }

    private fun intentReason(now: Long): String? {
        val a = arrivedAt
        return when {
            gear == Gear.PARK -> "기어 P"
            hazard == true -> "비상등 켜짐"
            a != null && now - a <= cfg.arrivalValidMs -> "내비 목적지 도착"
            else -> null
        }
    }

    private fun alightReason(): String? = when {
        doorOpen -> "운전석 문 열림"
        beltReleasedAt != null -> "운전석 벨트 해제"
        else -> null
    }

    private fun evaluate(now: Long): List<Transition> {
        val out = ArrayList<Transition>(3)
        fun go(to: Phase, why: String) {
            out.add(Transition(now, phase, to, why))
            phase = to
        }

        if (isFast(now) && phase != Phase.DRIVING) {
            go(Phase.DRIVING, "속도 ${cfg.driveSpeedKmh.toInt()}km/h 초과 ${cfg.driveHoldMs / 1000}초")
            arrivedAt = null
            beltReleasedAt = null
            return out
        }
        if (phase == Phase.DRIVING || phase == Phase.UNKNOWN) {
            val why = stoppedReason(now) ?: return out
            stopStartedAtLaunch = phase == Phase.UNKNOWN
            stopId++
            go(Phase.STOPPED, why)
        }
        if (phase == Phase.STOPPED) {
            intentReason(now)?.let { go(Phase.INTENT, it) }
        }
        if (phase == Phase.STOPPED || phase == Phase.INTENT) {
            alightReason()?.let { go(Phase.ALIGHTING, it) }
        }
        return out
    }

    val isStopped: Boolean get() = phase == Phase.STOPPED || phase == Phase.INTENT || phase == Phase.ALIGHTING
}
