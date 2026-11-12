package app.jamkkanman.core.metrics

import app.jamkkanman.core.Facility
import app.jamkkanman.core.Geo
import app.jamkkanman.core.LatLon
import app.jamkkanman.core.ZoneType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zone rule accuracy under location noise (plan section 5, first metric).
 * 200 isolated facilities (100 hydrants with R = 5 m, 100 bus stops with R = 10 m); true points at
 * 2, 4, 6, 8, 12 and 20 m in random directions; Gaussian noise with the given standard deviation on each axis;
 * 50 repetitions per point. Truth is "inside" when the true distance is at most R.
 * Two readings of the verdict: INSIDE only, and INSIDE or NEAR (R + m).
 */
object ZoneAccuracy {
    data class Row(
        val sigmaM: Double,
        val marginM: Double,
        val strictPrecision: Double,
        val strictRecall: Double,
        val lenientPrecision: Double,
        val lenientRecall: Double,
        val samples: Int,
    )

    fun measure(
        sigmas: List<Double> = listOf(3.0, 5.0, 8.0),
        margins: List<Double> = listOf(0.0, 3.0, 5.0, 8.0),
        distances: List<Double> = listOf(2.0, 4.0, 6.0, 8.0, 12.0, 20.0),
        reps: Int = 50,
        seed: Int = 1,
    ): List<Row> {
        val base = LatLon(37.5665, 126.9780)
        val facilities = (0 until 200).map { i ->
            val type = if (i < 100) ZoneType.HYDRANT else ZoneType.BUS_STOP
            Facility("A$i", type, "", Geo.offset(base, (i % 20) * 500.0, (i / 20) * 500.0))
        }
        val rows = ArrayList<Row>()
        for (sigma in sigmas) for (m in margins) {
            val r = Random(seed) // same points and noise for every margin
            var tpS = 0; var fpS = 0; var fnS = 0
            var tpL = 0; var fpL = 0; var fnL = 0
            var n = 0
            for (f in facilities) for (d in distances) repeat(reps) {
                val a = r.nextDouble(0.0, 2 * Math.PI)
                val truePt = Geo.offset(f.pos, d * cos(a), d * sin(a))
                val measured = Geo.offset(truePt, r.nextGaussian() * sigma, r.nextGaussian() * sigma)
                val dm = Geo.distanceM(measured, f.pos)
                val R = f.radiusM
                val truth = d <= R
                val strict = dm <= R
                val lenient = dm <= R + m
                if (strict && truth) tpS++ else if (strict) fpS++ else if (truth) fnS++
                if (lenient && truth) tpL++ else if (lenient) fpL++ else if (truth) fnL++
                n++
            }
            fun ratio(a: Int, b: Int) = if (a + b == 0) 1.0 else a.toDouble() / (a + b)
            rows.add(Row(sigma, m, ratio(tpS, fpS), ratio(tpS, fnS), ratio(tpL, fpL), ratio(tpL, fnL), n))
        }
        return rows
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller with a seeded generator, so every run gives the same table.
        var u = 0.0
        while (u == 0.0) u = nextDouble()
        val v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * cos(2 * Math.PI * v)
    }
}
