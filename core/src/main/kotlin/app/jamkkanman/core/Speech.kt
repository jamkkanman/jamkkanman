package app.jamkkanman.core

import kotlin.math.roundToInt

/** What the app says. Only the first three are unsolicited alerts and count toward the per-stop budget. */
enum class Utterance(val label: String, val isAlert: Boolean) {
    INTENT_ALERT("첫 안내", true),
    ALIGHT_ALERT("내리기 직전 안내", true),
    REMIND("한 번 더 안내", true),
    PARKING("주차장 안내", false),
    ACK("응답 확인", false),
}

enum class Reply(val label: String) { YES("응"), SOON("금방 가"), IGNORE("괜찮아"), UNKNOWN("알 수 없음") }

/**
 * Keyword rules for the driver's answer. Checked in this order: "괜찮아" family, then "금방 가" family,
 * then "응" family. Recognition can turn "응, 안내해 줘" into "응 안 해줘", so a plain negation like
 * "안 해" is not treated as a refusal; only explicit words such as 괜찮아 or 됐어 are.
 */
object ReplyClassifier {
    private val ignore = listOf("괜찮", "됐어", "됐다", "필요없", "필요 없", "안해도", "안 해도", "하지마", "하지 마", "그냥둬", "그냥 둬")
    private val soon = listOf("금방", "잠깐", "곧", "바로가", "바로 가", "갈게", "갈께")
    private val yes = listOf("응", "어", "네", "예", "그래", "좋아", "안내", "부탁", "가자", "주차장", "오케이", "ok")

    fun classify(text: String): Reply {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return Reply.UNKNOWN
        val compact = t.replace(" ", "")
        if (ignore.any { compact.contains(it.replace(" ", "")) }) return Reply.IGNORE
        if (soon.any { compact.contains(it.replace(" ", "")) }) return Reply.SOON
        if (yes.any { kw -> compact.startsWith(kw) || compact.contains(kw) && kw.length >= 2 }) return Reply.YES
        return Reply.UNKNOWN
    }
}

/** Every sentence the app speaks. One sentence each, reason first, then the alternative. */
object Phrases {
    private fun meters(r: Double) = "${r.roundToInt()}미터"

    fun intentAlert(hit: ZoneHit): String {
        val f = hit.facility
        if (f.type == ZoneType.CHILD_ZONE) {
            return "여기는 어린이보호구역이라 잠깐 정차도 과태료 12만원입니다. 근처 공영주차장으로 안내할까요?"
        }
        val legal = meters(f.type.legalM)
        return when (hit.verdict) {
            Verdict.INSIDE -> "${f.type.label} $legal 안이에요. 잠깐 정차도 안 되는 곳입니다. 근처 공영주차장으로 안내할까요?"
            Verdict.NEAR -> "${f.type.label} $legal 가까이일 수 있어요. 잠깐 정차도 안 되는 곳일 수 있습니다. 근처 공영주차장으로 안내할까요?"
        }
    }

    /** First alert at the alighting moment (no earlier alert in this stop). */
    fun alightFirst(hit: ZoneHit): String {
        val f = hit.facility
        if (f.type == ZoneType.CHILD_ZONE) return "내리시기 전에, 여기는 어린이보호구역입니다. 과태료 12만원이에요."
        val legal = meters(f.type.legalM)
        return when (hit.verdict) {
            Verdict.INSIDE -> "내리시기 전에, 여기는 ${f.type.label} $legal 안입니다."
            Verdict.NEAR -> "내리시기 전에, 여기는 ${f.type.label} $legal 가까이일 수 있어요."
        }
    }

    /** Short confirmation when the first alert got no answer. */
    fun alightConfirm(hit: ZoneHit): String {
        val f = hit.facility
        if (f.type == ZoneType.CHILD_ZONE) return "내리시기 전에, 여기는 어린이보호구역입니다. 과태료 12만원이에요."
        return "내리시기 전에, 여기는 ${f.type.label} ${meters(f.type.legalM)} ${if (hit.verdict == Verdict.INSIDE) "안" else "근처"}입니다. 과태료 ${f.type.fineText}이에요."
    }

