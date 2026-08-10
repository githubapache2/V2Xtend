package org.opentrafficmap.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the Overpass API (OpenStreetMap query service) — third traffic
 * data source (Redesign Phase 2, Punkt 2, see CLAUDE.md), fetches
 * `highway=traffic_signals` nodes as the static-map half of the OSM/C-ITS
 * traffic-light matching feature (see CLAUDE.md "B-Plan" section for the
 * live-matching design this feeds into).
 *
 * No API key. Documented soft limits: ~10,000 requests/day, ~1 GB/day
 * download, requests may queue up to 15s server-side under load (verified
 * live: the public instance returned a "server too busy" error once during
 * testing, succeeded on retry — build in tolerance for that, don't treat a
 * single failure as fatal).
 *
 * Same curated-region approach as AutobahnApi's road list, for the same
 * reason: Overpass *does* support arbitrary bounding boxes (unlike the
 * Autobahn API), but querying all of Germany at once would return an
 * impractical number of nodes for a first pass (865 signals in just the
 * ~15x15km Bonn-area box used here) — a real "whatever's on screen" query
 * bound to the map viewport is future work, not needed to prove out the
 * matching logic.
 */
object OverpassApi {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    // south, west, north, east — same Bonn/Cologne area as ROADWORK_ROAD_IDS.
    private const val BBOX = "50.65,7.05,50.80,7.20"

    data class TrafficSignal(val id: Long, val lat: Double, val lon: Double)

    suspend fun fetchTrafficSignals(): List<TrafficSignal> = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            node["highway"="traffic_signals"]($BBOX);
            out body;
        """.trimIndent()

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 20_000
        conn.requestMethod  = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val text = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(text)
            val arr  = root.optJSONArray("elements") ?: return@withContext emptyList()
            val out  = mutableListOf<TrafficSignal>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "node") continue
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(TrafficSignal(id = o.optLong("id", i.toLong()), lat = lat, lon = lon))
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("OverpassApi", "fetchTrafficSignals failed: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
