package app.shiftlit.driver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Interpolator

/**
 * Design tokens for the app: the "Instrument" palette — ink-black surfaces,
 * hairline rules, one orange accent, tabular numerals. Dark only, on purpose:
 * it reads as one instrument panel rather than a themed clone. Hand-rolled on
 * plain Android views, no dependencies.
 */
object Ios {

    // --- Motion -------------------------------------------------------------
    // Critically-damped-ish spring: slight overshoot then settle, like UIKit's
    // default spring (dampingRatio ~0.8).
    val SPRING: Interpolator = Interpolator { t ->
        val s = 1.70158f * 1.3f
        val x = t - 1f
        x * x * ((s + 1f) * x + s) + 1f
    }
    val EASE_OUT: Interpolator = android.view.animation.DecelerateInterpolator(1.8f)
    const val ANIM_MS = 320L
    const val ANIM_FAST = 180L

    /** Global toggle mirrored from SettingsRepository.haptics. */
    @Volatile var hapticsEnabled = true

    fun View.haptic() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun View.pressable(scale: Float = 0.965f) {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scale).scaleY(scale).alpha(0.85f)
                        .setDuration(ANIM_FAST).setInterpolator(EASE_OUT).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(ANIM_MS).setInterpolator(SPRING).start()
                }
            }
            false
        }
    }

    // --- Palette -------------------------------------------------------------
    class Palette(val isDark: Boolean = true) {
        /** The app ships dark-only; kept explicit so callers never guess. */
        val dark = true
        val bg: Int
        val groupedBg: Int // background behind grouped lists
        val card: Int      // secondarySystemGroupedBackground
        val card2: Int     // tertiarySystemGroupedBackground / fill
        val fill: Int      // quaternary fill (segmented track)
        val label: Int
        val label2: Int
        val label3: Int
        val separator: Int
        val tint: Int
        val green: Int
        val red: Int
        val orange: Int
        val yellow: Int
        val gray: Int
        val sheetHandle: Int

        init {
            // Instrument tokens. Same values in light and dark system mode.
            bg = Color.parseColor("#0A0A0B")
            groupedBg = Color.parseColor("#0A0A0B")
            card = Color.parseColor("#141416")
            card2 = Color.parseColor("#1B1B1E")
            fill = Color.parseColor("#202024")
            label = Color.parseColor("#F2F2F4")
            label2 = Color.parseColor("#B4B4BA")
            label3 = Color.parseColor("#8D8D93")
            separator = Color.parseColor("#26262A")
            tint = Color.parseColor("#FF7A1A")
            green = Color.parseColor("#3DDC84")
            red = Color.parseColor("#FF5A4A")
            orange = Color.parseColor("#FF7A1A")
            yellow = Color.parseColor("#FFB020")
            gray = Color.parseColor("#8D8D93")
            sheetHandle = Color.parseColor("#3A3A40")
        }
    }

    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return Palette(night)
    }

    // --- Metrics --------------------------------------------------------------
    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()
    fun sp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.scaledDensity

    const val R_CARD = 10f   // panel, 1px hairline
    const val R_PILL = 14f
    const val R_BUTTON = 10f
    const val R_SHEET = 12f

    // Type scale (sp) — Apple's text styles
    const val T_LARGE = 34f
    const val T_TITLE2 = 22f
    const val T_HEADLINE = 17f
    const val T_BODY = 17f
    const val T_SUBHEAD = 15f
    const val T_FOOTNOTE = 13f
    const val T_CAPTION = 12f

    // --- Drawing helpers ------------------------------------------------------
    fun rounded(radiusDp: Float, color: Int, ctx: Context) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(color)
    }

    fun stroke(radiusDp: Float, color: Int, strokeColor: Int, ctx: Context) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(color)
        setStroke(dp(ctx, 0.5f).coerceAtLeast(1), strokeColor)
    }

    /** iOS-style shadowed card background. */
    fun cardBg(p: Palette, ctx: Context) = GradientDrawable().apply {
        cornerRadius = dp(ctx, R_CARD).toFloat()
        setColor(p.card)
        setStroke(dp(ctx, 1f).coerceAtLeast(1), p.separator)
    }

    /** Currency-aware money render — placement/decimals come from the
     *  registry in [Currencies] ("1 850 Ft", "CHF 12.50", "¥1,200"). */
    fun money(cents: Int?, symbol: String = "$", code: String? = null): String =
        cents?.let { app.shiftlit.driver.Currencies.format(it, symbol, code) } ?: "—"

    fun verdictColor(v: VerdictCompat, p: Palette) = when (v) {
        VerdictCompat.GOOD -> p.green
        VerdictCompat.BAD -> p.red
    }

    /** Loose duplicate of the service-side enum for UI use. */
    enum class VerdictCompat { GOOD, BAD }
}
