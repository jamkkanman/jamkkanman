package app.jamkkanman.core.metrics

import app.jamkkanman.core.DataSet
import app.jamkkanman.core.Effect
import app.jamkkanman.core.Facility
import app.jamkkanman.core.Gear
import app.jamkkanman.core.Geo
import app.jamkkanman.core.LatLon
import app.jamkkanman.core.Scenario
import app.jamkkanman.core.ScenarioRunner
import app.jamkkanman.core.Signal
import app.jamkkanman.core.Utterance
import app.jamkkanman.core.ZoneIndex
import app.jamkkanman.core.ZoneJudge
import app.jamkkanman.core.ZoneType
import java.time.OffsetDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generated drives for the quantitative targets in the plan. Every suite is seeded, so a rerun gives
 * the same numbers.
 */
object Suites {
    private val wall = OffsetDateTime.parse("2026-11-13T19:02:00+09:00").toInstant().toEpochMilli()

    private class B(val title: String) {
        val list = ArrayList<Signal>()
        fun ms(s: Double) = Math.round(s * 1000)
        fun speed(t: Double, v: Double) { list.add(Signal.Speed(ms(t), v)) }
        fun gear(t: Double, g: Gear) { list.add(Signal.GearSel(ms(t), g)) }
        fun loc(t: Double, p: LatLon, acc: Double = 5.0) { list.add(Signal.Location(ms(t), p.lat, p.lon, acc)) }
        fun door(t: Double, open: Boolean) { list.add(Signal.DriverDoor(ms(t), open)) }
        fun belt(t: Double, on: Boolean) { list.add(Signal.DriverBelt(ms(t), on)) }
        fun arrive(t: Double) { list.add(Signal.NaviArrived(ms(t), "목적지")) }
        fun reply(t: Double, text: String) { list.add(Signal.ReplyText(ms(t), text)) }
        fun build(expectAlerts: Int? = null) = Scenario(title, title, wall, list.sortedBy { it.t }, expectAlerts)
    }

    private fun bearing(r: Random) = r.nextDouble(0.0, 2 * Math.PI)
    private fun around(p: LatLon, d: Double, a: Double) = Geo.offset(p, d * cos(a), d * sin(a))

    /** Approach at 25 to 45 km/h, stop at [stop]; returns the time the car stands still. */
    private fun B.approach(stop: LatLon, r: Random): Double {
        val a = bearing(r)
        gear(0.0, Gear.DRIVE); belt(0.0, true)
        speed(0.0, r.nextDouble(25.0, 45.0))
        loc(0.0, around(stop, 120.0, a))
        loc(4.0, around(stop, 40.0, a))
        speed(6.0, 12.0)
        loc(7.0, around(stop, 6.0, a))
        speed(8.0, 0.0)
        loc(8.5, stop)
        return 8.0
    }

    private fun pick(data: DataSet, type: ZoneType, i: Int): Facility? =
        data.facilities.filter { it.type == type }.let { if (it.isEmpty()) null else it[i % it.size] }

    /**
     * 100 traffic light waits next to crosswalks, bus stops and hydrants: speed 0 for 20 to 120 s,
     * gear D, belt on, door closed. Variants add GPS jitter, stop-and-go and long waits. Expected speech: 0.
     */
    fun signalWaits(data: DataSet, n: Int = 100, seed: Int = 7): List<Scenario> {
        val r = Random(seed)
        return (0 until n).map { i ->
            val type = listOf(ZoneType.CROSSWALK, ZoneType.CROSSWALK, ZoneType.BUS_STOP, ZoneType.HYDRANT)[i % 4]
            val f = pick(data, type, i / 4) ?: pick(data, ZoneType.CROSSWALK, i)!!
            // The worst case for false alarms: the stop line is inside the no-stopping distance.
            val stop = around(f.pos, r.nextDouble(1.0, f.radiusM * 0.9), bearing(r))
            val b = B("신호 대기 ${i + 1} (${f.type.label})")
            val t0 = b.approach(stop, r)
            val wait = r.nextDouble(20.0, 120.0)
            when (i % 5) {
                1 -> { var t = t0 + 1; while (t < t0 + wait) { b.loc(t, around(stop, r.nextDouble(0.0, 6.0), bearing(r)), 5.0); t += 1.0 } }
                2 -> { b.speed(t0 + wait / 2, 3.0); b.speed(t0 + wait / 2 + 2, 0.0) }
                else -> Unit
            }
            b.speed(t0 + wait, 15.0); b.speed(t0 + wait + 2, 30.0)
            b.loc(t0 + wait + 6, around(stop, 60.0, bearing(r)))
            b.build(expectAlerts = 0)
        }
    }

