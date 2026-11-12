package app.jamkkanman.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Wall clock for opening hours and the 30-day silence list. Replays pass a fixed clock. */
fun interface WallClock {
    fun nowMs(monoT: Long): Long
}

/** What the engine asks the app to do. */
sealed class Effect {
    /** [triggerT] is the time of the signal that caused this sentence, for reaction-time measurement. */
    data class Speak(val kind: Utterance, val text: String, val hit: ZoneHit?, val triggerT: Long) : Effect()
    /** Start listening for an answer after the sentence is spoken. */
    data class Listen(val windowMs: Long) : Effect()
    data class Route(val option: ParkingOption) : Effect()
    data class Log(val entry: LogEntry) : Effect()
}

data class LogEntry(val t: Long, val phase: Phase, val event: String, val decision: String)

data class EngineSnapshot(
    val t: Long,
    val phase: Phase,
    val stopId: Int,
    val speedKmh: Double?,
    val gear: Gear,
    val hazard: Boolean?,
    val doorOpen: Boolean,
    val belt: Boolean?,
    val location: LatLon?,
    val accuracyM: Double?,
    val judgment: Judgment?,
    val alertsThisStop: Int,
    val awaitingReply: Boolean,
    val lastSpoken: String?,
    val parking: List<ParkingOption>,
    val lastReply: Reply?,
)

data class EngineConfig(
    val stop: StopConfig = StopConfig(),
    val policy: PolicyConfig = PolicyConfig(),
    val listenWindowMs: Long = 7_000,
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
)

/**
 * Turns vehicle signals into at most two sentences per stop. Deterministic: the same signal sequence and
 * the same clock give the same effects. No network and no model calls.
 */
