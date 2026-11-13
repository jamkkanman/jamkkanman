package app.jamkkanman.core.metrics

import app.jamkkanman.core.Gear
import app.jamkkanman.core.Geo
import app.jamkkanman.core.LatLon
import app.jamkkanman.core.Scenario
import app.jamkkanman.core.Signal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.OffsetDateTime

/**
 * Builds the bundled demo scenarios from the three scenario points in assets/data/demo_points.json.
 * Usage: demoScenarios <assetsDir>
 */
fun main(args: Array<String>) {
    val assets = File(args.getOrElse(0) { "app/src/main/assets" })
    val pts = Json.parseToJsonElement(File(assets, "data/demo_points.json").readText()).jsonObject
    fun p(key: String) = pts[key]!!.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) }
    /** Direction of travel at the point (degrees clockwise from north); the car comes from behind. */
    fun heading(key: String) = pts[key]!!.jsonObject["headingDeg"]?.jsonPrimitive?.double ?: 90.0
    fun back(key: String, m: Double): Pair<Double, Double> {
        val h = Math.toRadians(heading(key))
        return Pair(-m * kotlin.math.sin(h), -m * kotlin.math.cos(h))
    }
    val start = OffsetDateTime.parse(pts["start"]?.jsonPrimitive?.content ?: "2026-11-13T19:02:00+09:00").toInstant().toEpochMilli()
    val dir = File(assets, "scenarios").apply { mkdirs() }

    class B {
        val list = ArrayList<Signal>()
        fun ms(s: Double) = Math.round(s * 1000)
        fun approach(stop: LatLon, fromEast: Double, fromNorth: Double) {
            fun at(k: Double) = Geo.offset(stop, fromEast * k, fromNorth * k)
            list += Signal.GearSel(0, Gear.DRIVE)
            list += Signal.DriverBelt(0, true)
            list += Signal.DriverDoor(0, false)
            list += Signal.Speed(0, 32.0)
            list += Signal.Location(0, at(1.0).lat, at(1.0).lon, 4.0)
            list += Signal.Location(ms(2.0), at(0.55).lat, at(0.55).lon, 4.0)
            list += Signal.Location(ms(4.0), at(0.2).lat, at(0.2).lon, 4.0)
            list += Signal.Speed(ms(4.5), 14.0)
            list += Signal.Location(ms(6.0), at(0.05).lat, at(0.05).lon, 4.0)
            list += Signal.Speed(ms(7.0), 0.0)
            list += Signal.Location(ms(7.5), stop.lat, stop.lon, 4.0)
        }
        fun leave(t: Double, stop: LatLon, ahead: Pair<Double, Double>) {
            val p = Geo.offset(stop, -ahead.first, -ahead.second)
            list += Signal.GearSel(ms(t - 1), Gear.DRIVE)
            list += Signal.Speed(ms(t), 18.0)
            list += Signal.Location(ms(t + 3), p.lat, p.lon, 4.0)
            list += Signal.Speed(ms(t + 4), 30.0)
        }
    }

    val scenarios = listOf(
        run {
            val stop = p("crosswalk")
            val (be, bn) = back("crosswalk", 90.0)
            val b = B(); b.approach(stop, be, bn)
            // Red light: gear D, belt on, door closed, no hazard. Inside the crosswalk distance, still silent.
            val ahead = Geo.offset(stop, -be / 3, -bn / 3)
            b.list += Signal.Speed(b.ms(32.0), 16.0)
            b.list += Signal.Location(b.ms(35.0), ahead.lat, ahead.lon, 4.0)
            b.list += Signal.Speed(b.ms(36.0), 30.0)
            Scenario("01_signal_wait", "신호 대기", start, b.list.sortedBy { it.t }, expectAlerts = 0,
                note = "횡단보도 10m 안 정지선에서 25초 신호 대기. 세우려는 신호가 없으므로 말하지 않는다.")
        },
        run {
            val stop = p("hydrant")
            val bk = back("hydrant", 90.0)
            val b = B(); b.approach(stop, bk.first, bk.second)
            b.list += Signal.GearSel(b.ms(11.5), Gear.PARK)
            b.list += Signal.ReplyText(b.ms(19.5), "응", "voice", "demo_voice/reply_yes.wav")
            b.leave(30.0, stop, Pair(bk.first / 3, bk.second / 3))
            Scenario("02_hydrant_gear_p", "소화전 앞 정차", start, b.list.sortedBy { it.t }, expectAlerts = 1, expectRoute = true,
                note = "소화전 3m 옆에서 기어 P. 첫 안내, 운전자 \"응\", 공영주차장으로 내비 목적지 변경.")
        },
        run {
            val stop = p("busStop")
            val bk = back("busStop", 90.0)
            val b = B(); b.approach(stop, bk.first, bk.second)
            b.list += Signal.DriverDoor(b.ms(12.0), true)
            b.list += Signal.ReplyText(b.ms(18.0), "금방 갈게", "voice", "demo_voice/reply_soon.wav")
            b.list += Signal.DriverDoor(b.ms(22.0), false)
            b.leave(75.0, stop, Pair(bk.first / 3, bk.second / 3))
            Scenario("03_busstop_door", "버스정류장 앞 하차", start, b.list.sortedBy { it.t }, expectAlerts = 2,
                note = "정류장 7m 앞에서 비상등 없이 문 열림. 내리기 직전 안내, \"금방 가\", 50초 뒤 한 번만 더 안내.")
        },
    )
    for (sc in scenarios) {
        val f = File(dir, "${sc.id}.json")
        f.writeText(Scenario.toJson(sc))
        println("wrote ${f.path} (${sc.signals.size} signals, ${sc.durationMs / 1000.0} s)")
    }
}
