package org.opentrafficmap.receiver

import kotlinx.serialization.json.jsonObject

/**
 * DWD WarnWetter gemeinde_warnings_v2 feed — nationwide polygons in one call.
 * Gzip is handled by Ktor ContentEncoding (Android HttpURLConnection did not).
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
        /** One entry per affected region; each region is a closed ring. */
        val regions: List<List<LatLon>>,
    )

    suspend fun fetchWarnings(): List<Warning> {
        return try {
            val body = SharedHttp.getText(URL)
            val root = SharedHttp.json.parseToJsonElement(body).jsonObject
            val arr = root.optArray("warnings") ?: return emptyList()
            val out = mutableListOf<Warning>()
            for (i in 0 until arr.size) {
                val o = arr[i].asObject() ?: continue
                val regionsArr = o.optArray("regions") ?: continue
                val regions = mutableListOf<List<LatLon>>()
                for (j in 0 until regionsArr.size) {
                    val flat = regionsArr[j].asObject()?.optArray("polygon") ?: continue
                    val ring = mutableListOf<LatLon>()
                    var k = 0
                    while (k + 1 < flat.size) {
                        val lat = flat.optDoubleAt(k) ?: break
                        val lon = flat.optDoubleAt(k + 1) ?: break
                        ring.add(LatLon(lat, lon))
                        k += 2
                    }
                    if (ring.isNotEmpty()) regions.add(ring)
                }
                if (regions.isEmpty()) continue
                out.add(
                    Warning(
                        id = o.optString("warnId", "dwd-$i"),
                        event = o.optString("event", "?"),
                        headline = o.optString("headLine", o.optString("event", "?")),
                        description = o.optString("description"),
                        level = o.optInt("level"),
                        startMs = o.optLong("start"),
                        endMs = o.optLong("end"),
                        regions = regions,
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
