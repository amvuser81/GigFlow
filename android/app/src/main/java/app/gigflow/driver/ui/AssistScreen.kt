package app.gigflow.driver.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import app.gigflow.driver.*
import app.gigflow.driver.ui.Ios.Palette
import app.gigflow.driver.ui.Ios.dp
import app.gigflow.driver.ui.Ios.haptic
import app.gigflow.driver.ui.Ios.pressable

/** Where diagnostics reports are emailed. This fork reports to its owner, not
 *  to the upstream project. */
private const val DIAG_EMAIL = "amvuser81@live.com"

/** Assist — assistant status, rules, automation switches, diagnostics. */
class AssistScreen(
    ctx: Context,
    private val p: Palette,
    private val settings: SettingsRepository,
    private val db: LocalDb,
) {
    val screen = Screen(ctx, p, "Assistant")
    private val ctx: Context = ctx

    private fun gap(h: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }

    fun refresh() {
        screen.column.removeAllViews()
        val c = screen.column

        c.addView(statusCard())
        c.addView(gap(10f))
        c.addView(setupCard())
        c.addView(Sections.header(ctx, p, "Automation"))
        c.addView(automationCard())
        c.addView(Sections.header(ctx, p, "Offer rules"))
        c.addView(rulesCard())
        c.addView(Sections.header(ctx, p, "Per-app rules"))
        c.addView(perAppCard())
        c.addView(TextView(ctx).apply {
            text = "Apps without an override use the global rules above."
            textSize = Ios.T_FOOTNOTE; setTextColor(p.label3)
            setPadding(dp(ctx, 20f), dp(ctx, 6f), dp(ctx, 16f), 0)
        })
        c.addView(Sections.header(ctx, p, "Preferences"))
        c.addView(preferencesCard())
        c.addView(Sections.header(ctx, p, "Diagnostics"))
        c.addView(diagnosticsCard())
        c.addView(TextView(ctx).apply {
            text = "What the assistant last saw in each app. If an offer wasn't scored, tap the app to see the texts it read — that tells us what to fix."
            textSize = Ios.T_FOOTNOTE; setTextColor(p.label3)
            setPadding(dp(ctx, 20f), dp(ctx, 6f), dp(ctx, 16f), 0)
        })
        c.addView(Sections.header(ctx, p, "About"))
        c.addView(aboutCard())
        c.addView(Sections.header(ctx, p, "Credits"))
        c.addView(creditsCard())
        screen.animateIn()
    }

    private fun statusCard(): View {
        val card = Sections.card(ctx, p)
        val on = OverlayController.isServiceEnabled(ctx)
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 14f))
            addView(View(ctx).apply {
                background = Ios.rounded(14f, if (on) p.green else p.red, ctx)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 10f), dp(ctx, 10f))
                    .apply { rightMargin = dp(ctx, 12f) }
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = if (on) "Assistant is on" else "Assistant is off"
                    textSize = Ios.T_HEADLINE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.label)
                })
                addView(TextView(ctx).apply {
                    text = if (on) "Scoring offers in your driver apps"
                    else "Enable it to score and auto-act on offers"
                    textSize = Ios.T_FOOTNOTE
                    setTextColor(p.label2)
                })
            })
        })
        // Full-width button below the text — an inline button crowded the
        // subtitle and clipped mid-word on narrower screens.
        if (!on) {
            card.addView(LinearLayout(ctx).apply {
                setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), dp(ctx, 14f))
                val btn = iosButton(ctx, p, "Enable now — takes 10 seconds")
                addView(btn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                btn.setOnClickListener {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })
        }
        return card
    }

    /** Guided setup — Mystro's biggest UX lesson: the app does *nothing*
     *  until the accessibility service is on, so surface that loudly. */
    private fun setupCard(): View {
        val serviceOn = OverlayController.isServiceEnabled(ctx)
        val locOn = MileageTracker.hasLocationPermission(ctx)
        val notifOn = android.os.Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (serviceOn && locOn && notifOn) return View(ctx) // all good — hide

        val card = Sections.card(ctx, p)
        var first = true
        fun step(title: String, subtitle: String, glyph: String, done: Boolean, action: (() -> Unit)?) {
            if (!first) card.addView(Sections.separator(ctx, p))
            first = false
            card.addView(Sections.row(ctx, p,
                title = title,
                subtitle = subtitle,
                iconGlyph = if (done) "checkmark" else glyph,
                iconTint = if (done) p.green else p.orange,
                chevron = !done && action != null,
            ) { action?.invoke() })
        }
        step("Enable the accessibility service",
            "Required — without it the assistant can't see offers at all",
            "slider", serviceOn) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        step("Allow location access",
            "Needed for automatic shift mileage",
            "mappin", locOn) {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION),
                MainActivity.REQ_LOCATION,
            )
        }
        step("Allow notifications",
            "Shows the shift-tracking notification while a shift runs",
            "bell", notifOn) {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        c_footer(card, "Finish these three, then turn on the automation switches below.")
        return card
    }

    /** What the service last saw per watched app — tap to inspect. */
    private fun diagnosticsCard(): View {
        val card = Sections.card(ctx, p)
        var first = true
        watchedApps.forEach { (name, pkg) ->
            if (!first) card.addView(Sections.separator(ctx, p))
            first = false
            val snap = Diagnostics.get(ctx, pkg)
            val (label, tint) = when {
                snap == null -> "Not seen yet" to p.label3
                snap.outcome == "offer" -> "Offer scored · ${ago(snap.at)}" to p.green
                snap.outcome == "miss" -> "Screen missed · ${ago(snap.at)}" to p.orange
                else -> "Screen seen · ${ago(snap.at)}" to p.label2
            }
            card.addView(Sections.row(ctx, p,
                title = name,
                subtitle = label,
                iconGlyph = "doc", iconTint = tint,
                chevron = snap?.texts?.isNotEmpty() == true,
            ) { snap?.let { showDiag(name, it) } })
        }
        return card
    }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    /** Sheet listing the texts the parser read — screenshot this to report. */
    private fun showDiag(name: String, snap: Diagnostics.Snap) {
        val sheet = IosSheet(ctx, p)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
        }
        col.addView(TextView(ctx).apply {
            text = "$name · ${snap.outcome}"
            textSize = Ios.T_HEADLINE; setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.label); setPadding(0, 0, 0, dp(ctx, 4f))
        })
        col.addView(TextView(ctx).apply {
            text = if (snap.outcome == "miss")
                "This screen had a price on it but wasn't read as an offer — the app's layout probably changed. Tap 'Share this report' and send it over."
            else "Everything the assistant read on the last screen."
            textSize = Ios.T_FOOTNOTE; setTextColor(p.label2)
            setPadding(0, 0, 0, dp(ctx, 10f))
        })
        snap.texts.forEach { t ->
            col.addView(TextView(ctx).apply {
                text = "· $t"
                textSize = Ios.T_FOOTNOTE; setTextColor(p.label)
                setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
            })
        }
        // Only meaningful on a miss: the parser never consults a hint when its
        // own heuristics produce an offer, so teaching a working screen would
        // be a no-op the user can't see the result of.
        if (snap.outcome == "miss" && snap.nodes.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Teach this screen"
                textSize = Ios.T_BODY; setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.tint); gravity = android.view.Gravity.CENTER
                setPadding(0, dp(ctx, 12f), 0, dp(ctx, 6f))
                setOnClickListener {
                    sheet.dismiss()
                    teachSheet(name, snap)
                }
            })
        }
        // Mail the full dump to the maintainer: every node with its view id,
        // bounds and clickability, the parser's decision trail, and the same
        // data as JSON so a tool (or an AI) can parse it.
        col.addView(TextView(ctx).apply {
            text = "Email this report"
            textSize = Ios.T_BODY; setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.tint); gravity = android.view.Gravity.CENTER
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 6f))
            setOnClickListener {
                val pkg = watchedApps.firstOrNull { it.first == name }?.second ?: "?"
                val mail = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(DIAG_EMAIL))
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "GigFlow diagnostics — $name ($pkg) · ${snap.outcome}",
                    )
                    putExtra(Intent.EXTRA_TEXT, diagReport(name, pkg, snap))
                }
                ctx.startActivity(Intent.createChooser(mail, "Email diagnostics to $DIAG_EMAIL"))
            }
        })
        col.addView(TextView(ctx).apply {
            text = "Addressed to $DIAG_EMAIL — the report fills the body; just send."
            textSize = Ios.T_CAPTION; setTextColor(p.label3)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(ctx, 8f))
        })
        sheet.add(col).show()
    }

    /**
     * The whole capture, for a human first and a parser second: a readable
     * header, the parser's decision trail, one line per node
     * (index | text | viewId | bounds | clickable), then the same data as JSON.
     */
    private fun diagReport(name: String, pkg: String, snap: Diagnostics.Snap): String {
        val pi = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (_: Exception) { null }
        val versionName = pi?.versionName ?: "?"
        @Suppress("DEPRECATION")
        val legacyCode = pi?.versionCode ?: 0
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pi?.longVersionCode ?: 0L
        } else legacyCode.toLong()
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
            " · Android ${android.os.Build.VERSION.RELEASE}" +
            " (SDK ${android.os.Build.VERSION.SDK_INT})"
        val iso = java.time.Instant.ofEpochMilli(snap.at).toString()

        val json = org.json.JSONObject()
            .put("schema", "gigflow.diag/1")
            .put("capturedAt", snap.at)
            .put("capturedAtIso", iso)
            .put("appVersion", versionName)
            .put("appVersionCode", versionCode)
            .put("device", device)
            .put("driverApp", name)
            .put("package", pkg)
            .put("outcome", snap.outcome)
            .put("screenKey", snap.screen)
            .put("screenNodeCount", snap.nodeCount)
            .put("capturedNodeCount", snap.nodes.size)
        val decisions = org.json.JSONArray()
        snap.decision.forEach { decisions.put(it) }
        json.put("parserDecision", decisions)
        val nodes = org.json.JSONArray()
        snap.nodes.forEachIndexed { i, n ->
            val b = n.bounds
            nodes.put(
                org.json.JSONObject()
                    .put("index", i)
                    .put("text", n.text)
                    .put("viewId", n.viewId ?: org.json.JSONObject.NULL)
                    .put(
                        "bounds",
                        org.json.JSONObject()
                            .put("left", b.getOrElse(0) { 0 })
                            .put("top", b.getOrElse(1) { 0 })
                            .put("right", b.getOrElse(2) { 0 })
                            .put("bottom", b.getOrElse(3) { 0 }),
                    )
                    .put("clickable", n.clickable),
            )
        }
        json.put("nodes", nodes)

        return buildString {
            append("GigFlow diagnostics\n")
            append("driver app  ").append(name).append("  (").append(pkg).append(")\n")
            append("outcome     ").append(snap.outcome).append('\n')
            append("captured    ").append(iso).append("  epoch ").append(snap.at).append('\n')
            append("app         GigFlow ").append(versionName)
                .append(" (").append(versionCode).append(")\n")
            append("device      ").append(device).append('\n')
            append("screen key  ").append(snap.screen).append('\n')
            append("nodes       ").append(snap.nodes.size)
            if (snap.nodeCount > snap.nodes.size) append(" of ").append(snap.nodeCount)
            append(" captured\n")
            if (snap.decision.isNotEmpty()) {
                append("\nparser decision\n")
                snap.decision.forEachIndexed { i, d ->
                    append("  ").append(i + 1).append(". ").append(d).append('\n')
                }
            }
            append("\nnodes  index | text | viewId | bounds(left,top,right,bottom) | clickable\n")
            snap.nodes.forEachIndexed { i, n ->
                val b = n.bounds
                append("  ").append(i).append(" | ")
                    .append(n.text.replace('\n', ' ')).append(" | ")
                    .append(n.viewId ?: "-").append(" | ")
                    .append(if (b.size == 4) "${b[0]},${b[1]},${b[2]},${b[3]}" else "-")
                    .append(" | ").append(n.clickable).append('\n')
            }
            append("\nmachine-readable JSON\n")
            append(json.toString(2))
            append('\n')
        }
    }

    /**
     * Teach a mapping from the captured snapshot: point at the payout, the
     * distance and the duration, test it against the very nodes that missed,
     * then save.
     *
     * Nothing is written until the test produces an offer, and the parser only
     * consults the saved mapping when its heuristics find nothing — screens it
     * already reads are never re-parsed through it.
     */
    private fun teachSheet(name: String, snap: Diagnostics.Snap) {
        val pkg = watchedApps.firstOrNull { it.first == name }?.second ?: return
        val flat = snap.nodes.map { n ->
            OfferParser.FlatNode(
                n.text,
                n.viewId,
                Bounds(
                    n.bounds.getOrElse(0) { 0 },
                    n.bounds.getOrElse(1) { 0 },
                    n.bounds.getOrElse(2) { 0 },
                    n.bounds.getOrElse(3) { 0 },
                ),
                n.clickable,
            )
        }
        if (flat.isEmpty()) return
        // The key captured with the snapshot wins: it was computed before the
        // 80-node cap, so it matches what a live scan produces.
        val screen = snap.screen.ifBlank { ParseHints.screenKey(flat) }
        val curSymbol = settings.currencySymbol
        val extraRe = OfferParser.extraMoney(settings.currencyCode, curSymbol)

        val roles = listOf("Payout", "Distance", "Duration")
        val picks = mutableMapOf<String, String?>(roles[0] to null, roles[1] to null, roles[2] to null)
        var active = 0
        var result: String? = null
        var resultOk = false

        val sheet = IosSheet(ctx, p)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
        }
        sheet.add(col)

        fun hintFor(): ParseHints.Hint? {
            val payout = picks["Payout"] ?: return null
            return ParseHints.Hint(
                pkg = pkg,
                screen = screen,
                payout = payout,
                distance = picks["Distance"],
                duration = picks["Duration"],
            )
        }

        fun test(h: ParseHints.Hint): Pair<Boolean, String> {
            val offer = OfferParser.parse(
                pkg, flat, extraRe, curSymbol, settings.currencyCode, null, h,
            )
                ?: return false to "Still no offer — tap a different node."
            val parts = ArrayList<String>(3)
            parts.add(Currencies.format(offer.payoutCents, offer.currencySymbol, offer.currencyCode))
            offer.distanceKm?.let { parts.add("%.1f km".format(it)) }
            offer.durationMin?.let { parts.add("%.0f min".format(it)) }
            return true to "Reads as ${parts.joinToString(" · ")}"
        }

        fun line(t: String, size: Float, color: Int, top: Float, bold: Boolean = false) =
            TextView(ctx).apply {
                text = t
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, top), 0, 0)
            }

        fun render() {
            col.removeAllViews()
            col.addView(line("Teach this screen", Ios.T_HEADLINE, p.label, 0f, bold = true))
            col.addView(line(
                "Tap the node holding the payout, then the distance and duration if " +
                    "they're shown. GigFlow only uses this where the normal parser finds " +
                    "nothing, so screens it already reads stay untouched.",
                Ios.T_FOOTNOTE, p.label2, 6f,
            ))

            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 10f), 0, dp(ctx, 4f))
            }
            roles.forEachIndexed { i, role ->
                chips.addView(TextView(ctx).apply {
                    text = if (picks[role] != null) "$role ✓" else role
                    textSize = Ios.T_SUBHEAD
                    setTextColor(if (i == active) p.tint else p.label2)
                    if (i == active) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(ctx, 4f), dp(ctx, 16f), dp(ctx, 4f))
                    setOnClickListener { active = i; render() }
                })
            }
            col.addView(chips)

            flat.forEachIndexed { i, n ->
                val anchor = ParseHints.anchor(flat, i)
                val role = picks.entries.firstOrNull { it.value == anchor }?.key
                col.addView(TextView(ctx).apply {
                    text = buildString {
                        if (role != null) append("[$role] ")
                        append(n.text.take(80))
                        n.viewId?.substringAfterLast('/')?.let { append("  ·  $it") }
                    }
                    textSize = Ios.T_FOOTNOTE
                    setTextColor(if (role != null) p.tint else p.label)
                    setPadding(0, dp(ctx, 5f), 0, dp(ctx, 5f))
                    setOnClickListener {
                        picks[roles[active]] = anchor
                        if (active < roles.lastIndex) active++
                        result = null
                        render()
                    }
                })
            }

            col.addView(TextView(ctx).apply {
                text = "Test this mapping"
                textSize = Ios.T_BODY; setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.tint); gravity = android.view.Gravity.CENTER
                setPadding(0, dp(ctx, 14f), 0, dp(ctx, 6f))
                setOnClickListener {
                    val h = hintFor()
                    if (h == null) {
                        resultOk = false
                        result = "Pick the payout node first."
                    } else {
                        val (ok, msg) = test(h)
                        resultOk = ok
                        result = msg
                    }
                    render()
                }
            })

            result?.let { r ->
                col.addView(TextView(ctx).apply {
                    text = r
                    textSize = Ios.T_FOOTNOTE
                    setTextColor(if (resultOk) p.green else p.orange)
                    setPadding(0, 0, 0, dp(ctx, 6f))
                })
            }

            if (resultOk) {
                col.addView(TextView(ctx).apply {
                    text = "Save for $name"
                    textSize = Ios.T_BODY; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.green); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(ctx, 10f), 0, dp(ctx, 4f))
                    setOnClickListener {
                        hintFor()?.let { ParseHints.save(ctx, it) }
                        sheet.dismiss()
                    }
                })
            }

            if (ParseHints.forScreen(ctx, pkg, flat) != null) {
                col.addView(TextView(ctx).apply {
                    text = "Forget this screen's mapping"
                    textSize = Ios.T_FOOTNOTE
                    setTextColor(p.label2); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(ctx, 10f), 0, 0)
                    setOnClickListener {
                        ParseHints.clear(ctx, pkg, screen)
                        sheet.dismiss()
                    }
                })
            }
        }

        render()
        sheet.show()
    }

    private fun automationCard(): View {
        val card = Sections.card(ctx, p)
        val (r1, _) = Sections.switchRow(ctx, p, "Monitoring",
            "Score offers as they appear", settings.enabled) {
            settings.enabled = it
            // Our own UI events never reach the service's event filter, so
            // tear the overlay down explicitly instead of waiting for the
            // next driver-app frame.
            if (!it) GigFlowAccessibilityService.instance?.stopWatching()
        }
        val (r2, _) = Sections.switchRow(ctx, p, "Auto-accept good offers",
            "Only when every rule passes", settings.autoAccept) { settings.autoAccept = it }
        val (r3, _) = Sections.switchRow(ctx, p, "Auto-decline bad offers",
            "Only when most rules fail", settings.autoDecline) { settings.autoDecline = it }
        val (r4, _) = Sections.switchRow(ctx, p, "Voice alerts",
            "Speak verdicts aloud as offers appear", settings.voiceAlerts) { settings.voiceAlerts = it }
        val (r5, _) = Sections.switchRow(ctx, p, "Auto-track mileage",
            "Start a GPS shift when a driver app opens", settings.autoTrackShift) {
            settings.autoTrackShift = it
        }
        val (r6, _) = Sections.switchRow(ctx, p, "Auto-log earnings",
            "Record payouts from post-trip summary screens", settings.autoLogEarnings) {
            settings.autoLogEarnings = it
        }
        card.addView(r1); card.addView(Sections.separator(ctx, p))
        card.addView(r2); card.addView(Sections.separator(ctx, p))
        card.addView(r3); card.addView(Sections.separator(ctx, p))
        card.addView(r4); card.addView(Sections.separator(ctx, p))
        card.addView(r5); card.addView(Sections.separator(ctx, p))
        card.addView(r6)

        // Auto-accept countdown — Mystro's 5-second window, cancellable.
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Auto-accept countdown",
            subtitle = "Tap the countdown on the overlay to cancel",
            iconGlyph = "bolt", iconTint = p.orange,
        ))
        card.addView(LinearLayout(ctx).apply {
            setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), dp(ctx, 14f))
            val delays = listOf(3, 5, 8, 10)
            addView(IosSegmented(ctx, p,
                delays.map { "${it}s" },
                initial = delays.indexOf(settings.autoAcceptDelaySec).coerceAtLeast(0)
            ).apply { onSelected = { settings.autoAcceptDelaySec = delays[it] } },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        val (rRes, _) = Sections.switchRow(ctx, p, "Include reserved offers",
            "Scheduled work can auto-accept too", settings.autoAcceptReservations) {
            settings.autoAcceptReservations = it
        }
        card.addView(rRes)
        // Dry-run the whole pipeline: score → overlay → voice. No logging,
        // no taps — safe to use while parked.
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Send a test offer",
            subtitle = if (OverlayController.isServiceEnabled(ctx))
                "Shows the verdict card + countdown without a real offer"
            else "Enable the service first — then this shows the overlay",
            iconGlyph = "bolt", iconTint = p.tint,
            chevron = OverlayController.isServiceEnabled(ctx),
        ) {
            val svc = GigFlowAccessibilityService.instance
            if (svc != null) svc.testOffer()
            else Toast.makeText(ctx, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
        })
        c_footer(card, "GigFlow only taps when the button is found clearly. It never touches other apps.")
        return card
    }

    private fun rulesCard(): View {
        val card = Sections.card(ctx, p)
        val isKm = settings.distanceUnit == "KM"
        val cur = settings.currencySymbol
        val curEntry = Currencies.forCode(settings.currencyCode)
        val rCur = Sections.row(ctx, p,
            title = "Currency",
            subtitle = curEntry?.name ?: "Custom symbol",
            value = if (curEntry != null) "${curEntry.symbol} · ${curEntry.code}"
                    else settings.currencySymbol,
            iconGlyph = "globe", iconTint = p.tint, chevron = true,
        ) { pickCurrency() }
        val (rMile, mile) = Sections.formField(ctx, p,
            "Min $cur / ${if (isKm) "km" else "mile"}",
            "%.2f".format(if (isKm) settings.minPerMileCents / 1.609344 / 100.0 else settings.minPerMileCents / 100.0))
        val (rHour, hour) = Sections.formField(ctx, p, "Min $cur / hour", "%.2f".format(settings.minPerHourCents / 100.0))
        val (rPayout, payout) = Sections.formField(ctx, p, "Min payout", "%.2f".format(settings.minPayoutCents / 100.0), hint = cur)
        val (rDist, dist) = Sections.formField(ctx, p, "Max distance (${if (isKm) "km" else "mi"})",
            "%.1f".format(if (isKm) settings.maxDistanceKm else settings.maxDistanceKm * 0.621371))
        card.addView(rCur); card.addView(Sections.separator(ctx, p))
        card.addView(rMile); card.addView(Sections.separator(ctx, p))
        card.addView(rHour); card.addView(Sections.separator(ctx, p))
        card.addView(rPayout); card.addView(Sections.separator(ctx, p))
        card.addView(rDist); card.addView(Sections.separator(ctx, p))
        card.addView(LinearLayout(ctx).apply {
            setPadding(dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
            val btn = iosButton(ctx, p, "Save rules")
            addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            btn.setOnClickListener {
                val perDist = (mile.text.toString().toDoubleOrNull() ?: 1.5) * 100
                settings.minPerMileCents = (if (isKm) perDist * 1.609344 else perDist).toInt()
                settings.minPerHourCents = ((hour.text.toString().toDoubleOrNull() ?: 20.0) * 100).toInt()
                settings.minPayoutCents = ((payout.text.toString().toDoubleOrNull() ?: 4.0) * 100).toInt()
                settings.maxDistanceKm = (dist.text.toString().toDoubleOrNull() ?: 25.0).let {
                    if (isKm) it else it / 0.621371
                }
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        c_footer(card, "Thresholds are in your currency — Ft drivers set forint amounts (e.g. 600 Ft/km).")
        return card
    }

    private fun aboutCard(): View {
        val card = Sections.card(ctx, p)
        card.addView(Sections.row(ctx, p,
            title = "Watched apps",
            value = "${ctx.resources.getStringArray(app.gigflow.driver.R.array.watched_packages).size} driver apps",
            iconGlyph = "grid", iconTint = p.tint,
            chevron = true,
        ) { showWatchedApps() })
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Privacy policy",
            iconGlyph = "doc", iconTint = p.tint,
            chevron = true,
        ) { openUrl("https://github.com/w0wzahh/GigFlow/blob/master/docs/legal/privacy-policy.md") })
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Terms of service",
            iconGlyph = "doc", iconTint = p.tint,
            chevron = true,
        ) { openUrl("https://github.com/w0wzahh/GigFlow/blob/master/docs/legal/terms-of-service.md") })
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Check for updates",
            subtitle = "Looks at GitHub Releases for a newer APK",
            iconGlyph = "globe", iconTint = p.tint,
            chevron = true,
        ) {
            UpdateChecker.check(ctx) { rel ->
                if (rel != null) UpdateChecker.showUpdateSheet(ctx, p, rel)
                else Toast.makeText(ctx,
                    "You're up to date (v${UpdateChecker.currentVersion(ctx)})",
                    Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Version",
            value = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) { "?" },
            iconGlyph = "doc", iconTint = p.gray,
        ))
        c_footer(card, "Everything stays on-device. GigFlow isn't affiliated with any gig platform.")
        return card
    }

    /** Units, overlay timing, haptics, log management. */
    private fun preferencesCard(): View {
        val card = Sections.card(ctx, p)

        // Distance unit — segmented
        card.addView(prefSegmentedRow("Units", listOf("Miles", "Kilometres"),
            if (settings.distanceUnit == "KM") 1 else 0) {
            settings.distanceUnit = if (it == 1) "KM" else "MI"
        })
        card.addView(Sections.separator(ctx, p))

        // Overlay timeout — segmented
        val secs = listOf(0, 10, 30, 60)
        card.addView(prefSegmentedRow("Overlay shows for",
            listOf("Until gone", "10 s", "30 s", "60 s"),
            secs.indexOf(settings.overlaySeconds).coerceAtLeast(0)) {
            settings.overlaySeconds = secs[it]
        })
        card.addView(Sections.separator(ctx, p))

        // Haptics
        val (rHap, _) = Sections.switchRow(ctx, p, "Haptic feedback",
            "Vibrate on taps and toggles", settings.haptics) { settings.haptics = it }
        card.addView(rHap)
        card.addView(Sections.separator(ctx, p))

        // Clear offer log — destructive
        card.addView(Sections.row(ctx, p,
            title = "Clear offer log",
            subtitle = "Removes the local history of scored offers",
            iconGlyph = "trash", iconTint = p.red,
        ) {
            OfferLog.clear(ctx)
            Toast.makeText(ctx, "Offer log cleared", Toast.LENGTH_SHORT).show()
        })
        return card
    }

    private fun prefSegmentedRow(
        label: String, options: List<String>, initial: Int, onPick: (Int) -> Unit,
    ): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 12f))
            addView(TextView(ctx).apply {
                text = label; textSize = Ios.T_SUBHEAD; setTextColor(p.label2)
                setPadding(0, 0, 0, dp(ctx, 8f))
            })
            addView(IosSegmented(ctx, p, options, initial = initial).apply {
                onSelected = onPick
            })
        }
    }

    private val watchedApps = listOf(
        "Uber Driver" to "com.ubercab.driver",
        "Lyft Driver" to "com.lyft.android.driver",
        "Dasher" to "com.doordash.driverapp",
        "Instacart Shopper" to "com.instacart.shopper",
        "Amazon Flex" to "com.amazon.rabbit",
        "Spark Driver" to "com.walmart.sparkdriver",
        "Wolt Courier Partner" to "com.wolt.courierapp",
        "foodora rider" to "com.logistics.rider.foodora",
    )

    /** Mystro-style per-service filters — one row per watched app. */
    private fun perAppCard(): View {
        val card = Sections.card(ctx, p)
        watchedApps.forEachIndexed { i, (name, pkg) ->
            if (i > 0) card.addView(Sections.separator(ctx, p))
            val overridden = settings.platformOverride(pkg) != null
            card.addView(Sections.row(ctx, p,
                title = name,
                value = if (overridden) "Custom" else null,
                iconGlyph = "slider", iconTint = if (overridden) p.orange else p.gray,
                chevron = true,
            ) { editAppRules(name, pkg) })
        }
        return card
    }

    /** Override sheet: same rule fields, scoped to one driver app. */
    private fun editAppRules(name: String, pkg: String) {
        val sheet = IosSheet(ctx, p)
        val isKm = settings.distanceUnit == "KM"
        val cur = settings.currencySymbol
        val eff = settings.rulesFor(pkg) // effective rules: override ?? global

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 16f))
            addView(TextView(ctx).apply {
                text = name
                textSize = Ios.T_HEADLINE; setTypeface(typeface, Typeface.BOLD); setTextColor(p.label)
            })
            addView(TextView(ctx).apply {
                text = "Overrides the global rules for offers from this app."
                textSize = Ios.T_FOOTNOTE; setTextColor(p.label2)
                setPadding(0, dp(ctx, 2f), 0, dp(ctx, 12f))
            })
        }

        val (rA, swA) = Sections.switchRow(ctx, p, "Auto-accept good offers",
            "Countdown applies", eff.autoAccept) { }
        val (rD, swD) = Sections.switchRow(ctx, p, "Auto-decline bad offers",
            "Immediate", eff.autoDecline) { }
        col.addView(rA); col.addView(Sections.separator(ctx, p)); col.addView(rD)
        col.addView(Sections.separator(ctx, p))

        val (r1, mile) = Sections.formField(ctx, p,
            "Min $cur / ${if (isKm) "km" else "mile"}",
            "%.2f".format(if (isKm) eff.minPerMileCents / 1.609344 / 100.0 else eff.minPerMileCents / 100.0))
        val (r2, hour) = Sections.formField(ctx, p, "Min $cur / hour", "%.2f".format(eff.minPerHourCents / 100.0))
        val (r3, payout) = Sections.formField(ctx, p, "Min payout", "%.2f".format(eff.minPayoutCents / 100.0), hint = cur)
        val (r4, dist) = Sections.formField(ctx, p, "Max distance (${if (isKm) "km" else "mi"})",
            "%.1f".format(if (isKm) eff.maxDistanceKm else eff.maxDistanceKm * 0.621371))
        col.addView(r1); col.addView(Sections.separator(ctx, p))
        col.addView(r2); col.addView(Sections.separator(ctx, p))
        col.addView(r3); col.addView(Sections.separator(ctx, p))
        col.addView(r4)
        sheet.add(col)

        val save = iosButton(ctx, p, "Save overrides")
        val reset = iosButton(ctx, p, "Reset to global rules", p.fill)
        reset.setTextColor(p.label)
        sheet.add(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
            addView(save); addView(reset)
            (reset.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(ctx, 8f)
        })

        save.setOnClickListener {
            val dpm = mile.text.toString().toDoubleOrNull() ?: return@setOnClickListener bad("Min per-distance")
            val dph = hour.text.toString().toDoubleOrNull() ?: return@setOnClickListener bad("Min per-hour")
            val pay = payout.text.toString().toDoubleOrNull() ?: return@setOnClickListener bad("Min payout")
            val d = dist.text.toString().toDoubleOrNull() ?: return@setOnClickListener bad("Max distance")
            settings.setPlatformOverride(pkg, JSONObject()
                .put("autoAccept", swA.isChecked)
                .put("autoDecline", swD.isChecked)
                .put("minPerMileCents", ((if (isKm) dpm * 1.609344 else dpm) * 100).toInt())
                .put("minPerHourCents", (dph * 100).toInt())
                .put("minPayoutCents", (pay * 100).toInt())
                .put("maxDistanceKm", if (isKm) d else d * 1.609344)
            )
            Toast.makeText(ctx, "$name rules saved", Toast.LENGTH_SHORT).show()
            sheet.dismiss(); refresh()
        }
        reset.setOnClickListener {
            settings.clearPlatformOverride(pkg)
            sheet.dismiss(); refresh()
        }
        sheet.show()
    }

    private fun bad(msg: String) {
        Toast.makeText(ctx, "$msg must be a number", Toast.LENGTH_SHORT).show()
    }

    /** Every currency in the registry, plus a custom-symbol escape hatch. */
    private fun pickCurrency() {
        val sheet = IosSheet(ctx, p)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 10f))
            addView(TextView(ctx).apply {
                text = "Currency"
                textSize = Ios.T_HEADLINE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.label)
            })
            addView(TextView(ctx).apply {
                text = "Used for rule thresholds, amounts and voice alerts. The assistant matches this currency's symbol and code on offer cards."
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.label2)
                setPadding(0, dp(ctx, 2f), 0, 0)
            })
        }
        sheet.add(col)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 8f))
        }
        Currencies.ALL.forEach { c ->
            list.addView(currencyRow(c, sheet))
        }
        list.addView(Sections.separator(ctx, p))
        list.addView(Sections.row(ctx, p,
            title = "Custom symbol…",
            subtitle = "Type any symbol — offers match on it too",
            iconGlyph = "plus.circle", iconTint = p.gray,
            chevron = true,
        ) { sheet.dismiss(); pickCustomCurrency() })

        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        sheet.add(scroll)
        // Cap the list so the sheet stays a sheet.
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380f))
        sheet.show()
    }

    private fun currencyRow(c: Currencies.Entry, sheet: IosSheet): View {
        val selected = settings.currencyCode == c.code
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 8f), dp(ctx, 20f), dp(ctx, 8f))
        }
        row.addView(TextView(ctx).apply {
            text = c.symbol
            textSize = Ios.T_SUBHEAD
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (selected) p.tint else p.label)
            gravity = android.view.Gravity.CENTER
            background = Ios.rounded(7f,
                if (selected) p.card2 else p.fill, ctx)
        }, LinearLayout.LayoutParams(dp(ctx, 46f), dp(ctx, 30f)).apply {
            rightMargin = dp(ctx, 12f)
        })
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = c.name; textSize = Ios.T_BODY; setTextColor(p.label)
            })
            addView(TextView(ctx).apply {
                text = c.code; textSize = Ios.T_CAPTION; setTextColor(p.label3)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(Icons.view(ctx, "checkmark", p.tint, 16f))
        }
        row.pressable()
        row.setOnClickListener {
            row.haptic()
            settings.currencyCode = c.code
            sheet.dismiss(); refresh()
        }
        return row
    }

    /** Escape hatch for symbols outside the registry. */
    private fun pickCustomCurrency() {
        val sheet = IosSheet(ctx, p)
        val field = android.widget.EditText(ctx).apply {
            hint = "e.g. ¥, R$, din"
            setHintTextColor(p.label3)
            setTextColor(p.label)
            textSize = Ios.T_BODY
            setText(settings.currencySymbol)
            setSingleLine()
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
        }
        sheet.add(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
            addView(TextView(ctx).apply {
                text = "Custom currency"
                textSize = Ios.T_HEADLINE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.label)
                setPadding(0, 0, 0, dp(ctx, 10f))
            })
            addView(field)
        })
        val save = iosButton(ctx, p, "Use this symbol")
        sheet.add(LinearLayout(ctx).apply {
            setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
            addView(save)
        })
        save.setOnClickListener {
            val sym = field.text.toString().trim()
            if (sym.isEmpty()) {
                Toast.makeText(ctx, "Enter a symbol", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.currencySymbol = sym
            sheet.dismiss(); refresh()
        }
        sheet.show()
    }

    private fun showWatchedApps() {
        val sheet = IosSheet(ctx, p)
        val names = watchedApps
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
        }
        col.addView(TextView(ctx).apply {
            text = "Watched apps"
            textSize = Ios.T_HEADLINE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.label)
            setPadding(0, 0, 0, dp(ctx, 10f))
        })
        names.forEach { (name, pkg) ->
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
                addView(TextView(ctx).apply {
                    text = name; textSize = Ios.T_BODY; setTextColor(p.label)
                })
                addView(TextView(ctx).apply {
                    text = pkg; textSize = Ios.T_CAPTION; setTextColor(p.label3)
                })
            })
        }
        col.addView(TextView(ctx).apply {
            text = "The assistant only reads these apps. It can't see anything else on your phone."
            textSize = Ios.T_FOOTNOTE; setTextColor(p.label2)
            setPadding(0, dp(ctx, 8f), 0, 0)
        })
        sheet.add(col).show()
    }

    private fun creditsCard(): View {
        val card = Sections.card(ctx, p)
        card.addView(Sections.row(ctx, p,
            title = "Made by w0wzahh",
            subtitle = "Open source · feedback welcome",
            iconGlyph = "person", iconTint = p.tint,
        ))
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "GitHub",
            subtitle = "github.com/w0wzahh",
            iconGlyph = "link", iconTint = p.gray,
            chevron = true,
        ) { openUrl("https://github.com/w0wzahh") })
        card.addView(Sections.separator(ctx, p))
        card.addView(Sections.row(ctx, p,
            title = "Support on Ko-fi",
            subtitle = "Buy the dev a coffee — ko-fi.com/w0wzahh",
            iconGlyph = "heart", iconTint = p.red,
            chevron = true,
        ) { openUrl("https://ko-fi.com/w0wzahh") })
        return card
    }

    private fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun c_footer(card: LinearLayout, text: String) {
        card.addView(TextView(ctx).apply {
            this.text = text
            textSize = Ios.T_CAPTION
            setTextColor(p.label3)
            setPadding(dp(ctx, 16f), dp(ctx, 6f), dp(ctx, 16f), dp(ctx, 10f))
        })
    }
}
