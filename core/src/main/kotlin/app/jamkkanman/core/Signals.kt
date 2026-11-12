package app.jamkkanman.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class Gear(val label: String) { PARK("P"), REVERSE("R"), NEUTRAL("N"), DRIVE("D"), UNKNOWN("-") }

/**
 * Every input the engine reacts to. The live vehicle source and the scenario player emit exactly these
 * objects, and both can be written to and read from the same JSON line format ([SignalCodec]).
 * [t] is a monotonic time in milliseconds.
 */
sealed class Signal {
    abstract val t: Long

    data class Speed(override val t: Long, val kmh: Double) : Signal()
    data class GearSel(override val t: Long, val gear: Gear) : Signal()
    data class Hazard(override val t: Long, val on: Boolean) : Signal()
    data class DriverDoor(override val t: Long, val open: Boolean) : Signal()
    data class DriverBelt(override val t: Long, val buckled: Boolean) : Signal()
    data class Location(override val t: Long, val lat: Double, val lon: Double, val accuracyM: Double? = null) : Signal() {
        val pos: LatLon get() = LatLon(lat, lon)
    }
    data class NaviArrived(override val t: Long, val name: String = "") : Signal()
    /** The navigation app just spoke; the app waits 5 s before talking. */
    data class NaviVoice(override val t: Long) : Signal()
    /**
     * A driver answer from speech recognition ("voice") or the stopped-screen buttons ("button").
     * In a scenario, [audio] names a recorded clip; the app feeds it to speech recognition when it can,
     * and the engine itself only reads [text].
     */
    data class ReplyText(override val t: Long, val text: String, val via: String = "voice", val audio: String? = null) : Signal()

    fun describe(): String = when (this) {
        is Speed -> "속도 %.0f km/h".format(kmh)
        is GearSel -> "기어 ${gear.label}"
        is Hazard -> "비상등 ${if (on) "켜짐" else "꺼짐"}"
        is DriverDoor -> "운전석 문 ${if (open) "열림" else "닫힘"}"
        is DriverBelt -> "운전석 벨트 ${if (buckled) "착용" else "해제"}"
        is Location -> "위치 %.6f, %.6f".format(lat, lon) + (accuracyM?.let { " (오차 %.0f m)".format(it) } ?: "")
        is NaviArrived -> "내비 목적지 도착" + if (name.isNotEmpty()) " ($name)" else ""
        is NaviVoice -> "내비 음성 안내"
        is ReplyText -> "응답 \"$text\" (${if (via == "button") "화면 버튼" else "음성"})"
    }
}

/** JSON line codec: {"t": 12.5, "type": "GEAR", "gear": "P"}. Time is in seconds in the file. */
object SignalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(s: Signal, t0: Long = 0L): JsonObject = buildJsonObject {
        put("t", (s.t - t0) / 1000.0)
        when (s) {
            is Signal.Speed -> { put("type", "SPEED"); put("kmh", s.kmh) }
            is Signal.GearSel -> { put("type", "GEAR"); put("gear", s.gear.label) }
            is Signal.Hazard -> { put("type", "HAZARD"); put("on", s.on) }
            is Signal.DriverDoor -> { put("type", "DOOR"); put("open", s.open) }
            is Signal.DriverBelt -> { put("type", "BELT"); put("buckled", s.buckled) }
            is Signal.Location -> {
                put("type", "LOCATION"); put("lat", s.lat); put("lon", s.lon)
                s.accuracyM?.let { put("acc", it) }
            }
            is Signal.NaviArrived -> { put("type", "NAVI_ARRIVED"); put("name", s.name) }
            is Signal.NaviVoice -> put("type", "NAVI_VOICE")
            is Signal.ReplyText -> {
                put("type", "REPLY"); put("text", s.text); put("via", s.via)
                s.audio?.let { put("audio", it) }
            }
        }
    }

    fun encodeLine(s: Signal, t0: Long = 0L): String = encode(s, t0).toString()

    fun decode(o: JsonObject, t0: Long = 0L): Signal {
        val t = t0 + Math.round(o["t"]!!.jsonPrimitive.double * 1000.0)
        fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
        fun bool(k: String) = o[k]!!.jsonPrimitive.boolean
        fun num(k: String) = o[k]!!.jsonPrimitive.double
        return when (val type = str("type")) {
            "SPEED" -> Signal.Speed(t, num("kmh"))
            "GEAR" -> Signal.GearSel(t, Gear.entries.firstOrNull { it.label == str("gear") || it.name == str("gear") } ?: Gear.UNKNOWN)
            "HAZARD" -> Signal.Hazard(t, bool("on"))
            "DOOR" -> Signal.DriverDoor(t, bool("open"))
            "BELT" -> Signal.DriverBelt(t, bool("buckled"))
            "LOCATION" -> Signal.Location(t, num("lat"), num("lon"), o["acc"]?.jsonPrimitive?.doubleOrNull)
            "NAVI_ARRIVED" -> Signal.NaviArrived(t, str("name"))
            "NAVI_VOICE" -> Signal.NaviVoice(t)
            "REPLY" -> Signal.ReplyText(t, str("text"), str("via").ifEmpty { "voice" }, o["audio"]?.jsonPrimitive?.content)
            else -> error("unknown signal type: $type")
        }
    }

    fun decodeLine(line: String, t0: Long = 0L): Signal = decode(json.parseToJsonElement(line).jsonObject, t0)
}
