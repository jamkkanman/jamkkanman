package app.jamkkanman.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic street: a hydrant, a bus stop and a crosswalk 100 m apart, and two public lots. */
object Street {
    val base = LatLon(37.5700, 126.9800)
    val hydrant = Facility("HYD-1", ZoneType.HYDRANT, "소화전 1", Geo.offset(base, 0.0, 0.0))
    val bus = Facility("BUS-1", ZoneType.BUS_STOP, "정류소 1", Geo.offset(base, 100.0, 0.0))
    val cross = Facility("CRS-1", ZoneType.CROSSWALK, "횡단보도 1", Geo.offset(base, 200.0, 0.0), extraM = 4.0)
    val lotNear = ParkingLot(
        "PK-1", "가까운공영주차장", Geo.offset(base, 0.0, 180.0), "서울 어딘가 1", "PK-1",
        WeeklyHours(OpenRange(0, 1440), OpenRange(0, 1440), OpenRange(0, 1440)),
    )
    val lotClosed = ParkingLot(
        "PK-2", "닫힌공영주차장", Geo.offset(base, 0.0, 60.0), "서울 어딘가 2", "PK-2",
        WeeklyHours(OpenRange(9 * 60, 18 * 60), OpenRange(9 * 60, 13 * 60), null),
    )
    val data = DataSet(listOf(hydrant, bus, cross), listOf(lotNear, lotClosed))

    fun at(f: Facility, eastM: Double, northM: Double = 0.0) = Geo.offset(f.pos, eastM, northM)
}

/** Small DSL for writing drives in seconds. */
class Drive(val title: String) {
    private val list = ArrayList<Signal>()
    private fun ms(s: Double) = Math.round(s * 1000)
    fun speed(t: Double, kmh: Double) = apply { list.add(Signal.Speed(ms(t), kmh)) }
    fun gear(t: Double, g: Gear) = apply { list.add(Signal.GearSel(ms(t), g)) }
    fun loc(t: Double, p: LatLon, acc: Double? = 4.0) = apply { list.add(Signal.Location(ms(t), p.lat, p.lon, acc)) }
    fun door(t: Double, open: Boolean) = apply { list.add(Signal.DriverDoor(ms(t), open)) }
    fun belt(t: Double, on: Boolean) = apply { list.add(Signal.DriverBelt(ms(t), on)) }
    fun hazard(t: Double, on: Boolean) = apply { list.add(Signal.Hazard(ms(t), on)) }
    fun arrive(t: Double) = apply { list.add(Signal.NaviArrived(ms(t), "목적지")) }
    fun naviVoice(t: Double) = apply { list.add(Signal.NaviVoice(ms(t))) }
    fun reply(t: Double, text: String) = apply { list.add(Signal.ReplyText(ms(t), text)) }
    /** Drives at 30 km/h in gear D with the belt on, then stops at [p] at time [stopAt]. */
    fun approach(p: LatLon, stopAt: Double) = apply {
        gear(0.0, Gear.DRIVE); belt(0.0, true); speed(0.0, 30.0)
        loc(0.0, Geo.offset(p, -60.0, 0.0))
        speed(stopAt - 2, 10.0)
        loc(stopAt, p)
        speed(stopAt, 0.0)
    }
    fun leave(t: Double, p: LatLon) = apply { speed(t, 20.0); loc(t + 6, Geo.offset(p, 80.0, 0.0)) }

    fun scenario(wall: String = "2026-11-13T19:02:00+09:00") = Scenario(
        id = title, title = title,
        startWallMs = java.time.OffsetDateTime.parse(wall).toInstant().toEpochMilli(),
        signals = list.sortedBy { it.t },
    )
}

class EngineTest {
    private fun run(d: Drive, wall: String = "2026-11-13T19:02:00+09:00") = ScenarioRunner.run(d.scenario(wall), Street.data)

    @Test
    fun trafficLightWaitAtCrosswalkIsSilent() {
        val p = Street.at(Street.cross, -6.0)
        val r = run(Drive("신호 대기").approach(p, 10.0).leave(70.0, p))
        assertEquals(0, r.spoken.size, r.logs.joinToString("\n"))
        assertTrue(r.logs.any { it.decision.contains("말하지 않음") })
    }

    @Test
    fun gearParkNextToHydrantSpeaksOnceAndRoutesOnYes() {
        val p = Street.at(Street.hydrant, 3.0)
        val r = run(Drive("소화전 기어 P").approach(p, 10.0).gear(14.0, Gear.PARK).reply(18.0, "응"))
        assertEquals(1, r.alerts.size)
        assertEquals("소화전 5미터 안이에요. 잠깐 정차도 안 되는 곳입니다. 근처 공영주차장으로 안내할까요?", r.alerts[0].text)
        assertTrue(r.routed)
        val parking = r.spoken.first { it.kind == Utterance.PARKING }
        assertEquals("가까운공영주차장, 180미터입니다.", parking.text)
    }

