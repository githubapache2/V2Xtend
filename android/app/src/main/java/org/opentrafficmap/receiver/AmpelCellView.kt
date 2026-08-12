package org.opentrafficmap.receiver

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.R as MaterialR

/**
 * Peek-strip traffic-phase cell (handoff redesign step 6, "Ampelzelle";
 * precision pass 2026-08-12 against the design-file dark palette — see
 * CLAUDE.md). Three light dots (stacked vertically, red/yellow/green,
 * mimicking a real traffic light) sit left of a two-line text stack
 * (phase name + sub-line). Sub-line typography/color depends on whether a
 * countdown is present: with a countdown it's bold Mono in the phase
 * color; without one (or with no reception at all) it's small DM Sans in
 * a flat muted grey — deliberately NOT phase-colored, a correction against
 * the first pass, which had used the phase color there too.
 *
 * The sub-slot text view is never set to GONE, only its text/typeface/
 * color change, so the row never jumps height. The phase-name color
 * animates over 220ms via ArgbEvaluator; geometry never changes.
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

    private val dotRed: View
    private val dotYellow: View
    private val dotGreen: View
    private val phaseText: TextView
    private val subText: TextView
    private var currentPhaseColor: Int = 0
    private var colorAnimator: ValueAnimator? = null

    private val outfit = ResourcesCompat.getFont(context, R.font.outfit)
    private val dmSans = ResourcesCompat.getFont(context, R.font.dm_sans)
    private val jetbrainsMono = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val dp = resources.displayMetrics.density
        fun dot(): View = View(context).apply {
            layoutParams = LayoutParams((6 * dp).toInt(), (6 * dp).toInt()).also {
                it.topMargin = (3 * dp).toInt()
            }
            background = ContextCompat.getDrawable(context, R.drawable.ic_status_dot)?.mutate()
        }
        dotRed = dot()
        dotYellow = dot()
        dotGreen = dot()
        val dotStack = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((4 * dp).toInt(), (5 * dp).toInt(), (4 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 6 * dp
                setColor(ContextCompat.getColor(context, R.color.v2x_surface_raised_dark))
            }
            addView(dotRed)
            addView(dotYellow, LayoutParams(dotYellow.layoutParams).also { it.topMargin = (3 * dp).toInt() })
            addView(dotGreen, LayoutParams(dotGreen.layoutParams).also { it.topMargin = (3 * dp).toInt() })
        }
        addView(dotStack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = (8 * dp).toInt()
        })

        phaseText = TextView(context).apply {
            typeface = outfit
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            maxLines = 1
        }
        subText = TextView(context).apply {
            maxLines = 1
        }
        val textStack = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(phaseText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(subText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(textStack, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        setState(State.NoReception)
    }

    private fun activeColor(phase: SpatTemParser.Phase): Int = when (phase) {
        SpatTemParser.Phase.RED     -> ContextCompat.getColor(context, R.color.ampel_red_active)
        SpatTemParser.Phase.YELLOW  -> ContextCompat.getColor(context, R.color.ampel_yellow_active)
        SpatTemParser.Phase.GREEN   -> ContextCompat.getColor(context, R.color.ampel_green_active)
        SpatTemParser.Phase.UNKNOWN -> mutedColor()
    }

    private fun mutedColor(): Int = ContextCompat.getColor(context, R.color.v2x_muted_dark)
    private fun subGreyColor(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(MaterialR.attr.colorOnSurfaceVariant, tv, true)
        return tv.data
    }

    private fun phaseLabel(phase: SpatTemParser.Phase): String = when (phase) {
        SpatTemParser.Phase.RED     -> "RED"
        SpatTemParser.Phase.YELLOW  -> "YELLOW"
        SpatTemParser.Phase.GREEN   -> "GREEN"
        SpatTemParser.Phase.UNKNOWN -> context.getString(R.string.peek_phase_none)
    }

    private fun setDots(active: SpatTemParser.Phase?) {
        fun tint(view: View, on: Boolean, activeColor: Int, offColorRes: Int) {
            view.backgroundTintList = ColorStateList.valueOf(
                if (on) activeColor else ContextCompat.getColor(context, offColorRes)
            )
        }
        tint(dotRed,    active == SpatTemParser.Phase.RED,    ContextCompat.getColor(context, R.color.ampel_red_active),    R.color.ampel_red_off)
        tint(dotYellow, active == SpatTemParser.Phase.YELLOW, ContextCompat.getColor(context, R.color.ampel_yellow_active), R.color.ampel_yellow_off)
        tint(dotGreen,  active == SpatTemParser.Phase.GREEN,  ContextCompat.getColor(context, R.color.ampel_green_active),  R.color.ampel_green_off)
    }

    fun setState(state: State) {
        when (state) {
            is State.PhaseCountdown -> {
                setDots(state.phase)
                phaseText.text = phaseLabel(state.phase)
                val color = activeColor(state.phase)
                animatePhaseColor(color)
                subText.apply {
                    typeface = jetbrainsMono
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 15f
                    setTextColor(color)
                    text = "${state.seconds} s · ${state.meters} m"
                }
            }
            is State.PhaseOnly -> {
                setDots(state.phase)
                phaseText.text = phaseLabel(state.phase)
                animatePhaseColor(activeColor(state.phase))
                subText.apply {
                    typeface = dmSans
                    setTypeface(typeface, Typeface.NORMAL)
                    textSize = 11f
                    setTextColor(subGreyColor())
                    text = context.getString(R.string.ampel_no_countdown)
                }
            }
            State.NoReception -> {
                setDots(null)
                phaseText.text = context.getString(R.string.peek_phase_none)
                animatePhaseColor(mutedColor())
                subText.apply {
                    typeface = dmSans
                    setTypeface(typeface, Typeface.NORMAL)
                    textSize = 11f
                    setTextColor(subGreyColor())
                    text = context.getString(R.string.ampel_no_reception)
                }
            }
        }
    }

    private fun animatePhaseColor(target: Int) {
        if (target == currentPhaseColor) { phaseText.setTextColor(target); return }
        colorAnimator?.cancel()
        val from = if (currentPhaseColor == 0) target else currentPhaseColor
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 220
            addUpdateListener { phaseText.setTextColor(it.animatedValue as Int) }
            start()
        }
        currentPhaseColor = target
    }
}
