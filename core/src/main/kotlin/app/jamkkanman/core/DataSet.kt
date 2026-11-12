package app.jamkkanman.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The embedded public data (app/src/main/assets/data/facilities.json): one JSON array with
 * HYDRANT, BUS_STOP, CROSSWALK, CHILD_ZONE and PARKING rows. Built by tools/build_dataset.py.
 */
class DataSet(val facilities: List<Facility>, val parking: List<ParkingLot>) {

    fun counts(): Map<String, Int> =
        facilities.groupingBy { it.type.name }.eachCount() + ("PARKING" to parking.size)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Crosswalk length across the road when the data has none (a two-lane street). */
        const val DEFAULT_CROSSWALK_LENGTH_M = 8.0

        fun parse(text: String): DataSet {
            val arr: JsonArray = json.parseToJsonElement(text).let {
                if (it is JsonArray) it else it.jsonObject["items"]!!.jsonArray
            }
            val facilities = ArrayList<Facility>()
            val lots = ArrayList<ParkingLot>()
            for (e in arr) {
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
                val pos = LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double)
                val id = s("id") ?: continue
                val name = s("name") ?: ""
                val source = s("source") ?: ""
                when (s("type")) {
                    "HYDRANT" -> facilities.add(Facility(id, ZoneType.HYDRANT, name, pos, 0.0, source))
                    "BUS_STOP" -> facilities.add(Facility(id, ZoneType.BUS_STOP, name, pos, 0.0, source))
                    "CROSSWALK" -> {
                        val len = d("lengthM")?.takeIf { it in 2.0..60.0 } ?: DEFAULT_CROSSWALK_LENGTH_M
                        facilities.add(Facility(id, ZoneType.CROSSWALK, name, pos, len / 2.0, source))
                    }
                    "CHILD_ZONE" -> facilities.add(Facility(id, ZoneType.CHILD_ZONE, name, pos, d("radiusM") ?: 300.0, source))
                    "PARKING" -> lots.add(parkingLot(o, id, name, pos, source))
                }
            }
            return DataSet(facilities, lots)
        }

        private fun parkingLot(o: JsonObject, id: String, name: String, pos: LatLon, source: String): ParkingLot {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            return ParkingLot(
                id = id,
                name = name,
                pos = pos,
                address = s("address") ?: "",
                mgmtNo = s("mgmtNo") ?: id,
                hours = WeeklyHours(OpenRange.parse(s("weekday")), OpenRange.parse(s("saturday")), OpenRange.parse(s("holiday"))),
                feeText = s("feeBasic") ?: "",
                capacity = o["capacity"]?.jsonPrimitive?.intOrNull,
                source = source,
            )
        }
    }
}
