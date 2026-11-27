package app.shiftlit.driver

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Debug-only: seed one diagnostics capture that the heuristics miss, so the AI
 * screen-fix sheet can be exercised without waiting for a live offer.
 *
 * Only runs in a debuggable build and only when MainActivity is started with
 * the [EXTRA] boolean, so a normal debug run is unaffected. Release builds
 * return immediately.
 */
object DebugSeed {

    const val EXTRA = "seed_diag"

    /** Not installed on this phone, so a seeded capture can't be overwritten
     *  by the real app coming to the foreground. */
    private const val PKG = "com.wolt.courierapp"

    fun seedIfRequested(ctx: Context, requested: Boolean, settings: SettingsRepository) {
        if (!requested || !isDebuggable(ctx)) return
        // Bare numbers with no unit words: the heuristic parser refuses this
        // card, exactly the kind of miss the AI fix is meant for.
        val nodes = listOf(
            flat("New offer", "id/title", 0, 0, 1080, 120, false),
            flat("\$12.50", "id/payout", 40, 140, 500, 220, false),
            flat("4.2", "id/distance", 40, 240, 500, 300, false),
            flat("18", "id/duration", 40, 320, 500, 380, false),
            flat("Accept", "id/accept", 40, 900, 700, 1000, true),
            flat("", "id/close", 980, 40, 1060, 120, true),
        )
        Diagnostics.record(
            ctx, PKG, "miss", nodes,
            listOf("debug seed: bare distance and duration, no unit words"),
        )
        // The debug app has its own preferences; start it on the provider the
        // preview is normally tested with. The key still has to be entered.
        settings.aiProvider = AiProvider.OPENROUTER.name
        settings.aiModel = "nvidia/nemotron-3-ultra-550b-a55b:free"
    }

    private fun flat(
        text: String, id: String, l: Int, t: Int, r: Int, b: Int, clickable: Boolean,
    ) = OfferParser.FlatNode(text, id, Bounds(l, t, r, b), clickable)

    private fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
