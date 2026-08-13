package org.opentrafficmap.receiver

import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.jsonObject

/**
 * Overpass API client — static `highway=traffic_signals` nodes for OSM/C-ITS matching.
 */
object OverpassApi {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    data class TrafficSignal(val id: Long, val lat: Double, val lon: Double)

    suspend fun fetchTrafficSignals(
        bbox: String = OverlayRegion.OVERPASS_BBOX,
    ): List<TrafficSignal> {
        val query = """
            [out:json][timeout:25];
            node["highway"="traffic_signals"]($bbox);
            out body;
        """.trimIndent()
        return try {
            val form = listOf("data" to query).formUrlEncode()
            val text = SharedHttp.postForm(ENDPOINT, form)
            val root = SharedHttp.json.parseToJsonElement(text).jsonObject
            val arr = root.optArray("elements") ?: return emptyList()
            val out = mutableListOf<TrafficSignal>()
            for (i in 0 until arr.size) {
                val o = arr[i].asObject() ?: continue
                if (o.optString("type") != "node") continue
                val lat = o.optDouble("lat") ?: continue
                val lon = o.optDouble("lon") ?: continue
                out.add(TrafficSignal(id = o.optLong("id", i.toLong()), lat = lat, lon = lon))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
