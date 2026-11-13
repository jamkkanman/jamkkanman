package app.jamkkanman.core

import kotlin.math.cos
import kotlin.math.floor

/**
 * No-stopping zones handled by the app (Road Traffic Act article 32) and passenger car fines
 * (Enforcement Decree tables 6 and 7). [rankFine] orders overlapping zones: the highest fine is spoken.
 */
enum class ZoneType(
    val label: String,
    val legalM: Double,
    val rankFine: Int,
    val fineText: String,
    val typePriority: Int,
) {
    // 40,000 won, or 80,000 won where a safety sign (red curb) is installed; the data has no sign field.
    HYDRANT("소화전", 5.0, 80_000, "최대 8만원", 2),
    BUS_STOP("버스정류장", 10.0, 40_000, "4만원", 1),
    CROSSWALK("횡단보도", 10.0, 40_000, "4만원", 1),
    CHILD_ZONE("어린이보호구역", 0.0, 120_000, "12만원", 3),
}

/**
 * One facility from public data. [extraM] widens the legal distance for facilities that are not points:
 * for a crosswalk it is half its length across the road, so a car at the curb next to the stripes
 * is still measured from the crosswalk edge. For a child protection zone it is the zone radius.
 */
data class Facility(
    val id: String,
    val type: ZoneType,
    val name: String,
    val pos: LatLon,
    val extraM: Double = 0.0,
    val source: String = "",
) {
    val radiusM: Double get() = type.legalM + extraM
}

enum class Verdict(val label: String) { INSIDE("안"), NEAR("가까이일 수 있음") }

data class ZoneHit(
    val facility: Facility,
    val distanceM: Double,
    val radiusM: Double,
    val marginM: Double,
    val verdict: Verdict,
) {
    /** Place key for cooldown and the silence list. */
    val placeKey: String get() = facility.id

    /** Distance from the facility's edge (for a crosswalk, from the stripes), compared with the legal distance. */
    val edgeDistanceM: Double get() = maxOf(0.0, distanceM - facility.extraM)

    /** "소화전 2.9m" or "횡단보도 가장자리에서 0.9m", for logs and the screen. */
    fun describe(): String {
        val where = if (facility.type == ZoneType.CROSSWALK) "${facility.type.label} 가장자리에서" else facility.type.label
        return "%s %.1fm".format(where, edgeDistanceM)
    }
}

data class Judgment(
    val at: LatLon,
    val marginM: Double,
    /** Every hit within R + m, best first. */
    val hits: List<ZoneHit>,
    /** Nearest facility of any type within the lookup cells, even when it is out of range. */
    val nearest: Pair<Facility, Double>?,
    val elapsedNanos: Long,
) {
    val top: ZoneHit? get() = hits.firstOrNull()
}

/** Grid of about 150 m cells; a lookup reads the point's cell and its 8 neighbors. */
class ZoneIndex(facilities: List<Facility>, private val cellM: Double = 150.0) {
    private val points = facilities.filter { it.type != ZoneType.CHILD_ZONE }
    private val areas = facilities.filter { it.type == ZoneType.CHILD_ZONE }
    private val lat0 = if (facilities.isEmpty()) 37.55 else facilities.map { it.pos.lat }.average()
    private val dLat = cellM / 111_320.0
    private val dLon = cellM / (111_320.0 * cos(Math.toRadians(lat0)))
    private val cells: Map<Long, List<Facility>> = points.groupBy { key(cellOf(it.pos)) }

    val size: Int get() = points.size + areas.size

    private fun cellOf(p: LatLon) = Pair(floor(p.lat / dLat).toLong(), floor(p.lon / dLon).toLong())
    private fun key(c: Pair<Long, Long>) = (c.first shl 32) xor (c.second and 0xffffffffL)

    fun candidates(p: LatLon): List<Facility> {
        val (cy, cx) = cellOf(p)
        val out = ArrayList<Facility>()
        for (dy in -1L..1L) for (dx in -1L..1L) {
            cells[key(Pair(cy + dy, cx + dx))]?.let { out.addAll(it) }
        }
        out.addAll(areas)
        return out
    }
}

/**
 * Rule (plan section 5): d is the distance to a facility, R its legal distance, m the location error margin
 * (5 m by default, or the reported accuracy). d <= R is INSIDE, R < d <= R + m is NEAR, otherwise silent.
 * Ranking when several apply: INSIDE before NEAR, then the larger fine, then type priority, then distance.
 */
class ZoneJudge(private val index: ZoneIndex, val defaultMarginM: Double = 5.0) {

    fun marginFor(accuracyM: Double?): Double =
        if (accuracyM != null && accuracyM in 1.0..50.0) accuracyM else defaultMarginM

    fun judge(p: LatLon, accuracyM: Double? = null): Judgment {
        val t0 = System.nanoTime()
        val m = marginFor(accuracyM)
        val hits = ArrayList<ZoneHit>(4)
        var nearest: Pair<Facility, Double>? = null
        for (f in index.candidates(p)) {
            val d = Geo.distanceM(p, f.pos)
            if (nearest == null || d < nearest.second) nearest = f to d
            val r = f.radiusM
            val verdict = when {
                d <= r -> Verdict.INSIDE
                d <= r + m -> Verdict.NEAR
                else -> null
            }
            if (verdict != null) hits.add(ZoneHit(f, d, r, m, verdict))
        }
        hits.sortWith(
            compareBy<ZoneHit> { it.verdict.ordinal }
                .thenByDescending { it.facility.type.rankFine }
                .thenByDescending { it.facility.type.typePriority }
                .thenBy { it.distanceM }
        )
        return Judgment(p, m, hits, nearest, System.nanoTime() - t0)
    }
}
