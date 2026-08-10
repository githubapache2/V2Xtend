package org.opentrafficmap.receiver

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Renders AutobahnApi.Roadwork entries as map markers — first traffic-data
 * overlay layer (Redesign Phase 2, Punkt 2), mirrors MarkerLayer's plain
 * osmdroid Marker + title/snippet pattern rather than introducing a new one.
 * Unlike MarkerLayer there's no live stream/TTL pruning here — the whole set
 * is replaced on every refresh() call (LayerPickerSheet toggle or app start).
 */
class RoadworksLayer(private val map: MapView, private val context: Context) {

    private val markers = mutableListOf<Marker>()

    fun show(roadworks: List<AutobahnApi.Roadwork>) {
        clear()
        for (rw in roadworks) {
            // A fresh mutate()'d drawable per marker, NOT one shared instance —
            // osmdroid mutates each Marker's icon bounds/position per draw call,
            // and reusing a single Drawable across many Markers produced a
            // corrupted giant smeared icon instead of many small ones (found
            // during on-device testing of this exact overlay).
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_marker_roadwork)!!.mutate()
            DrawableCompat.setTint(icon, ROADWORK_COLOR)
            val m = Marker(map).apply {
                position = GeoPoint(rw.lat, rw.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                this.icon = icon
                title     = rw.title
                snippet   = (if (rw.subtitle.isNotEmpty()) rw.subtitle + "\n" else "") + rw.description
            }
            map.overlays.add(m)
            markers.add(m)
        }
        map.invalidate()
    }

    fun clear() {
        for (m in markers) map.overlays.remove(m)
        markers.clear()
        map.invalidate()
    }

    companion object {
        private const val ROADWORK_COLOR = 0xFFE8A33D.toInt() // construction-sign orange
    }
}
