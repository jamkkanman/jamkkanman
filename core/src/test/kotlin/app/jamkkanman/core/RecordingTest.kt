package app.jamkkanman.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The live source records signals with [SignalCodec]; a recording replays to the same decisions. */
class RecordingTest {
    @Test
    fun liveRecordingRoundTripsThroughTheReplayer() {
        val p = Street.at(Street.hydrant, 3.0)
        val drive = Drive("기록").approach(p, 10.0).gear(14.0, Gear.PARK).reply(18.0, "응").scenario()
        // The app writes times relative to its session start, which is not zero.
        val sessionStart = 5_000_000L
        val lines = drive.signals.map { SignalCodec.encodeLine(Scenario.shiftTime(it, sessionStart + 123_456), sessionStart) }
        val back = Scenario.fromRecording("rec", "기록 재생", lines, drive.startWallMs)
        assertEquals(drive.signals.size, back.signals.size)
        val a = ScenarioRunner.run(drive, Street.data)
        val b = ScenarioRunner.run(back, Street.data)
        assertEquals(a.alerts.map { it.text }, b.alerts.map { it.text })
        assertEquals(a.routed, b.routed)
    }

    @Test
    fun codecKeepsEveryField() {
        val signals = listOf(
            Signal.Speed(1_000, 31.5), Signal.GearSel(2_000, Gear.PARK), Signal.Hazard(3_000, true),
            Signal.DriverDoor(4_000, true), Signal.DriverBelt(5_000, false),
            Signal.Location(6_000, 37.5, 127.0, 4.0), Signal.NaviArrived(7_000, "학원"), Signal.NaviVoice(8_000),
            Signal.ReplyText(9_000, "응", "voice", "demo_voice/reply_yes.wav"),
        )
        for (s in signals) assertEquals(s, SignalCodec.decodeLine(SignalCodec.encodeLine(s)))
    }
}
