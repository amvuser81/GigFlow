package app.shiftlit.driver

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.shiftlit.driver.ui.Ios
import app.shiftlit.driver.ui.IosSheet
import app.shiftlit.driver.ui.Ios.dp

/**
 * Debug source set: seed diagnostics captures that exercise the parser and the
 * AI screen-fix sheet without waiting for a live offer.
 *
 * Starting MainActivity with [EXTRA] seeds the default miss; [SCENARIO] picks
 * another fixture. Debug builds also expose [picker] from About, so a tester
 * can switch fixtures on the phone. Release builds link the no-op stub.
 */
object DebugSeed {

    const val EXTRA = "seed_diag"
    const val SCENARIO = "seed_scenario"

    /** Not installed on this phone, so a seeded capture can't be overwritten
     *  by the real app coming to the foreground. */
    private const val PKG = "com.wolt.courierapp"

    data class Scenario(val key: String, val label: String, val description: String)

    val scenarios = listOf(
        Scenario(
            "offer", "Missed offer",
            "Bare numbers with no unit words — the default AI-fix fixture.",
        ),
        Scenario(
            "reservation", "Reserved offer",
            "A scheduled/reserved card with different wording.",
        ),
        Scenario(
            "money", "Tip next to pay",
            "Two money values, so the payout sanity check has something to catch.",
        ),
        Scenario(
            "buttons", "Buttons only",
            "No amount, but an accept control and an unnamed close button.",
        ),
        Scenario(
            "unreadable", "Unreadable nodes",
            "All text blank; the model has only rectangles to work with.",
        ),
    )

    fun available(ctx: Context): Boolean = true

    fun seedIfRequested(
        ctx: Context,
        requested: Boolean,
        settings: SettingsRepository,
        scenario: String? = null,
    ) {
        if (!requested) return
        seed(ctx, settings, scenario ?: "offer")
    }

    fun seedScenario(ctx: Context, settings: SettingsRepository, key: String) {
        seed(ctx, settings, key)
    }

    private fun seed(ctx: Context, settings: SettingsRepository, key: String) {
        val nodes = when (key) {
            "reservation" -> listOf(
                flat("Reserved offer", "id/title", 0, 0, 1080, 120, false),
                flat("Tomorrow, 08:30", "id/when", 40, 130, 700, 190, false),
                flat("\$22.00", "id/payout", 40, 210, 500, 280, false),
                flat("6.5", "id/distance", 40, 300, 500, 360, false),
                flat("35", "id/duration", 40, 380, 500, 440, false),
                flat("Accept", "id/accept", 40, 960, 700, 1060, true),
                flat("", "id/close", 980, 40, 1060, 120, true),
            )
            "money" -> listOf(
                flat("New offer", "id/title", 0, 0, 1080, 120, false),
                flat("Trip \$8.00", "id/pay", 40, 140, 500, 210, false),
                flat("Tip \$4.00", "id/tip", 40, 230, 500, 300, false),
                flat("5.1", "id/distance", 40, 320, 500, 380, false),
                flat("14", "id/duration", 40, 400, 500, 460, false),
                flat("Accept", "id/accept", 40, 980, 700, 1080, true),
                flat("", "id/close", 980, 40, 1060, 120, true),
            )
            "buttons" -> listOf(
                flat("New offer", "id/title", 0, 0, 1080, 120, false),
                flat("Accept", "id/accept", 40, 900, 700, 1000, true),
                flat("", "id/close", 980, 40, 1060, 120, true),
            )
            "unreadable" -> listOf(
                flat("", "id/a", 0, 0, 1080, 120, false),
                flat("", "id/b", 40, 160, 500, 240, false),
                flat("", "id/c", 40, 280, 500, 340, false),
                flat("", "id/d", 40, 900, 700, 1000, true),
                flat("", "id/e", 980, 40, 1060, 120, true),
            )
            else -> listOf(
                flat("New offer", "id/title", 0, 0, 1080, 120, false),
                flat("\$12.50", "id/payout", 40, 140, 500, 220, false),
                flat("4.2", "id/distance", 40, 240, 500, 300, false),
                flat("18", "id/duration", 40, 320, 500, 380, false),
                flat("Accept", "id/accept", 40, 900, 700, 1000, true),
                flat("", "id/close", 980, 40, 1060, 120, true),
            )
        }
        Diagnostics.record(
            ctx, PKG, "miss", nodes,
            listOf("debug seed ($key): parser fixture for Diagnostics and AI fix"),
        )
        // The debug app has its own preferences; start it on the provider the
        // preview is normally tested with. The key still has to be entered.
        settings.aiProvider = AiProvider.OPENROUTER.name
        settings.aiModel = "nvidia/nemotron-3-ultra-550b-a55b:free"
    }

    /** Debug-only on-device picker, reached by long-pressing the About row. */
    fun picker(
        ctx: Context,
        p: Ios.Palette,
        settings: SettingsRepository,
        onDone: () -> Unit,
    ) {
        val sheet = IosSheet(ctx, p)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
        }
        col.addView(TextView(ctx).apply {
            text = "Debug seed scenarios"
            textSize = Ios.T_HEADLINE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.label)
        })
        col.addView(TextView(ctx).apply {
            text = "Debug builds only. Seeds one Diagnostics capture; it does not " +
                "touch the release app's data."
            textSize = Ios.T_FOOTNOTE
            setTextColor(p.label2)
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 10f))
        })
        scenarios.forEach { s ->
            col.addView(TextView(ctx).apply {
                text = s.label
                textSize = Ios.T_BODY
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.label)
                setPadding(0, dp(ctx, 8f), 0, 0)
                setOnClickListener {
                    seedScenario(ctx, settings, s.key)
                    Toast.makeText(ctx, "Seeded ${s.label}", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                    onDone()
                }
            })
            col.addView(TextView(ctx).apply {
                text = s.description
                textSize = Ios.T_CAPTION
                setTextColor(p.label3)
                setPadding(0, 0, 0, dp(ctx, 2f))
            })
        }
        col.addView(TextView(ctx).apply {
            text = "Close"
            textSize = Ios.T_BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.tint)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 14f), 0, 0)
            setOnClickListener { sheet.dismiss() }
        })
        sheet.add(col).show()
    }

    private fun flat(
        text: String, id: String, l: Int, t: Int, r: Int, b: Int, clickable: Boolean,
    ) = OfferParser.FlatNode(text, id, Bounds(l, t, r, b), clickable)

}