class Engine(
    private val judge: ZoneJudge,
    private val parking: ParkingFinder,
    private val clock: WallClock,
    private val cfg: EngineConfig = EngineConfig(),
    silence: SilenceStore = InMemorySilenceStore(),
) {
    private val machine = StopMachine(cfg.stop)
    private val policy = SpeechPolicy(cfg.policy, silence)

    private var location: LatLon? = null
    private var accuracy: Double? = null
    private var judgment: Judgment? = null
    private var lastT = 0L

    // Per-stop state
    private var firstAlertHit: ZoneHit? = null
    private var awaitingReply = false
    private var answered = false
    private var remindAt: Long? = null
    private var deferred: Pair<Long, () -> List<Effect>>? = null
    private var waitingForLocation: Triple<Utterance, Transition, Boolean>? = null
    private var lastSpoken: String? = null
    private var lastReply: Reply? = null
    private var parkingShown: List<ParkingOption> = emptyList()

    val phase: Phase get() = machine.phase

    fun snapshot(): EngineSnapshot = EngineSnapshot(
        t = lastT, phase = machine.phase, stopId = machine.stopId, speedKmh = machine.speedKmh, gear = machine.gear,
        hazard = machine.hazard, doorOpen = machine.doorOpen, belt = machine.belt, location = location,
        accuracyM = accuracy, judgment = judgment, alertsThisStop = policy.alertsThisStop,
        awaitingReply = awaitingReply, lastSpoken = lastSpoken, parking = parkingShown, lastReply = lastReply,
    )

    private fun wall(t: Long): ZonedDateTime = Instant.ofEpochMilli(clock.nowMs(t)).atZone(cfg.zone)

    private fun log(t: Long, event: String, decision: String) =
        Effect.Log(LogEntry(t, machine.phase, event, decision))

    fun onSignal(s: Signal): List<Effect> {
        lastT = s.t
        val out = ArrayList<Effect>()
        when (s) {
            is Signal.Location -> {
                location = s.pos
                accuracy = s.accuracyM
                if (machine.isStopped) {
                    judgment = judge.judge(s.pos, s.accuracyM)
                    // A trigger that arrived before the first location fix is judged now.
                    waitingForLocation?.let { (kind, tr, confirm) ->
                        waitingForLocation = null
                        out.addAll(maybeAlert(kind, tr.copy(t = s.t), confirm))
                    }
                }
            }
            is Signal.NaviVoice -> policy.lastNaviVoiceT = s.t
            is Signal.ReplyText -> {
                out.addAll(onReply(s))
                return out
            }
            else -> Unit
        }
        val transitions = machine.onSignal(s)
        val quiet = s is Signal.Location || (s is Signal.Speed && transitions.isEmpty())
        if (!quiet) out.add(log(s.t, s.describe(), if (transitions.isEmpty()) "상태 그대로" else ""))
        transitions.forEach { out.addAll(onTransition(it)) }
        return out
    }

    fun tick(now: Long): List<Effect> {
        lastT = now
        val out = ArrayList<Effect>()
        machine.onTick(now).forEach { out.addAll(onTransition(it)) }
        deferred?.let { (at, action) ->
            if (now >= at) { deferred = null; out.addAll(action()) }
        }
        remindAt?.let { at ->
            if (now >= at) {
                remindAt = null
                val hit = firstAlertHit
                if (machine.isStopped && hit != null) {
                    if (policy.alertsThisStop < cfg.policy.maxAlertsPerStop) {
                        policy.recordAlert(hit, now, machine.stopId)
                        out.add(speak(Utterance.REMIND, Phrases.REMIND, hit, now))
                        out.add(log(now, "50초 경과, 아직 정차 중", "한 번 더 안내"))
                    } else {
                        out.add(log(now, "50초 경과, 아직 정차 중", "말하지 않음: 정차당 ${cfg.policy.maxAlertsPerStop}번 다 씀"))
                    }
                }
            }
        }
        return out
    }

    private fun onTransition(tr: Transition): List<Effect> {
        val out = ArrayList<Effect>()
        out.add(log(tr.t, "${tr.from.label} -> ${tr.to.label}", tr.reason))
        when (tr.to) {
            Phase.DRIVING -> resetStop()
            Phase.STOPPED -> {
                policy.newStop(machine.stopId)
                location?.let { judgment = judge.judge(it, accuracy) }
                val j = judgment
                val desc = when {
                    j == null -> "위치 없음"
                    j.top != null -> "${j.top!!.describe()} (법정 %.0fm, ${j.top!!.verdict.label})".format(j.top!!.facility.type.legalM)
                    j.nearest != null -> "가장 가까운 ${j.nearest.first.type.label} %.1fm, 금지 거리 밖".format(j.nearest.second)
                    else -> "주변 시설 없음"
                }
                out.add(log(tr.t, "판정 미리 계산 (%.3f ms)".format((j?.elapsedNanos ?: 0) / 1e6), "$desc, 말하지 않음"))
            }
            Phase.INTENT -> out.addAll(maybeAlert(Utterance.INTENT_ALERT, tr))
            Phase.ALIGHTING -> {
                when {
                    answered -> out.add(log(tr.t, "내리기 직전", "말하지 않음: 이미 응답함"))
                    firstAlertHit == null -> out.addAll(maybeAlert(Utterance.ALIGHT_ALERT, tr))
                    else -> out.addAll(maybeAlert(Utterance.ALIGHT_ALERT, tr, confirm = true))
                }
            }
            Phase.UNKNOWN -> Unit
        }
        return out
    }

    private fun maybeAlert(kind: Utterance, tr: Transition, confirm: Boolean = false): List<Effect> {
        if (machine.stopStartedAtLaunch) {
            return listOf(log(tr.t, kind.label, "말하지 않음: 앱 시작 때부터 서 있던 차"))
        }
        val loc = location ?: run {
            waitingForLocation = Triple(kind, tr, confirm)
            return listOf(log(tr.t, kind.label, "위치를 기다림"))
        }
        val j = judge.judge(loc, accuracy).also { judgment = it }
        val hit = j.top
        val decision = policy.check(hit, tr.t, clock.nowMs(tr.t), parking.nearestDistance(loc), machine.stopId)
        return when (decision) {
            is PolicyDecision.Silent -> listOf(log(tr.t, kind.label, "말하지 않음: ${decision.reason}"))
            is PolicyDecision.Defer -> {
                deferred = decision.untilT to { maybeAlert(kind, tr.copy(t = decision.untilT), confirm) }
                listOf(log(tr.t, kind.label, "내비 음성 직후라 ${(decision.untilT - tr.t) / 1000.0}초 뒤로 미룸"))
            }
            PolicyDecision.Speak -> {
                hit!!
                policy.recordAlert(hit, tr.t, machine.stopId)
                if (firstAlertHit == null) firstAlertHit = hit
                awaitingReply = true
                parkingShown = parking.options(loc, wall(tr.t))
                val text = when {
                    kind == Utterance.INTENT_ALERT -> Phrases.intentAlert(hit)
                    confirm -> Phrases.alightConfirm(hit)
                    else -> Phrases.alightFirst(hit)
                }
                listOf(
                    log(tr.t, "${kind.label} (${tr.reason})", "${hit.describe()}, ${hit.verdict.label}"),
                    speak(kind, text, hit, tr.t),
                    Effect.Listen(cfg.listenWindowMs),
                )
            }
        }
    }

    private fun speak(kind: Utterance, text: String, hit: ZoneHit?, triggerT: Long): Effect.Speak {
        lastSpoken = text
        return Effect.Speak(kind, text, hit, triggerT)
    }

    private fun onReply(s: Signal.ReplyText): List<Effect> {
        val reply = ReplyClassifier.classify(s.text)
        lastReply = reply
        val out = ArrayList<Effect>()
        val hit = firstAlertHit
        if (hit == null || !machine.isStopped) {
            out.add(log(s.t, s.describe(), "처리 안 함: 응답할 안내가 없음"))
            return out
        }
        out.add(log(s.t, s.describe(), "분류: ${reply.label}"))
        when (reply) {
            Reply.YES -> {
                answered = true; awaitingReply = false
                policy.recordAnswered(hit, s.t, machine.stopId)
                val loc = location ?: hit.facility.pos
                val options = parking.options(loc, wall(s.t))
                parkingShown = options
                val best = options.firstOrNull()
                if (best == null) {
                    out.add(speak(Utterance.PARKING, Phrases.NO_PARKING, hit, s.t))
                } else {
                    out.add(speak(Utterance.PARKING, Phrases.parking(best), hit, s.t))
                    out.add(Effect.Route(best))
                    out.add(log(s.t, "주차장 선택", "${best.lot.name} %.0fm, 내비 목적지 변경 요청".format(best.distanceM)))
                }
            }
            Reply.SOON -> {
                answered = true; awaitingReply = false
                policy.recordAnswered(hit, s.t, machine.stopId)
                remindAt = s.t + cfg.policy.remindAfterMs
                out.add(log(s.t, "금방 가", "${cfg.policy.remindAfterMs / 1000}초 뒤에도 서 있으면 한 번만 더 안내"))
            }
            Reply.IGNORE -> {
                answered = true; awaitingReply = false
                policy.recordAnswered(hit, s.t, machine.stopId)
                policy.silencePlace(hit, clock.nowMs(s.t))
                out.add(speak(Utterance.ACK, Phrases.ACK_IGNORE, hit, s.t))
                out.add(log(s.t, "괜찮아", "${hit.facility.name.ifEmpty { hit.facility.type.label }} 30일 침묵 목록에 추가"))
            }
            Reply.UNKNOWN -> out.add(log(s.t, "응답 분류 실패", "다시 묻지 않음, 화면 버튼 유지"))
        }
        return out
    }

    private fun resetStop() {
        firstAlertHit = null
        awaitingReply = false
        answered = false
        remindAt = null
        deferred = null
        waitingForLocation = null
        parkingShown = emptyList()
        judgment = null
        lastReply = null
    }
}
