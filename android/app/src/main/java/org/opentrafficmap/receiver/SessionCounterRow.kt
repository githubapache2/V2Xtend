package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Applies a specific Typeface to a span range — android.text.style.TypefaceSpan(Typeface)
 *  only exists from API 28, this app's minSdk is 24. */
private class TypefaceSpanCompat(private val typeface: Typeface?) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(paint: Paint) { typeface?.let { paint.typeface = it } }
}

/**
 * Session-total counter row (design-file precision pass, 2026-08-12) —
 * four fixed chips (CAM/DENM/SPATEM/MAPEM) showing running totals since
 * app start or the last "Reset map & log", NOT the 30s-TTL floating
 * alertBar above the map (see CitsAlertBar). Dim-background + colored-text
 * look (cits_*_dim/_text), deliberately different from the floating bar's
 * solid-color chips — see handoff README precision notes in CLAUDE.md.
 *
 * Unlike CitsAlertBar's dynamic list (rebuilt per message, since which
 * types are "active" changes), this row's four chips are fixed, so each
 * one is built once in init and updated in place on increment() — no
 * per-message view churn.
 */
class SessionCounterRow(context: Context, container: LinearLayout) {

    private data class ChipSpec(
        val type: ItsG5Decoder.MsgType,
        val dimColorRes: Int,
        val textColorRes: Int,
    )

    private val specs = listOf(
        ChipSpec(ItsG5Decoder.MsgType.CAM, R.color.cits_cam_dim, R.color.cits_cam_text),
        ChipSpec(ItsG5Decoder.MsgType.DENM, R.color.cits_denm_dim, R.color.cits_denm_text),
        ChipSpec(ItsG5Decoder.MsgType.SPATEM, R.color.cits_spatem_dim, R.color.cits_spatem_text),
        ChipSpec(ItsG5Decoder.MsgType.MAPEM, R.color.cits_mapem_dim, R.color.cits_mapem_text),
    )

    private val counts = specs.associate { it.type to 0 }.toMutableMap()
    private val chipViews = mutableMapOf<ItsG5Decoder.MsgType, TextView>()
    private val jetbrainsMonoBold = ResourcesCompat.getFont(context, R.font.jetbrains_mono)?.let {
        Typeface.create(it, Typeface.BOLD)
    }
    private val dmSansSemiBold = ResourcesCompat.getFont(context, R.font.dm_sans)?.let {
        Typeface.create(it, Typeface.BOLD)
    }

    init {
        val dp = context.resources.displayMetrics.density
        specs.forEach { spec ->
            val chip = TextView(context).apply {
                textSize = 11f
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 50 * dp
                    setColor(ContextCompat.getColor(context, spec.dimColorRes))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (6 * dp).toInt() }
            }
            chipViews[spec.type] = chip
            renderChip(chip, spec, 0)
            container.addView(chip)
        }
    }

    fun increment(type: ItsG5Decoder.MsgType) {
        val spec = specs.firstOrNull { it.type == type } ?: return
        val newCount = (counts[type] ?: 0) + 1
        counts[type] = newCount
        chipViews[type]?.let { renderChip(it, spec, newCount) }
    }

    fun reset() {
        specs.forEach { spec ->
            counts[spec.type] = 0
            chipViews[spec.type]?.let { renderChip(it, spec, 0) }
        }
    }

    /** "{count} {TYPE}" — count in bold JetBrains Mono, type label in
     *  semibold DM Sans, both in the spec's text color for that chip. */
    private fun renderChip(view: TextView, spec: ChipSpec, count: Int) {
        val textColor = ContextCompat.getColor(view.context, spec.textColorRes)
        val countStr = count.toString()
        val label = " ${spec.type.short}"
        val sb = SpannableStringBuilder(countStr + label)
        sb.setSpan(TypefaceSpanCompat(jetbrainsMonoBold), 0, countStr.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpanCompat(dmSansSemiBold), countStr.length, sb.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(textColor), 0, sb.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = sb
    }
}
