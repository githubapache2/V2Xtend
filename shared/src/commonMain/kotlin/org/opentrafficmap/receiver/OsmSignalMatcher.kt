package org.opentrafficmap.receiver

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geo helper: which OSM traffic signals lie within [thresholdM] of any
 * live SPATEM RSU position (Android OsmTrafficSignalsLayer.updateActive parity).
 */
object OsmSignalMatcher {
    fun activeSignalIds(
        signals: List<OverpassApi.TrafficSignal>,
        rsuPositions: List<LatLon>,
        thresholdM: Double = OverlayRegion.OSM_SIGNAL_ACTIVE_M,
    ): Set<Long> {
        if (signals.isEmpty() || rsuPositions.isEmpty()) return emptySet()
        val active = mutableSetOf<Long>()
        for (s in signals) {
            for (rsu in rsuPositions) {
                if (haversineM(s.lat, s.lon, rsu.lat, rsu.lon) <= thresholdM) {
                    active.add(s.id)
                    break
                }
            }
        }
        return active
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = toRadians(lat1)
        val p2 = toRadians(lat2)
        val dp = toRadians(lat2 - lat1)
        val dl = toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun toRadians(deg: Double): Double = deg * PI / 180.0
}
