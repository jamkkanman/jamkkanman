package app.jamkkanman.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

/**
 * A recorded or scripted drive: signals in the same format the live vehicle source records,
 * a wall-clock start time (for parking hours), and what the run must produce.
 */
data class Scenario(
    val id: String,
    val title: String,
    val startWallMs: Long,
    val signals: List<Signal>,
    val expectAlerts: Int? = null,
    val expectRoute: Boolean? = null,
    val note: String = "",
) {
    val durationMs: Long get() = signals.maxOfOrNull { it.t } ?: 0L

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Scenario {
            val o = json.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val expect = o["expect"]?.jsonObject
            val signals = o["events"]!!.jsonArray.map { SignalCodec.decode(it.jsonObject) }.sortedBy { it.t }
            return Scenario(
                id = s("id") ?: "scenario",
                title = s("title") ?: "",
                startWallMs = OffsetDateTime.parse(s("start") ?: "2026-11-13T19:02:00+09:00").toInstant().toEpochMilli(),
                signals = signals,
                expectAlerts = expect?.get("alerts")?.jsonPrimitive?.intOrNull,
                expectRoute = expect?.get("route")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                note = s("note") ?: "",
            )
        }

        /**
         * A live recording from the app (files/recordings/live-*.jsonl): one signal per line, written by the
         * vehicle source with the same codec. Time zero is the first signal.
         */
        fun fromRecording(id: String, title: String, lines: List<String>, startWallMs: Long, expectAlerts: Int? = null): Scenario {
            val signals = lines.filter { it.isNotBlank() }.map { SignalCodec.decodeLine(it) }.sortedBy { it.t }
            val t0 = signals.firstOrNull()?.t ?: 0L
            val shifted = signals.map { shiftTime(it, -t0) }
            return Scenario(id, title, startWallMs, shifted, expectAlerts)
        }

        fun shiftTime(s: Signal, d: Long): Signal = when (s) {
            is Signal.Speed -> s.copy(t = s.t + d)
            is Signal.GearSel -> s.copy(t = s.t + d)
            is Signal.Hazard -> s.copy(t = s.t + d)
            is Signal.DriverDoor -> s.copy(t = s.t + d)
            is Signal.DriverBelt -> s.copy(t = s.t + d)
            is Signal.Location -> s.copy(t = s.t + d)
            is Signal.NaviArrived -> s.copy(t = s.t + d)
            is Signal.NaviVoice -> s.copy(t = s.t + d)
            is Signal.ReplyText -> s.copy(t = s.t + d)
        }

        fun toJson(sc: Scenario): String {
            fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
            val events = sc.signals.joinToString(",\n    ") { SignalCodec.encodeLine(it) }
            val start = java.time.Instant.ofEpochMilli(sc.startWallMs).atOffset(java.time.ZoneOffset.ofHours(9))
            val expect = buildList {
                sc.expectAlerts?.let { add("\"alerts\": $it") }
                sc.expectRoute?.let { add("\"route\": \"$it\"") }
            }.joinToString(", ")
            return "{\n" +
                "  \"id\": ${q(sc.id)},\n" +
                "  \"title\": ${q(sc.title)},\n" +
                "  \"start\": ${q(start.toString())},\n" +
                "  \"note\": ${q(sc.note)},\n" +
                "  \"expect\": { $expect },\n" +
                "  \"events\": [\n    $events\n  ]\n" +
                "}\n"
        }
    }
}

/** Result of replaying one scenario on a virtual clock. */
data class RunResult(
    val scenario: Scenario,
    val effects: List<Pair<Long, Effect>>,
    val stops: Int,
) {
    val spoken: List<Effect.Speak> get() = effects.map { it.second }.filterIsInstance<Effect.Speak>()
    val alerts: List<Effect.Speak> get() = spoken.filter { it.kind.isAlert }
    val routed: Boolean get() = effects.any { it.second is Effect.Route }
    val logs: List<LogEntry> get() = effects.map { it.second }.filterIsInstance<Effect.Log>().map { it.entry }
    val passed: Boolean
        get() = (scenario.expectAlerts == null || scenario.expectAlerts == alerts.size) &&
            (scenario.expectRoute == null || scenario.expectRoute == routed)
}

/**
 * Plays a scenario into a fresh engine on a virtual clock, ticking every [tickMs] between signals.
 * The app uses the same engine with a real clock; this runner is what tests and metrics use.
 */
object ScenarioRunner {
    fun run(sc: Scenario, data: DataSet, tickMs: Long = 100, tailMs: Long = 60_000, engineCfg: EngineConfig = EngineConfig()): RunResult {
        val engine = Engine(
            judge = ZoneJudge(ZoneIndex(data.facilities)),
            parking = ParkingFinder(data.parking),
            clock = WallClock { t -> sc.startWallMs + t },
            cfg = engineCfg,
        )
        val out = ArrayList<Pair<Long, Effect>>()
        var t = 0L
        val end = sc.durationMs + tailMs
        var i = 0
        while (t <= end) {
            while (i < sc.signals.size && sc.signals[i].t <= t) {
                val s = sc.signals[i++]
                engine.onSignal(s).forEach { out.add(s.t to it) }
            }
            engine.tick(t).forEach { out.add(t to it) }
            t += tickMs
        }
        val stops = out.count { (_, e) -> e is Effect.Log && e.entry.event.endsWith("-> 멈춤") }
        return RunResult(sc, out, stops)
    }
}
