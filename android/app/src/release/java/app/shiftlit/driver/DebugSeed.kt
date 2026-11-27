package app.shiftlit.driver

import android.content.Context

/** Release source set: seeding is a debug-only tool, so this does nothing. */
object DebugSeed {
    const val EXTRA = "seed_diag"

    @Suppress("UNUSED_PARAMETER")
    fun seedIfRequested(ctx: Context, requested: Boolean, settings: SettingsRepository) = Unit
}
