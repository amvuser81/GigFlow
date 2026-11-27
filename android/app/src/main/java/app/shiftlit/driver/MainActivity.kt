package app.shiftlit.driver

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import app.shiftlit.driver.ui.*

/**
 * Shiftlit — driver utility companion.
 *
 * Five tabs hosted in a single activity (no fragments, no deps):
 *   Dashboard · Offers · Track · Plan · Assistant
 *
 * The accessibility service runs independently of this UI — see
 * ShiftlitAccessibilityService.
 */
class MainActivity : Activity() {

    companion object {
        const val REQ_LOCATION = 7
    }

    private lateinit var settings: SettingsRepository
    private lateinit var db: LocalDb
    private lateinit var p: Ios.Palette

    private lateinit var dashboard: DashboardScreen
    private lateinit var offers: OffersScreen
    private lateinit var track: TrackScreen
    private lateinit var plan: PlanScreen
    private lateinit var assist: AssistScreen

    private lateinit var content: FrameLayout
    private lateinit var tabBar: TabBar
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        DebugSeed.seedIfRequested(
            this, intent?.getBooleanExtra(DebugSeed.EXTRA, false) == true, settings,
        )
        db = LocalDb(this)
        p = Ios.palette(this)

        dashboard = DashboardScreen(this, p, db, settings)
        offers = OffersScreen(this, p, settings)
        track = TrackScreen(this, p, db, settings)
        plan = PlanScreen(this, p, db, settings)
        assist = AssistScreen(this, p, settings, db)

        content = FrameLayout(this)
        tabBar = TabBar(this, p)
        tabBar.setTabs(listOf(
            TabBar.Tab("house", "Home"),
            TabBar.Tab("tag", "Offers"),
            TabBar.Tab("plus.circle", "Track"),
            TabBar.Tab("calendar", "Plan"),
            TabBar.Tab("slider", "Assist"),
        ))
        tabBar.onSelect = { show(it) }

        val root = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(tabBar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setBackgroundColor(p.groupedBg)
        }
        setContentView(root)

        // Keep the status bar and phone navigation bar visible at all times.
        window.statusBarColor = p.groupedBg
        window.navigationBarColor = p.groupedBg
        // Light icons on the ink surface (the light theme would draw dark
        // ones on a near-black bar).
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            root.setOnApplyWindowInsetsListener { _, insets ->
                val sys = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                content.setPadding(sys.left, sys.top, sys.right, 0)
                tabBar.setPadding(sys.left, 0, sys.right, sys.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        show(0)
        UpdateChecker.promptIfNewer(this, p)
    }

    private fun show(i: Int) {
        current = i
        val target = when (i) {
            0 -> dashboard.screen
            1 -> offers.screen
            2 -> track.screen
            3 -> plan.screen
            else -> assist.screen
        }
        if (target.parent == content) return
        content.removeAllViews()
        target.alpha = 0f
        content.addView(target, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        target.animate().alpha(1f).setDuration(Ios.ANIM_FAST).start()
        refresh(i)
    }

    private fun refresh(i: Int): Unit {
        Ios.hapticsEnabled = settings.haptics
        return when (i) {
            0 -> dashboard.refresh()
            1 -> offers.refresh()
            2 -> track.refresh()
            3 -> plan.refresh()
            else -> assist.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tabBar.isInitialized) refresh(current)
    }

    /** Location permission result — granted means the user tapped Start shift. */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (MileageTracker.hasLocationPermission(this)) {
                startForegroundService(MileageTracker.startIntent(this))
            }
            if (::track.isInitialized) track.refresh()
        }
    }
}
