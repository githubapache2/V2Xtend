package org.opentrafficmap.receiver

import android.content.Context
import androidx.core.content.edit

/**
 * Thin SharedPreferences wrapper for the small set of values the app keeps
 * across sessions. Keys are namespaced under [PREFS_NAME].
 */
object Prefs {
    private const val PREFS_NAME = "v2x2map"

    private const val KEY_LEGAL_ACCEPTED = "legal_accepted"
    private const val KEY_FOLLOW_ENABLED = "follow_enabled"
    private const val KEY_MQTT_ENABLED   = "mqtt_enabled"
    private const val KEY_MQTT_BROKER    = "mqtt_broker"   // legacy single-broker key
    private const val KEY_MQTT_BROKERS   = "mqtt_brokers"  // multi-broker: one URL per line
    private const val KEY_NODE_ID        = "node_id"
    private const val KEY_AUDIO_FEEDBACK  = "audio_feedback"
    private const val KEY_MARKER_TTL_MIN  = "marker_ttl_min"
    private const val KEY_SPAT_LIGHT      = "spat_light"
    private const val KEY_KEEP_SCREEN_ON  = "keep_screen_on"
    private const val KEY_MAP_LAYER       = "map_layer"
    private const val KEY_MQTT_FILTER     = "mqtt_filter"
    private const val KEY_FORCE_DARK      = "force_dark_mode"
    private const val KEY_OWN_TRACK       = "own_track"
    private const val KEY_COMPASS_MODE    = "compass_mode"
    private const val KEY_ROADWORKS_ENABLED = "roadworks_enabled"
    private const val KEY_DWD_WARNINGS_ENABLED = "dwd_warnings_enabled"

    const val DEFAULT_MARKER_TTL_MIN = 60   // 1 hour
    private val OLD_DEFAULTS = setOf(5, 1440) // values that should be migrated to new default
    const val MAX_MARKER_TTL_MIN     = 999_999

    fun legalAccepted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LEGAL_ACCEPTED, false)

    fun setLegalAccepted(ctx: Context, accepted: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_LEGAL_ACCEPTED, accepted) }

    fun followEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FOLLOW_ENABLED, false)

    fun setFollowEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_FOLLOW_ENABLED, on) }

    fun mqttEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MQTT_ENABLED, false)

    fun setMqttEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_MQTT_ENABLED, on) }

    /** Returns the list of broker URLs. Migrates from the legacy single-broker key on first call. */
    fun mqttBrokerList(ctx: Context): List<String> {
        val stored = prefs(ctx).getString(KEY_MQTT_BROKERS, null)
        if (stored != null) return stored.lines().filter { it.isNotBlank() }
        // Migrate: read old single-URL pref and promote it to the list
        val legacy = prefs(ctx).getString(KEY_MQTT_BROKER, null)
            ?: ctx.getString(R.string.default_mqtt_broker)
        return listOf(legacy)
    }

    fun setMqttBrokerList(ctx: Context, urls: List<String>) =
        prefs(ctx).edit { putString(KEY_MQTT_BROKERS, urls.filter { it.isNotBlank() }.joinToString("\n")) }

    // Legacy accessors kept for migration path above
    fun mqttBroker(ctx: Context): String = mqttBrokerList(ctx).firstOrNull() ?: ctx.getString(R.string.default_mqtt_broker)
    fun setMqttBroker(ctx: Context, url: String) = setMqttBrokerList(ctx, listOf(url))

    fun nodeId(ctx: Context): String =
        prefs(ctx).getString(KEY_NODE_ID, null)
            ?: ctx.getString(R.string.default_node_id)

    fun setNodeId(ctx: Context, id: String) =
        prefs(ctx).edit { putString(KEY_NODE_ID, id) }

    fun audioFeedback(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUDIO_FEEDBACK, false)

    fun setAudioFeedback(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_AUDIO_FEEDBACK, on) }

    fun markerTtlMinutes(ctx: Context): Int {
        val p = prefs(ctx)
        val stored = p.getInt(KEY_MARKER_TTL_MIN, -1)
        if (stored == -1 || stored in OLD_DEFAULTS) {
            p.edit().remove(KEY_MARKER_TTL_MIN).apply()
            return DEFAULT_MARKER_TTL_MIN
        }
        return stored.coerceIn(1, MAX_MARKER_TTL_MIN)
    }

    fun setMarkerTtlMinutes(ctx: Context, minutes: Int) =
        prefs(ctx).edit { putInt(KEY_MARKER_TTL_MIN, minutes.coerceIn(1, MAX_MARKER_TTL_MIN)) }

    fun spatLightEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SPAT_LIGHT, true)

    fun setSpatLightEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_SPAT_LIGHT, on) }

    fun keepScreenOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_KEEP_SCREEN_ON, on) }

    fun mapLayer(ctx: Context): String =
        prefs(ctx).getString(KEY_MAP_LAYER, "MAPNIK") ?: "MAPNIK"

    fun setMapLayer(ctx: Context, layer: String) =
        prefs(ctx).edit { putString(KEY_MAP_LAYER, layer) }

    fun mqttFilterTypes(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_MQTT_FILTER, null)
            ?: ItsG5Decoder.MsgType.values().map { it.name }.toSet()

    fun setMqttFilterTypes(ctx: Context, types: Set<String>) =
        prefs(ctx).edit { putStringSet(KEY_MQTT_FILTER, types) }

    fun forceDarkMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FORCE_DARK, false)

    fun setForceDarkMode(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_FORCE_DARK, on) }

    fun ownTrackEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_OWN_TRACK, false)

    fun setOwnTrackEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_OWN_TRACK, on) }

    fun compassMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_COMPASS_MODE, false)

    fun setCompassMode(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_COMPASS_MODE, on) }

    fun roadworksEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ROADWORKS_ENABLED, false)

    fun setRoadworksEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_ROADWORKS_ENABLED, on) }

    fun dwdWarningsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DWD_WARNINGS_ENABLED, false)

    fun setDwdWarningsEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_DWD_WARNINGS_ENABLED, on) }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
