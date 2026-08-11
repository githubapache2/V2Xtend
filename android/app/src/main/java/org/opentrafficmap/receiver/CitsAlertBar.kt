package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * C-ITS alert bar (handoff redesign step 6, "Meldungsleiste") — one chip per
 * active message TYPE, not per message. A repeat of the same type bumps the
 * count and resets its 30s TTL; [tick] (called once a second from
 * MainActivity's existing rateRefresh loop) expires stale entries. See
 * handoff README "C-ITS-Meldungen" for the data-model sketch this follows.
 */
class CitsAlertBar(
    private val context: Context,
    private val barContainer: LinearLayout,
    private val chipContainer: LinearLayout,
    private val emptyText: TextView,
    private val chevron: ImageView,
    private val detailsContainer: LinearLayout,
) {
    private data class Alert(
        val type: ItsG5Decoder.MsgType,
        var text: String,
        var count: Int,
        var lastSeen: Long,
    )

    private val alerts = mutableListOf<Alert>()
    private var expanded = false

    init {
        barContainer.setOnClickListener { if (alerts.isNotEmpty()) toggleExpanded() }
        render()
    }

    fun onMessage(type: ItsG5Decoder.MsgType, text: String) {
        val a = alerts.firstOrNull { it.type == type }
        if (a != null) {
            a.count++; a.text = text; a.lastSeen = System.currentTimeMillis()
        } else {
            alerts.add(0, Alert(type, text, 1, System.currentTimeMillis()))
        }
        render()
    }

    /** Expires entries untouched for >30s. Cheap no-op render skip when
     *  nothing actually expired, since this runs every second regardless. */
    fun tick() {
        val before = alerts.size
        alerts.removeAll { System.currentTimeMillis() - it.lastSeen > 30_000L }
        if (alerts.size != before) render()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 180f else 0f
    }

    private fun render() {
        chipContainer.removeAllViews()
        alerts.forEach { chipContainer.addView(buildChip(it)) }

        val hasAlerts = alerts.isNotEmpty()
        emptyText.visibility = if (hasAlerts) View.GONE else View.VISIBLE
        chevron.visibility = if (hasAlerts) View.VISIBLE else View.GONE
        barContainer.isClickable = hasAlerts
        if (!hasAlerts && expanded) toggleExpanded()

        detailsContainer.removeAllViews()
        alerts.forEach { a ->
            detailsContainer.addView(TextView(context).apply {
                text = "${a.type.short} — ${a.text} (×${a.count})"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(citsColor(a.type))
            })
        }
    }

    private fun buildChip(a: Alert): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = context.getString(R.string.cits_chip_format, a.type.short, a.count)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 50 * dp
                setColor(citsColor(a.type))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (6 * dp).toInt() }
        }
    }

    /** The handoff's colors_v2x.xml only names cits_* tones for the five
     *  most common message types (CAM/DENM/MAPEM/SPATEM/IVIM) — the rest
     *  (SREM/SSEM/TLM/RTCMEM/UNKNOWN) keep falling back to
     *  ItsG5Decoder.MsgType's own existing color, same as the map markers
     *  and frame log already use, since there's no dedicated C-ITS-palette
     *  tone defined for them to unify with. NOTE: for the five that do have
     *  a cits_* tone, this deliberately does NOT change MsgType.color
     *  itself (which still drives markers/log) — see CLAUDE.md for why that
     *  broader reconciliation was left open rather than done here. */
    private fun citsColor(type: ItsG5Decoder.MsgType): Int = when (type) {
        ItsG5Decoder.MsgType.CAM    -> ContextCompat.getColor(context, R.color.cits_cam)
        ItsG5Decoder.MsgType.DENM   -> ContextCompat.getColor(context, R.color.cits_denm)
        ItsG5Decoder.MsgType.MAPEM  -> ContextCompat.getColor(context, R.color.cits_mapem)
        ItsG5Decoder.MsgType.SPATEM -> ContextCompat.getColor(context, R.color.cits_spatem)
        ItsG5Decoder.MsgType.IVIM   -> ContextCompat.getColor(context, R.color.cits_ivim)
        else -> type.color
    }
}