    /**
     * 100 stops inside a no-stopping distance with an intent or alighting signal, and a stated answer mix:
     * no answer then the door opens (40 %), "응" (30 %), "금방 가" and still stopped after 50 s (15 %),
     * "괜찮아" (15 %). Intent signal mix: gear P 60 %, navigation arrival 20 %, door only 20 %.
     */
    fun intentStops(data: DataSet, n: Int = 100, seed: Int = 11): List<Scenario> {
        val r = Random(seed)
        return (0 until n).map { i ->
            val type = listOf(ZoneType.HYDRANT, ZoneType.BUS_STOP, ZoneType.CROSSWALK)[i % 3]
            val f = pick(data, type, i / 3) ?: pick(data, ZoneType.HYDRANT, i)!!
            val stop = around(f.pos, r.nextDouble(0.5, f.type.legalM * 0.8), bearing(r))
            val b = B("금지 구역 정차 ${i + 1} (${f.type.label})")
            val t0 = b.approach(stop, r)
            val ti = t0 + 3 + r.nextDouble(0.0, 4.0)
            val signal = when (i % 5) { 0, 1, 2 -> "P"; 3 -> "ARRIVE"; else -> "DOOR" }
            when (signal) {
                "P" -> b.gear(ti, Gear.PARK)
                "ARRIVE" -> b.arrive(t0 - 1)
                "DOOR" -> b.door(ti, true)
            }
            val answer = when (i % 20) { in 0..7 -> "NONE"; in 8..13 -> "YES"; in 14..16 -> "SOON"; else -> "IGNORE" }
            val tr = ti + 6
            var expect: Int
            when (answer) {
                "YES" -> { b.reply(tr, "응"); expect = 1 }
                "SOON" -> { b.reply(tr, "금방 가"); expect = 2 }
                "IGNORE" -> { b.reply(tr, "괜찮아"); expect = 1 }
                else -> { if (signal != "DOOR") b.door(tr + 10, true); expect = if (signal == "DOOR") 1 else 2 }
            }
            val leaveAt = if (answer == "SOON") tr + 70 else tr + 25
            b.gear(leaveAt - 1, Gear.DRIVE)
            b.speed(leaveAt, 20.0)
            b.loc(leaveAt + 6, around(stop, 80.0, bearing(r)))
            b.build(expectAlerts = expect)
        }
    }

    data class SuiteResult(
        val name: String,
        val scenarios: Int,
        val passed: Int,
        val stops: Int,
        val alerts: Int,
        val maxAlertsPerStop: Int,
        val failures: List<String>,
    ) {
        val alertsPerStop: Double get() = if (stops == 0) 0.0 else alerts.toDouble() / stops
    }

    fun run(name: String, scenarios: List<Scenario>, data: DataSet): SuiteResult {
        var passed = 0; var stops = 0; var alerts = 0; var maxPerStop = 0
        val failures = ArrayList<String>()
        for (sc in scenarios) {
            val res = ScenarioRunner.run(sc, data, tailMs = 70_000)
            if (res.passed) passed++ else failures.add("${sc.title}: 예상 ${sc.expectAlerts}, 실제 ${res.alerts.size}")
            stops += res.stops
            alerts += res.alerts.size
            // Alerts grouped by stop: count Speak effects between stop transitions.
            var current = 0
            for ((_, e) in res.effects) {
                if (e is Effect.Log && e.entry.event.endsWith("-> 멈춤")) current = 0
                if (e is Effect.Speak && e.kind.isAlert) { current++; if (current > maxPerStop) maxPerStop = current }
            }
        }
        return SuiteResult(name, scenarios.size, passed, stops, alerts, maxPerStop, failures)
    }

    /** Judge timing over [n] random points inside the data's bounding box. Returns (mean us, p99 us, max us). */
    fun judgeTiming(facilities: List<Facility>, n: Int = 20_000, seed: Int = 3): Triple<Double, Double, Double> {
        val judge = ZoneJudge(ZoneIndex(facilities))
        val r = Random(seed)
        // Pad by about 200 m so a tiny data set still gives a usable query area.
        val minLat = facilities.minOf { it.pos.lat } - 0.002; val maxLat = facilities.maxOf { it.pos.lat } + 0.002
        val minLon = facilities.minOf { it.pos.lon } - 0.002; val maxLon = facilities.maxOf { it.pos.lon } + 0.002
        repeat(2_000) { judge.judge(LatLon(r.nextDouble(minLat, maxLat), r.nextDouble(minLon, maxLon))) } // warm-up
        val times = DoubleArray(n) {
            judge.judge(LatLon(r.nextDouble(minLat, maxLat), r.nextDouble(minLon, maxLon))).elapsedNanos / 1000.0
        }
        times.sort()
        return Triple(times.average(), times[(n * 0.99).toInt()], times.last())
    }

    /** Facilities spread over Seoul's extent at hydrant-like density, for timing at city scale. */
    fun cityScale(count: Int = 60_000, seed: Int = 5): List<Facility> {
        val r = Random(seed)
        return (0 until count).map { i ->
            val type = listOf(ZoneType.HYDRANT, ZoneType.BUS_STOP, ZoneType.CROSSWALK)[i % 3]
            Facility("S$i", type, "", LatLon(r.nextDouble(37.43, 37.70), r.nextDouble(126.76, 127.18)))
        }
    }

    @Suppress("unused")
    private fun alertKinds(effects: List<Effect>) = effects.filterIsInstance<Effect.Speak>().filter { it.kind != Utterance.PARKING }
}
