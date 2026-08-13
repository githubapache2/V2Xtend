package org.opentrafficmap.receiver

/**
 * Flat DTOs for Swift interop — nested List&lt;List&lt;LatLon&gt;&gt; bridges poorly.
 */
data class OverlayPointDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    /** roadworks|closures|warnings|parking|charging|osm|osmActive */
    val kind: String,
)

data class OverlayPolygonDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val severe: Boolean,
    /** Closed rings; each ring is lat,lon,lat,lon,… */
    val ringsFlat: List<DoubleArray>,
)

object OverlayDtoMapper {
    fun points(events: List<AutobahnApi.PointEvent>, kind: String): List<OverlayPointDto> =
        events.map {
            OverlayPointDto(
                id = "$kind-${it.id}",
                title = it.title,
                subtitle = listOf(it.subtitle, it.description).filter { s -> s.isNotBlank() }
                    .joinToString("\n"),
                lat = it.lat,
                lon = it.lon,
                kind = kind,
            )
        }

    fun signals(
        signals: List<OverpassApi.TrafficSignal>,
        activeIds: Set<Long>,
    ): List<OverlayPointDto> =
        signals.map { s ->
            val on = s.id in activeIds
            OverlayPointDto(
                id = "osm-${s.id}",
                title = if (on) "Traffic light (C-ITS)" else "Traffic light",
                subtitle = if (on) "SPATEM nearby" else "OSM",
                lat = s.lat,
                lon = s.lon,
                kind = if (on) "osmActive" else "osm",
            )
        }

    /** Rematch cached OSM DTOs against RSU positions — no network. */
    fun rematchOsm(
        cached: List<OverlayPointDto>,
        rsuLats: DoubleArray,
        rsuLons: DoubleArray,
    ): List<OverlayPointDto> {
        val signals = cached.mapNotNull { dto ->
            val id = dto.id.removePrefix("osm-").toLongOrNull() ?: return@mapNotNull null
            OverpassApi.TrafficSignal(id = id, lat = dto.lat, lon = dto.lon)
        }
        val rsus = rsuLats.indices.map { LatLon(rsuLats[it], rsuLons[it]) }
        return signals(signals, OsmSignalMatcher.activeSignalIds(signals, rsus))
    }

    fun polygons(warnings: List<DwdWarningsApi.Warning>): List<OverlayPolygonDto> =
        warnings.map { w ->
            OverlayPolygonDto(
                id = w.id,
                title = w.headline,
                subtitle = w.description,
                severe = w.level >= 3,
                ringsFlat = w.regions.map { ring ->
                    DoubleArray(ring.size * 2).also { arr ->
                        ring.forEachIndexed { i, ll ->
                            arr[i * 2] = ll.lat
                            arr[i * 2 + 1] = ll.lon
                        }
                    }
                },
            )
        }
}
