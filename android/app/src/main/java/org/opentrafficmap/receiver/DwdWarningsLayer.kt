package org.opentrafficmap.receiver

import android.content.Context
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

/**
 * Renders DwdWarningsApi.Warning entries as filled map polygons — second
 * traffic/environment overlay (Redesign Phase 2, Punkt 2). Structurally a
 * sibling to RoadworksLayer (same show()/clear() shape, same "replace
 * everything on refresh" model) but draws osmdroid Polygon overlays instead
 * of Markers, since warnings are areas, not points.
 */
class DwdWarningsLayer(private val map: MapView, private val context: Context) {

    private val polygons = mutableListOf<Polygon>()

    fun show(warnings: List<DwdWarningsApi.Warning>) {
        clear()
        for (w in warnings) {
            val (fill, stroke) = colorsForLevel(w.level)
            for (region in w.regions) {
                val poly = Polygon(map).apply {
                    points = region.map { GeoPoint(it.lat, it.lon) }
                    fillColor    = fill
                    strokeColor  = stroke
                    strokeWidth  = 3f
                    title        = w.headline
                    snippet      = w.description
                }
                map.overlays.add(poly)
                polygons.add(poly)
            }
        }
        map.invalidate()
    }

    fun clear() {
        for (p in polygons) map.overlays.remove(p)
        polygons.clear()
        map.invalidate()
    }

    /** DWD's own level scale isn't the documented 1-4 "Wetterwarnung ...
     *  Extreme Unwetterwarnung" everywhere — a "level: 50" was observed
     *  live for heat warnings (see CLAUDE.md), outside that range. Kept
     *  simple rather than guessing at an undocumented full scale: >=3 reads
     *  as "severe" (red), everything else as "moderate" (amber). */
    private fun colorsForLevel(level: Int): Pair<Int, Int> =
        if (level >= 3) FILL_SEVERE to STROKE_SEVERE else FILL_MODERATE to STROKE_MODERATE

    companion object {
        private const val FILL_MODERATE   = 0x40FFA726 // amber, low alpha
        private const val STROKE_MODERATE = 0xFFFF9800.toInt()
        private const val FILL_SEVERE     = 0x50E53935 // red, low alpha
        private const val STROKE_SEVERE   = 0xFFD32F2F.toInt()
    }
}