    @Test
    fun doorOpenNearBusStopWithoutIntentSpeaksAlightForm() {
        val p = Street.at(Street.bus, 7.0)
        val r = run(Drive("정류소 문 열림").approach(p, 10.0).door(16.0, true))
        assertEquals(1, r.alerts.size)
        assertEquals("내리시기 전에, 여기는 버스정류소 10미터 안입니다.", r.alerts[0].text)
    }

    @Test
    fun hazardIsAnIntentSignalWhenAvailable() {
        val p = Street.at(Street.hydrant, 3.0)
        val r = run(Drive("비상등").approach(p, 10.0).hazard(12.0, true))
        assertEquals(1, r.alerts.size)
    }

    @Test
    fun unansweredAlertGetsOneConfirmationAtTheDoorAndNoMore() {
        val p = Street.at(Street.hydrant, 3.0)
        val r = run(Drive("두 번까지").approach(p, 10.0).gear(14.0, Gear.PARK).door(30.0, true).door(40.0, false).door(50.0, true))
        assertEquals(2, r.alerts.size)
        assertEquals(Utterance.ALIGHT_ALERT, r.alerts[1].kind)
        assertTrue(r.alerts[1].text.contains("과태료 최대 8만원"))
    }

    @Test
    fun soonGetsOneReminderAfter50Seconds() {
        val p = Street.at(Street.hydrant, 3.0)
        val r = run(Drive("금방 가").approach(p, 10.0).gear(14.0, Gear.PARK).reply(17.0, "금방 갈게"))
        val remind = r.effects.filter { (_, e) -> e is Effect.Speak && e.kind == Utterance.REMIND }
        assertEquals(1, remind.size)
        assertEquals(67_000L, remind[0].first)
        assertEquals(2, r.alerts.size)
    }

    @Test
    fun ignoreSilencesThePlaceForTheNextStop() {
        val p = Street.at(Street.hydrant, 3.0)
        val d = Drive("괜찮아").approach(p, 10.0).gear(14.0, Gear.PARK).reply(17.0, "괜찮아")
            .gear(30.0, Gear.DRIVE).speed(30.0, 20.0).loc(40.0, Street.at(Street.hydrant, 300.0))
            // Back to the same spot 20 minutes later.
            .loc(1230.0, p).speed(1230.0, 0.0).gear(1236.0, Gear.PARK)
        val r = run(d)
        assertEquals(1, r.alerts.size, r.logs.joinToString("\n"))
        assertTrue(r.logs.any { it.decision.contains("30일") })
    }

    @Test
    fun samePlaceWithinTenMinutesIsQuiet() {
        val p = Street.at(Street.hydrant, 3.0)
        val d = Drive("쿨다운").approach(p, 10.0).gear(14.0, Gear.PARK)
            .gear(30.0, Gear.DRIVE).speed(30.0, 20.0).loc(40.0, Street.at(Street.hydrant, 300.0))
            .loc(300.0, p).speed(300.0, 0.0).gear(306.0, Gear.PARK)
        val r = run(d)
        assertEquals(1, r.alerts.size, r.logs.joinToString("\n"))
        assertTrue(r.logs.any { it.decision.contains("10분 쿨다운") })
    }

    @Test
    fun naviVoiceDefersTheAlertByFiveSeconds() {
        val p = Street.at(Street.hydrant, 3.0)
        val r = run(Drive("내비 음성").approach(p, 10.0).naviVoice(13.5).gear(14.0, Gear.PARK))
        assertEquals(1, r.alerts.size)
        val at = r.effects.first { (_, e) -> e is Effect.Speak }.first
        assertEquals(18_500L, at)
    }

    @Test
    fun stoppedAtLaunchDoesNotSpeak() {
        val p = Street.at(Street.hydrant, 3.0)
        val d = Drive("시동 직후").gear(0.0, Gear.PARK).loc(0.0, p).speed(0.0, 0.0).door(5.0, true)
        val r = run(d)
        assertEquals(0, r.spoken.size)
    }

    @Test
    fun beltThatAlwaysReadsUnbuckledIsNotAnAlightSignal() {
        val p = Street.at(Street.cross, -6.0)
        val d = Drive("벨트 false 고정").gear(0.0, Gear.DRIVE).belt(0.0, false).speed(0.0, 30.0)
            .loc(0.0, Geo.offset(p, -60.0, 0.0)).speed(8.0, 0.0).loc(10.0, p).belt(20.0, false)
        val r = run(d)
        assertEquals(0, r.spoken.size)
    }

