package org.opentrafficmap.receiver

/**
 * Curated Bonn/Köln region constants shared by Autobahn + Overpass overlays.
 * Same values as the Android v1 integration — nationwide eager load is not
 * practical for Autobahn (per-road nationwide lists) or Overpass (865 signals
 * in this ~15×15 km box alone).
 */
object OverlayRegion {
    /** Autobahn road IDs fetched for all five point-service overlays. */
    val ROAD_IDS: List<String> = listOf("A555", "A59", "A565", "A61", "A560")

    /** Overpass bbox: south, west, north, east. */
    const val OVERPASS_BBOX: String = "50.65,7.05,50.80,7.20"

    /** OSM signal considered "C-ITS active" when within this of a live SPATEM RSU. */
    const val OSM_SIGNAL_ACTIVE_M: Double = 400.0

    /** SPAT RSU staleness (ms) — shared with Ampel / OSM matching. */
    const val SPAT_RSU_STALE_MS: Long = 30_000L
}
