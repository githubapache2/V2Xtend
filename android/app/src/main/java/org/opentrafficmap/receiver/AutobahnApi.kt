package org.opentrafficmap.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the Autobahn GmbH's public traffic API (verkehr.autobahn.de).
 *
 * First traffic-data-source integration (Redesign Phase 2, Punkt 2 — see
 * CLAUDE.md), roadworks only so far, not the other service types (parking,
 * warnings, closures, webcams, charging stations) the same API also exposes
 * under an identical /{roadId}/services/{type} shape.
 *
 * No API key / auth required (verified against the live API, not just the
 * OpenAPI spec — https://github.com/bundesAPI/autobahn-api). No documented
 * rate limit either, but this client still only fetches a small, curated
 * list of road IDs (see MainActivity.ROADWORK_ROAD_IDS) rather than the
 * ~150 roads nationwide: each road's roadworks list is nationwide-per-road
 * (not filtered to a viewport/region — the API has no geo/bbox query
 * parameter at all), and busy roads return hundreds of entries at
 * 100-300+ KB each. Fetching every road up front isn't practical; see
 * CLAUDE.md for the fuller scaling discussion.
 */
object AutobahnApi {

    private const val BASE = "https://verkehr.autobahn.de/o/autobahn"

    data class Roadwork(
        val id: String,
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
        val description: String,
        val isBlocked: Boolean,
    )

    /** Fetches and combines roadworks for all given road IDs. Network calls run
     *  sequentially on Dispatchers.IO; a failure for one road is skipped (logged
     *  to logcat, not surfaced as an error) rather than failing the whole batch —
     *  a temporary glitch on one road shouldn't blank out the others. */
    suspend fun fetchRoadworks(roadIds: List<String>): List<Roadwork> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Roadwork>()
        for (roadId in roadIds) {
            try {
                out.addAll(fetchRoadworksForRoad(roadId))
            } catch (e: Exception) {
                android.util.Log.w("AutobahnApi", "fetchRoadworks($roadId) failed: ${e.message}")
            }
        }
        out
    }

    private fun fetchRoadworksForRoad(roadId: String): List<Roadwork> {
        val url = URL("$BASE/${roadId.trim()}/services/roadworks")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        conn.requestMethod  = "GET"
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val arr  = root.optJSONArray("roadworks") ?: return emptyList()
            val result = mutableListOf<Roadwork>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val coord = o.optJSONObject("coordinate") ?: continue
                val lat = coord.optDouble("lat", Double.NaN)
                val lon = coord.optDouble("long", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val descArr = o.optJSONArray("description")
                val desc = if (descArr != null) {
                    (0 until descArr.length()).joinToString("\n") { descArr.optString(it, "") }
                } else ""
                result.add(
                    Roadwork(
                        id          = o.optString("identifier", "$roadId-$i"),
                        title       = o.optString("title", roadId),
                        subtitle    = o.optString("subtitle", "").trim(),
                        lat         = lat,
                        lon         = lon,
                        description = desc,
                        isBlocked   = o.optString("isBlocked", "false") == "true",
                    )
                )
            }
            return result
        } finally {
            conn.disconnect()
        }
    }
}