    const val REMIND = "아직 정차 중입니다. 출발하거나 주차장으로 옮겨 주세요."

    fun parking(option: ParkingOption): String {
        val d = ((option.distanceM / 10.0).roundToInt() * 10).coerceAtLeast(10)
        return "${option.lot.name}, ${d}미터입니다."
    }

    const val NO_PARKING = "지금 운영 중인 공영주차장을 찾지 못했어요."
    const val ACK_IGNORE = "알겠어요. 이곳은 30일 동안 조용히 있을게요."
}

data class PolicyConfig(
    val maxAlertsPerStop: Int = 2,
    val placeCooldownMs: Long = 10 * 60_000L,
    val naviQuietMs: Long = 5_000L,
    val parkingQuietM: Double = 30.0,
    val silenceDays: Long = 30,
    val remindAfterMs: Long = 50_000L,
)

/** Places the driver silenced with "괜찮아", with an expiry in wall-clock milliseconds. */
interface SilenceStore {
    fun until(placeKey: String): Long?
    fun put(placeKey: String, untilWallMs: Long)
}

class InMemorySilenceStore : SilenceStore {
    private val map = HashMap<String, Long>()
    override fun until(placeKey: String): Long? = map[placeKey]
    override fun put(placeKey: String, untilWallMs: Long) { map[placeKey] = untilWallMs }
}

sealed class PolicyDecision {
    data object Speak : PolicyDecision()
    data class Defer(val untilT: Long) : PolicyDecision()
    data class Silent(val reason: String) : PolicyDecision()
}

/** Who gets to talk, and when (plan section 5, utterance policy). */
class SpeechPolicy(val cfg: PolicyConfig = PolicyConfig(), private val silence: SilenceStore = InMemorySilenceStore()) {
    var alertsThisStop = 0; private set
    private var currentStop = -1
    private val lastAlert = HashMap<String, Pair<Int, Long>>() // placeKey -> (stopId, t)
    var lastNaviVoiceT: Long? = null

    fun newStop(stopId: Int) {
        if (stopId != currentStop) { currentStop = stopId; alertsThisStop = 0 }
    }

    fun check(hit: ZoneHit?, now: Long, wallMs: Long, nearestParkingM: Double?, stopId: Int): PolicyDecision {
        if (hit == null) return PolicyDecision.Silent("금지 구역 아님")
        if (alertsThisStop >= cfg.maxAlertsPerStop) return PolicyDecision.Silent("정차당 ${cfg.maxAlertsPerStop}번 다 씀")
        silence.until(hit.placeKey)?.let { if (wallMs < it) return PolicyDecision.Silent("운전자가 30일 조용히 둔 장소") }
        lastAlert[hit.placeKey]?.let { (sid, t) ->
            if (sid != stopId && now - t < cfg.placeCooldownMs) return PolicyDecision.Silent("같은 장소 10분 쿨다운")
        }
        if (nearestParkingM != null && nearestParkingM <= cfg.parkingQuietM) return PolicyDecision.Silent("주차장 30m 안")
        lastNaviVoiceT?.let { if (now - it < cfg.naviQuietMs) return PolicyDecision.Defer(it + cfg.naviQuietMs) }
        return PolicyDecision.Speak
    }

    fun recordAlert(hit: ZoneHit, now: Long, stopId: Int) {
        alertsThisStop++
        lastAlert[hit.placeKey] = stopId to now
    }

    /** After any answer the place rests for the cooldown, even within later stops. */
    fun recordAnswered(hit: ZoneHit, now: Long, stopId: Int) {
        lastAlert[hit.placeKey] = stopId to now
    }

    fun silencePlace(hit: ZoneHit, wallMs: Long) {
        silence.put(hit.placeKey, wallMs + cfg.silenceDays * 24 * 3_600_000L)
    }
}
