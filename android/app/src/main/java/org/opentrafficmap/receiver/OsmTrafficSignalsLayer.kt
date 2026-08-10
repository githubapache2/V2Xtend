package org.opentrafficmap.receiver

import android.content.Context
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Renders OSM traffic-signal nodes, distinguishing ones currently matched to
 * a live SPATEM-broadcasting RSU ("V2X-active") from plain OSM-only ones.
 *
 * ## The OSM↔C-ITS matching approach (Redesign Phase 2, Punkt 2 "B-Plan")
 *
 * MAPEM (the message that would carry a real intersection reference point)
 * is not decoded anywhere in this codebase — only classified by BTP port,
 * payload never opened (SpatTemParser.kt only extracts the SPATEM phase
 * enum, not even the IntersectionReferenceID it reads past). Building that
 * decoder first was considered and deliberately deferred: this v1 instead
 * reuses the GeoNetworking source position already carried on every frame
 * (the same `lat`/`lon` MainActivity's existing 400m SPAT-light indicator
 * has used in production — see `spatRsus`/`updateSpatLight()`) as a stand-in
 * for "where this intersection roughly is". That's the RSU's own broadcast
 * position, not a surveyed intersection point — usually close, not
 * guaranteed identical. Same accepted tolerance as the existing feature,
 * not a new unknown. See CLAUDE.md for the full reasoning and the plan to
 * revisit with a real MAPEM decoder if this proves too imprecise in
 * practice.
 *
 * Static signal list comes from Overpass once per `show()` call; the
 * active/inactive split is recomputed independently (and cheaply — this is
 * a few hundred signals, not thousands) via `updateActive()` whenever live
 * SPATEM data changes, without re-fetching from Overpass.
 */
class OsmTrafficSignalsLayer(private val map: MapView, private val context: Context) {

    private var signals: List<OverpassApi.TrafficSignal> = emptyList()
    private val markers = mutableListOf<Marker>()

    fun show(newSignals: List<OverpassApi.TrafficSignal>) {
        signals = newSignals
        render(emptySet())
    }

    /** Recomputes which signals are within [thresholdMeters] of any position
     *  in [rsuPositions] (live, non-stale SPATEM RSUs) and re-renders with
     *  the distinct active icon for those. Cheap linear scan — fine at the
     *  few-hundred-signal scale a curated-region bbox produces; would need
     *  a spatial index before this scales to a full-country signal set. */
    fun updateActive(rsuPositions: List<Pair<Double, Double>>, thresholdMeters: Float = 400f) {
        if (signals.isEmpty()) return
        val results = FloatArray(2)
        val activeIds = signals.filter { sig ->
            rsuPositions.any { (lat, lon) ->
                Location.distanceBetween(sig.lat, sig.lon, lat, lon, results)
                results[0] <= thresholdMeters
            }
        }.mapTo(mutableSetOf()) { it.id }
        render(activeIds)
    }

    private fun render(activeIds: Set<Long>) {
        clearMarkersOnly()
        for (sig in signals) {
            val active = sig.id in activeIds
            val iconRes = if (active) R.drawable.ic_marker_trafficlight else R.drawable.ic_marker_dot
            val tint    = if (active) ItsG5Decoder.MsgType.SPATEM.color else DIM_GRAY
            val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate()
            DrawableCompat.setTint(icon, tint)
            val m = Marker(map).apply {
                position = GeoPoint(sig.lat, sig.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                this.icon = icon
                title = context.getString(
                    if (active) R.string.osm_signal_active else R.string.osm_signal_plain
                )
            }
            map.overlays.add(m)
            markers.add(m)
        }
        map.invalidate()
    }

    private fun clearMarkersOnly() {
        for (m in markers) map.overlays.remove(m)
        markers.clear()
    }

    fun clear() {
        signals = emptyList()
        clearMarkersOnly()
        map.invalidate()
    }

    companion object {
        private const val DIM_GRAY = 0xFF8B949E.toInt()
    }
}
