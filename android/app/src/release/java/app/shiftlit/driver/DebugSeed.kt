package app.shiftlit.driver

import android.content.Context
import app.shiftlit.driver.ui.Ios

/** Release source set: seeding is a debug-only tool, so this does nothing. */
object DebugSeed {
    const val EXTRA = "seed_diag"
    const val SCENARIO = "seed_scenario"

    fun available(ctx: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun seedIfRequested(
        ctx: Context,
        requested: Boolean,
        settings: SettingsRepository,
        scenario: String? = null,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun seedScenario(ctx: Context, settings: SettingsRepository, key: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun picker(
        ctx: Context,
        p: Ios.Palette,
        settings: SettingsRepository,
        onDone: () -> Unit,
    ) = Unit
}
