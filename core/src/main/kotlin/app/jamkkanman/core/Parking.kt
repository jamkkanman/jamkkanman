package app.jamkkanman.core

import java.time.DayOfWeek
import java.time.ZonedDateTime

/** Opening range in minutes of the day. 0..1440 means all day; start > end crosses midnight. */
data class OpenRange(val startMin: Int, val endMin: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> minuteOfDay in startMin until endMin
        else -> minuteOfDay >= startMin || minuteOfDay < endMin
    }

    override fun toString(): String = "%02d:%02d-%02d:%02d".format(startMin / 60, startMin % 60, endMin / 60, endMin % 60)

    companion object {
        /** Parses "HH:MM-HH:MM"; "00:00-00:00" in the public data means closed unless both days say so. */
        fun parse(text: String?): OpenRange? {
            if (text.isNullOrBlank()) return null
            val m = Regex("""(\d{1,2}):?(\d{2})\s*[-~]\s*(\d{1,2}):?(\d{2})""").find(text) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            val s = h1.toInt() * 60 + m1.toInt()
            var e = h2.toInt() * 60 + m2.toInt()
            if (e == 0 && s == 0) e = 24 * 60
            return OpenRange(s, e.coerceAtMost(24 * 60))
        }
    }
}

data class WeeklyHours(val weekday: OpenRange?, val saturday: OpenRange?, val holiday: OpenRange?) {
    fun rangeFor(day: DayOfWeek): OpenRange? = when (day) {
        DayOfWeek.SATURDAY -> saturday
        DayOfWeek.SUNDAY -> holiday
        else -> weekday
    }

    /** null when the data does not say. */
    fun isOpen(at: ZonedDateTime): Boolean? {
        val r = rangeFor(at.dayOfWeek) ?: return null
        return r.contains(at.hour * 60 + at.minute)
    }
}

data class ParkingLot(
    val id: String,
    val name: String,
    val pos: LatLon,
    val address: String,
    val mgmtNo: String,
    val hours: WeeklyHours,
    val feeText: String = "",
    val capacity: Int? = null,
    val source: String = "",
)

data class ParkingOption(val lot: ParkingLot, val distanceM: Double, val openNow: Boolean?)

/** Public parking lots that are open now, nearest first. Lots without hours come after lots known to be open. */
class ParkingFinder(private val lots: List<ParkingLot>) {

    fun options(at: LatLon, wall: ZonedDateTime, limit: Int = 2): List<ParkingOption> =
        lots.map { ParkingOption(it, Geo.distanceM(at, it.pos), it.hours.isOpen(wall)) }
            .filter { it.openNow != false }
            .sortedWith(compareBy<ParkingOption> { if (it.openNow == true) 0 else 1 }.thenBy { it.distanceM })
            .take(limit)

    /** Distance to the nearest lot regardless of hours, for the "inside a parking lot" silence rule. */
    fun nearestDistance(at: LatLon): Double? = lots.minOfOrNull { Geo.distanceM(at, it.pos) }

    val size: Int get() = lots.size
}
