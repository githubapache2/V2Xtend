package org.opentrafficmap.receiver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Client for the Autobahn GmbH public traffic API (verkehr.autobahn.de).
 * Ported from Android to `:shared` for iOS/Android parity (M5).
 *
 * Five point services share one response shape. `coordinate.lat`/`long` may be
 * JSON numbers or strings — [optDouble] accepts both.
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

    suspend fun fetchTrafficWarnings(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "warning", "warning")

    suspend fun fetchParkingLorry(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "parking_lorry", "parking_lorry")

    suspend fun fetchChargingStations(roadIds: List<String>): List<PointEvent> =
        fetchPointEvents(roadIds, "electric_charging_station", "electric_charging_station")

    private suspend fun fetchPointEvents(
        roadIds: List<String>,
        servicePath: String,
        jsonKey: String,
    ): List<PointEvent> {
        val out = mutableListOf<PointEvent>()
        for (roadId in roadIds) {
            try {
                out.addAll(fetchForRoad(roadId, servicePath, jsonKey))
            } catch (_: Exception) {
                // Skip one road — don't blank the whole overlay.
            }
        }
        return out
    }

    private suspend fun fetchForRoad(
        roadId: String,
        servicePath: String,
        jsonKey: String,
    ): List<PointEvent> {
        val body = SharedHttp.getText("$BASE/${roadId.trim()}/services/$servicePath")
        val root = SharedHttp.json.parseToJsonElement(body).jsonObject
        val arr = root.optArray(jsonKey) ?: return emptyList()
        val result = mutableListOf<PointEvent>()
        for (i in 0 until arr.size) {
            val o = arr[i].asObject() ?: continue
            val coord = o.optObject("coordinate") ?: continue
            val lat = coord.optDouble("lat") ?: continue
            val lon = coord.optDouble("long") ?: continue
            result.add(
                PointEvent(
                    id = o.optString("identifier", "$roadId-$i"),
                    title = o.optString("title", roadId),
                    subtitle = o.optString("subtitle").trim(),
                    lat = lat,
                    lon = lon,
                    description = joinStringArray(o.optArray("description")),
                    isBlocked = o.optString("isBlocked", "false") == "true",
                )
            )
        }
        return result
    }

    private fun joinStringArray(arr: JsonArray?): String {
        if (arr == null) return ""
        return (0 until arr.size).joinToString("\n") { i ->
            (arr[i] as? JsonPrimitive)?.contentOrNull ?: ""
        }
    }
}
