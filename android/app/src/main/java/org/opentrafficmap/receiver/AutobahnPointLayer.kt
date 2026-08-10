package org.opentrafficmap.receiver

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Renders AutobahnApi.PointEvent entries as map markers. Generic over icon +
 * tint so all five Autobahn-API point services (roadworks, closures, traffic
 * warnings, lorry parking, charging stations) share one implementation
 * instead of five near-identical copies — they're genuinely the same shape
 * (point + title + description), unlike DwdWarningsLayer's polygons, which
 * earned its own class because the underlying geometry is actually
 * different. MainActivity holds one instance per service type.
 *
 * Mirrors MarkerLayer's plain Marker + title/snippet pattern, and — learned
 * the hard way while building the roadworks pilot (see CLAUDE.md) — builds
 * a fresh mutate()'d Drawable per marker rather than sharing one instance
 * across all of them.
 */
class AutobahnPointLayer(
    private val map: MapView,
    private val context: Context,
    private val iconRes: Int,
    private val tintColor: Int,
) {

    private val markers = mutableListOf<Marker>()

    fun show(events: List<AutobahnApi.PointEvent>) {
        clear()
        for (ev in events) {
            val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate()
            DrawableCompat.setTint(icon, tintColor)
            val m = Marker(map).apply {
                position = GeoPoint(ev.lat, ev.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                this.icon = icon
                title     = ev.title
                snippet   = (if (ev.subtitle.isNotEmpty()) ev.subtitle + "\n" else "") + ev.description
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
}