    @Test
    fun closedLotIsNotOffered() {
        val p = Street.at(Street.hydrant, 3.0)
        // 21:00 on a Friday: the 09:00-18:00 lot 60 m away is closed, the 24-hour lot 180 m away is open.
        val r = run(Drive("야간").approach(p, 10.0).gear(14.0, Gear.PARK).reply(18.0, "응"), "2026-11-13T21:00:00+09:00")
        val route = r.effects.map { it.second }.filterIsInstance<Effect.Route>().single()
        assertEquals("PK-1", route.option.lot.id)
        // At 10:00 the nearer lot is open and wins.
        val r2 = run(Drive("주간").approach(p, 10.0).gear(14.0, Gear.PARK).reply(18.0, "응"), "2026-11-13T10:00:00+09:00")
        assertEquals("PK-2", r2.effects.map { it.second }.filterIsInstance<Effect.Route>().single().option.lot.id)
    }

    @Test
    fun replayIsDeterministic() {
        val p = Street.at(Street.hydrant, 3.0)
        val d = Drive("결정론").approach(p, 10.0).gear(14.0, Gear.PARK).reply(18.0, "금방 가").door(40.0, true)
        val a = ScenarioRunner.run(d.scenario(), Street.data).effects
        val b = ScenarioRunner.run(d.scenario(), Street.data).effects
        assertEquals(a.map { it.first to it.second.stable() }, b.map { it.first to it.second.stable() })
    }

    /** Judge timing is the only nondeterministic number; drop it before comparing. */
    private fun Effect.stable(): String = when (this) {
        is Effect.Log -> "${entry.t} ${entry.phase} ${entry.event.replace(Regex("""\(\d+\.\d+ ms\)"""), "")} ${entry.decision}"
        else -> toString()
    }
}

class ZoneJudgeTest {
    private val judge = ZoneJudge(ZoneIndex(Street.data.facilities))

    @Test
    fun insideNearAndOutside() {
        assertEquals(Verdict.INSIDE, judge.judge(Street.at(Street.hydrant, 4.9), 5.0).top?.verdict)
        assertEquals(Verdict.NEAR, judge.judge(Street.at(Street.hydrant, 8.0), 5.0).top?.verdict)
        assertEquals(null, judge.judge(Street.at(Street.hydrant, 10.5), 5.0).top)
        // Smaller reported error, smaller band.
        assertEquals(null, judge.judge(Street.at(Street.hydrant, 8.0), 2.0).top)
    }

    @Test
    fun crosswalkCountsFromItsEdge() {
        // 10 m legal distance plus half of an 8 m crosswalk.
        assertEquals(Verdict.INSIDE, judge.judge(Street.at(Street.cross, -13.5), 5.0).top?.verdict)
    }

    @Test
    fun higherFineWinsWhenZonesOverlap() {
        val f1 = Facility("H", ZoneType.HYDRANT, "", Street.base)
        val f2 = Facility("B", ZoneType.BUS_STOP, "", Geo.offset(Street.base, 2.0, 0.0))
        val j = ZoneJudge(ZoneIndex(listOf(f1, f2))).judge(Geo.offset(Street.base, 1.0, 0.0), 5.0)
        assertEquals("H", j.top?.facility?.id)
        assertEquals(2, j.hits.size)
    }

    @Test
    fun gridFindsFacilitiesAcrossCellEdges() {
        val many = (0 until 400).map { i ->
            Facility("F$i", ZoneType.HYDRANT, "", Geo.offset(Street.base, (i % 20) * 37.0, (i / 20) * 41.0))
        }
        val j = ZoneJudge(ZoneIndex(many))
        for (f in many) {
            val hit = j.judge(Geo.offset(f.pos, 1.0, 1.0), 5.0).top
            assertEquals(f.id, hit?.facility?.id)
        }
    }
}

class ReplyClassifierTest {
    @Test
    fun keywords() {
        assertEquals(Reply.YES, ReplyClassifier.classify("응"))
        assertEquals(Reply.YES, ReplyClassifier.classify("응 안 해줘")) // how STT heard "응, 안내해 줘"
        assertEquals(Reply.YES, ReplyClassifier.classify("주차장 안내해"))
        assertEquals(Reply.SOON, ReplyClassifier.classify("금방 가"))
        assertEquals(Reply.SOON, ReplyClassifier.classify("갈게"))
        assertEquals(Reply.SOON, ReplyClassifier.classify("잠깐만"))
        assertEquals(Reply.IGNORE, ReplyClassifier.classify("괜찮아"))
        assertEquals(Reply.IGNORE, ReplyClassifier.classify("아니 괜찮아"))
        assertEquals(Reply.UNKNOWN, ReplyClassifier.classify("음"))
    }
}

class StopMachineTest {
    @Test
    fun drivingNeedsFiveSecondsAboveFive() {
        val m = StopMachine()
        m.onSignal(Signal.Speed(0, 30.0))
        assertEquals(Phase.UNKNOWN, m.phase)
        m.onTick(4_900)
        assertEquals(Phase.UNKNOWN, m.phase)
        m.onTick(5_000)
        assertEquals(Phase.DRIVING, m.phase)
        m.onSignal(Signal.Speed(6_000, 0.0))
        m.onTick(8_900)
        assertEquals(Phase.DRIVING, m.phase)
        m.onTick(9_000)
        assertEquals(Phase.STOPPED, m.phase)
    }
}
