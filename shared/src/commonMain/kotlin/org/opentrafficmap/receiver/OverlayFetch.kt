package org.opentrafficmap.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Swift-friendly overlay fetch — returns flat DTOs on the main dispatcher.
 */
object OverlayFetch {
    private val scope = MainScope()

    fun roadworks(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.points(AutobahnApi.fetchRoadworks(OverlayRegion.ROAD_IDS), "roadworks")
    }

    fun closures(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.points(AutobahnApi.fetchClosures(OverlayRegion.ROAD_IDS), "closures")
    }

    fun trafficWarnings(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.points(AutobahnApi.fetchTrafficWarnings(OverlayRegion.ROAD_IDS), "warnings")
    }

    fun parkingLorry(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.points(AutobahnApi.fetchParkingLorry(OverlayRegion.ROAD_IDS), "parking")
    }

    fun chargingStations(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.points(AutobahnApi.fetchChargingStations(OverlayRegion.ROAD_IDS), "charging")
    }

    fun dwdWarnings(callback: (List<OverlayPolygonDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.polygons(DwdWarningsApi.fetchWarnings())
    }

    fun osmSignals(callback: (List<OverlayPointDto>) -> Unit) = launch(callback) {
        OverlayDtoMapper.signals(OverpassApi.fetchTrafficSignals(), emptySet())
    }

    /** Rematch already-fetched OSM points — no network round-trip. */
    fun rematchOsm(
        cached: List<OverlayPointDto>,
        rsuLats: DoubleArray,
        rsuLons: DoubleArray,
        callback: (List<OverlayPointDto>) -> Unit,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                OverlayDtoMapper.rematchOsm(cached, rsuLats, rsuLons)
            }
            callback(result)
        }
    }

    private fun <T> launch(callback: (List<T>) -> Unit, block: suspend () -> List<T>) {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    block()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            callback(result)
        }
    }
}
