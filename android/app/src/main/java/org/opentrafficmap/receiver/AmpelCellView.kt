package org.opentrafficmap.receiver

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

/**
 * Peek-strip traffic-phase cell (handoff redesign step 6, "Ampelzelle").
 *
 * Three explicit states per the handoff README table — phase+countdown,
 * phase without countdown, no reception. The sub-slot text view is never
 * set to GONE, only its text/style/color change, so the row never jumps
 * height when the state changes (this was the whole point of building a
 * dedicated view instead of reusing a plain TextView here). Color changes
 * animate over 220ms via ArgbEvaluator; geometry never changes.
 */
class AmpelCellView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    sealed class State {
        data class PhaseCountdown(val phase: SpatTemParser.Phase, val seconds: Int, val meters: Int) : State()
        data class PhaseOnly(val phase: SpatTemParser.Phase) : State()
        object NoReception : State()
    }

    private val phaseText: TextView
    private val subText: TextView
    private var currentColor: Int = 0
    private var colorAnimator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        phaseText = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
        }
        subText = TextView(context).apply {
            textSize = 9f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            alpha = 0.8f
        }
        addView(phaseText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        currentColor = neutralColor()
        phaseText.setTextColor(currentColor)
        subText.setTextColor(neutralColor())
        phaseText.text = context.getString(R.string.peek_phase_none)
        subText.text = context.getString(R.string.ampel_no_reception)
    }

    private fun neutralColor(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(MaterialR.attr.colorOnSurfaceVariant, tv, true)
        return tv.data
    }

    private fun phaseColor(phase: SpatTemParser.Phase): Int {
        val attr = when (phase) {
            SpatTemParser.Phase.RED    -> MaterialR.attr.colorError
            SpatTemParser.Phase.GREEN  -> MaterialR.attr.colorTertiary
            SpatTemParser.Phase.YELLOW -> return ContextCompat.getColor(context, R.color.v2x_warn)
            SpatTemParser.Phase.UNKNOWN -> return neutralColor()
        }
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun phaseLabel(phase: SpatTemParser.Phase): String = when (phase) {
        SpatTemParser.Phase.RED     -> "RED"
        SpatTemParser.Phase.YELLOW  -> "YLW"
        SpatTemParser.Phase.GREEN   -> "GRN"
        SpatTemParser.Phase.UNKNOWN -> context.getString(R.string.peek_phase_none)
    }

    fun setState(state: State) {
        val (label, sub, color) = when (state) {
            is State.PhaseCountdown -> Triple(
                phaseLabel(state.phase),
                "${state.seconds}s · ${state.meters}m",
                phaseColor(state.phase),
            )
            is State.PhaseOnly -> Triple(
                phaseLabel(state.phase),
                context.getString(R.string.ampel_no_countdown),
                phaseColor(state.phase),
            )
            State.NoReception -> Triple(
                context.getString(R.string.peek_phase_none),
                context.getString(R.string.ampel_no_reception),
                neutralColor(),
            )
        }
        phaseText.text = label
        subText.text = sub
        animateToColor(color)
    }

    private fun animateToColor(target: Int) {
        if (target == currentColor) return
        colorAnimator?.cancel()
        val from = currentColor
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 220
            addUpdateListener { phaseText.setTextColor(it.animatedValue as Int) }
            start()
        }
        currentColor = target
    }
}
