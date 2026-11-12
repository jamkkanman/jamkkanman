package app.jamkkanman.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    /** Mean Earth radius in meters. */
    const val EARTH_R = 6_371_008.8

    /** Great circle distance in meters (haversine). */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(s)))
    }

    /** Moves a point by the given east and north offsets in meters. */
    fun offset(p: LatLon, eastM: Double, northM: Double): LatLon {
        val dLat = northM / EARTH_R
        val dLon = eastM / (EARTH_R * cos(Math.toRadians(p.lat)))
        return LatLon(p.lat + Math.toDegrees(dLat), p.lon + Math.toDegrees(dLon))
    }
}
