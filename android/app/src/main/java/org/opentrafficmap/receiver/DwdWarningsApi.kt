package org.opentrafficmap.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Client for the DWD's (Deutscher Wetterdienst) public severe-weather-warnings
 * feed. Second traffic/environment data source (Redesign Phase 2, Punkt 2 —
 * see CLAUDE.md), deliberately picked to be structurally different from the
 * first (Autobahn roadworks) rather than a second instance of the same shape:
 * warnings are polygon *areas*, not point markers, and — unlike the Autobahn
 * API — this single endpoint returns the complete nationwide warning list in
 * one call (no per-region/per-road fetching needed, no curated region list
 * required the way AutobahnApi needed one).
 *
 * Endpoint reverse-engineered from the DWD's own WarnWetter app
 * (github.com/bundesAPI/dwd-api documents the URL, not the schema — the
 * field layout below was read directly off a live response). No API key.
 * No documented rate limit; response carries `Cache-Control: max-age=30`
 * and an explicit "next refresh" timestamp, i.e. the data itself is only
 * meaningfully new every ~30s-2min regardless of how often it's polled.
 *
 * One real endpoint quirk: despite `Content-Type: application/json`, the
 * body is gzip-compressed (`Content-Encoding: gzip`) and HttpURLConnection
 * does NOT transparently decompress that — has to be unwrapped manually.
 */
object DwdWarningsApi {

    private const val URL =
        "https://s3.eu-central-1.amazonaws.com/app-prod-static.warnwetter.de/v16/gemeinde_warnings_v2.json"

    data class Warning(
        val id: String,
        val event: String,
        val headline: String,
        val description: String,
        val level: Int,
        val startMs: Long,
        val endMs: Long,
        /** One entry per affected region; each region is a closed ring of (lat, lon) points. */
        val regions: List<List<Pair<Double, Double>>>,
    )

    suspend fun fetchWarnings(): List<Warning> = withContext(Dispatchers.IO) {
        val conn = URL(URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        conn.requestMethod  = "GET"
        try {
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding == "gzip") GZIPInputStream(raw) else raw
            val body = stream.bufferedReader().readText()
            val root = JSONObject(body)
            val arr  = root.optJSONArray("warnings") ?: return@withContext emptyList()
            val out  = mutableListOf<Warning>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val regionsArr = o.optJSONArray("regions") ?: continue
                val regions = mutableListOf<List<Pair<Double, Double>>>()
                for (j in 0 until regionsArr.length()) {
                    val flat = regionsArr.optJSONObject(j)?.optJSONArray("polygon") ?: continue
                    val ring = mutableListOf<Pair<Double, Double>>()
                    var k = 0
                    while (k + 1 < flat.length()) {
                        ring.add(flat.optDouble(k) to flat.optDouble(k + 1))
                        k += 2
                    }
                    if (ring.isNotEmpty()) regions.add(ring)
                }
                if (regions.isEmpty()) continue
                out.add(
                    Warning(
                        id          = o.optString("warnId", "dwd-$i"),
                        event       = o.optString("event", "?"),
                        headline    = o.optString("headLine", o.optString("event", "?")),
                        description = o.optString("description", ""),
                        level       = o.optInt("level", 0),
                        startMs     = o.optLong("start", 0L),
                        endMs       = o.optLong("end", 0L),
                        regions     = regions,
                    )
                )
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("DwdWarningsApi", "fetchWarnings failed: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
