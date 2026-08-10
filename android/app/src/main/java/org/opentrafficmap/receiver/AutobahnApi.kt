package org.opentrafficmap.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the Autobahn GmbH's public traffic API (verkehr.autobahn.de).
 *
 * Covers all five point-based service types the API exposes under an
 * identical /{roadId}/services/{type} shape — roadworks, closures, traffic
 * warnings, lorry parking, and EV charging stations (Redesign Phase 2,
 * Punkt 2, see CLAUDE.md — roadworks landed first as the architecture pilot,
 * the other four followed once that held up). Webcams are the one service
 * type under this API *not* covered — not a point-of-interest overlay in
 * the same sense, out of scope.
 *
 * All five share one response shape (checked live per type before adding
 * it, not assumed from the roadworks schema alone — see CLAUDE.md for the
 * per-type notes): a JSON object with one array field (key name matches the
 * service path, singular for closure/warning/parking_lorry/
 * electric_charging_station, "roadworks" for roadworks specifically) of
 * entries with `coordinate.lat`/`coordinate.long`, `title`, `subtitle`,
 * `description` (string array), `isBlocked`. One real inconsistency worth
 * knowing about: `coordinate.lat`/`long` are JSON *numbers* for roadworks/
 * closure/warning but JSON *strings* for parking_lorry/
 * electric_charging_station — org.json's optDouble() coerces both cases
 * transparently, so this doesn't need type-specific handling, just noting
 * it since it's the kind of inconsistency that silently breaks a stricter
 * JSON mapper.
 *
 * No API key / auth required (verified against the live API, not just the
 * OpenAPI spec — https://github.com/bundesAPI/autobahn-api). No documented
 * rate limit. No gzip either (checked per type, unlike DwdWarningsApi).
 *
 * Still only fetches a small, curated list of road IDs (see
 * MainActivity.ROADWORK_ROAD_IDS) rather than the ~150 roads nationwide for
 * all five types: each road's list is nationwide-per-road (not filtered to
 * a viewport/region — no geo/bbox query parameter anywhere in this API),
 * and busy roads return hundreds of entries at 100-300+ KB each. See
 * CLAUDE.md for the fuller scaling discussion (and DwdWarningsApi for a
 * contrasting case where this wasn't necessary).
 */
object AutobahnApi {

    private const val BASE = "https://verkehr.autobahn.de/o/autobahn"

    data class PointEvent(
        val id: String,
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
        val description: String,
        val isBlocked: Boolean,
    )

    suspend fun fetchRoadworks(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "roadworks", "roadworks")

    suspend fun fetchClosures(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "closure", "closure")

    /** Named "traffic warnings" (not just "warnings") to avoid confusion with
     *  DwdWarningsApi's weather warnings — same word, unrelated APIs. */
    suspend fun fetchTrafficWarnings(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "warning", "warning")

    suspend fun fetchParkingLorry(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "parking_lorry", "parking_lorry")

    suspend fun fetchChargingStations(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "electric_charging_station", "electric_charging_station")

    /** Fetches and combines one service type for all given road IDs. Network
     *  calls run sequentially on Dispatchers.IO; a failure for one road is
     *  skipped (logged to logcat, not surfaced as an error) rather than
     *  failing the whole batch — a temporary glitch on one road shouldn't
     *  blank out the others. */
    private suspend fun fetchPointEvents(
        roadIds: List<String>,
        servicePath: String,
        jsonKey: String,
    ): List<PointEvent> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PointEvent>()
        for (roadId in roadIds) {
            try {
                out.addAll(fetchForRoad(roadId, servicePath, jsonKey))
            } catch (e: Exception) {
                android.util.Log.w("AutobahnApi", "fetch($servicePath, $roadId) failed: ${e.message}")
            }
        }
        out
    }

    private fun fetchForRoad(roadId: String, servicePath: String, jsonKey: String): List<PointEvent> {
        val url = URL("$BASE/${roadId.trim()}/services/$servicePath")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        conn.requestMethod  = "GET"
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val arr  = root.optJSONArray(jsonKey) ?: return emptyList()
            val result = mutableListOf<PointEvent>()
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
                    PointEvent(
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
