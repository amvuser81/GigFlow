package app.gigflow.driver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import org.json.JSONObject

/**
 * Watches the configured driver apps' windows, extracts offer cards, scores
 * them against the user's rules, shows the floating verdict, and optionally
 * taps accept/decline when automation toggles are on.
 *
 * Everything runs on-device — no screen content or offer data leaves the phone.
 */
class GigFlowAccessibilityService : AccessibilityService() {

    private lateinit var settings: SettingsRepository
    private lateinit var overlay: OverlayController

    private var lastFingerprint = ""
    private var lastSeenAt = 0L
    private var lastScanAt = 0L
    private var scanToken = 0
    private var activeOffer: ScoredOffer? = null

    /** Live point-at-the-card teach session, if one is running. */
    private var teach: TeachOverlay? = null
    private var teachSession: TeachSession? = null

    /** Last screen the teach bar was offered for, so it can't re-pop on the
     *  same screen every few seconds while the driver stays on it. */
    private var lastMissKey = ""
    private var lastMissPkg = ""

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var hideToken = ""
    private var tts: TextToSpeech? = null
    private var localDb: LocalDb? = null
    private var countdownTicker: Runnable? = null

    /** Driver apps GigFlow reads — the rest of the event stream is only used to
     *  notice that the driver app left the screen. */
    private val watched: Set<String> by lazy {
        resources.getStringArray(R.array.watched_packages).toSet()
    }

    // Auto-shift tracking state
    private var lastDriverAppAt = 0L
    private var idleStopCheck: Runnable? = null
    private val loggedCompletions = mutableSetOf<String>()

    companion object {
        /** Auto-stop shift tracking after this long with no driver-app events. */
        private const val IDLE_STOP_MS = 30 * 60_000L

        /** Frames can flicker mid-animation; give an offer this long to
         *  reappear before hiding the overlay. */
        private const val MISS_GRACE_MS = 2_000L

        /** Re-read delays when a screen has money but didn't parse — offer
         *  cards animate in, so the first read can precede the text. */
        private val RETRY_DELAYS_MS = longArrayOf(250L, 700L)

        /** A verdict never outlives this, even if the card behind it lingers. */
        private const val MAX_OVERLAY_MS = 30_000L

        /** Package name of the synthetic offer from the Assist screen. */
        private const val TEST_PKG = "app.gigflow.test"

        /** A miss bar gives up on its own, like a verdict. */
        private const val MISS_BAR_MS = 20_000L

        /** Live instance while the service is enabled — null otherwise. */
        @Volatile var instance: GigFlowAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsRepository(this)
        overlay = OverlayController(this)
        localDb = LocalDb(this)
        tts = TextToSpeech(this) { /* ready */ }
        instance = this
    }

    /**
     * Synthetic test offer — exercises the whole pipeline: score → overlay →
     * countdown → gesture dispatch → voice. The accept node points at a
     * screen-margin dead zone so the auto-accept tap provably fires without
     * touching anything real; nothing is logged (see recordAction).
     */
    fun testOffer() {
        handler.post {
            // Nothing runs while monitoring is off — including the dry run.
            if (!settings.enabled) {
                toast("Monitoring is off — turn it on first")
                return@post
            }
            val dead = NodeRef(Bounds(8, 1150, 14, 1190), "test")
            val o = DetectedOffer(
                appPackage = TEST_PKG,
                payoutCents = (12.50 * 100).toInt(),
                tipCents = 200,
                distanceKm = 4.2,
                durationMin = 18.0,
                extraDistanceKm = null,
                acceptNode = dead,
                declineNode = dead,
                rawTexts = listOf("Test offer"),
                currencySymbol = settings.currencySymbol,
                currencyCode = settings.currencyCode,
                expiresInSec = 12,
            )
            val scored = RuleEngine.score(o, settings.rules())
            activeOffer = scored // so stopWatching()/onInterrupt clear it too
            val delay = if (settings.autoAccept && scored.verdict == Verdict.GOOD)
                settings.autoAcceptDelaySec else 0
            overlay.show(scored, delay) { action ->
                cancelAutoAccept()
                if (action == OverlayController.Action.EXPIRED) offerGone()
                overlay.hide()
            }
            if (delay > 0) scheduleAutoAccept(scored, dead, delay)
            if (settings.voiceAlerts) {
                tts?.speak(
                    "Test offer, ${spokenAmount(o.payoutCents, o.currencySymbol, o.currencyCode)}",
                    TextToSpeech.QUEUE_FLUSH, null, "test",
                )
            }
        }
    }

    /**
     * UI-initiated teardown, for the same frame the switch is flipped: don't
     * wait for the next accessibility event to clear the overlay and any
     * pending auto-accept tap.
     */
    fun stopWatching() = handler.post { endTeach(); offerGone() }

    /**
     * Point-at-the-card teaching: read the app's live tree, then cover the card
     * with a full-screen overlay so each tap maps to the control under it.
     * Unlike the Diagnostics sheet this never works from a stored snapshot.
     */
    fun startTeach(pkg: String) = handler.post {
        if (teachSession != null) return@post
        val nodes = pickWindow(pkg)?.nodes
        if (nodes == null) {
            toast("Open the card first")
            return@post
        }
        if (nodes.isEmpty()) {
            toast("Couldn't read this screen")
            return@post
        }
        // If the card was already dismissed there is nothing to point at, and
        // a mapping saved here would key the wrong layout.
        if (nodes.none { OfferParser.moneyOf(it.text) != null }) {
            toast("No card on screen")
            return@post
        }
        overlay.hide()
        val session = TeachSession(pkg, nodes)
        teachSession = session
        teach = TeachOverlay(
            service = this,
            session = session,
            onSave = { hint -> saveTeach(session, hint) },
            onCancel = { endTeach() },
        ).also { it.show() }
    }

    fun endTeach() {
        teach?.hide()
        teach = null
        teachSession = null
    }

    /**
     * Persist a session's mapping, then prove it against a fresh read: the card
     * may have changed while the driver was pointing at it, and a hint saved
     * against a stale tree would silently miss on the next card.
     */
    private fun saveTeach(session: TeachSession, hint: ParseHints.Hint): String {
        ParseHints.save(this, hint)
        val fresh = pickWindow(hint.pkg)?.nodes ?: session.nodes
        val extra = OfferParser.extraMoney(settings.currencyCode, settings.currencySymbol)
        val offer = OfferParser.parse(
            hint.pkg, fresh, extra, settings.currencySymbol, settings.currencyCode,
            null, hint, settings.distanceUnit,
        )
        endTeach()
        return if (offer == null) "Saved — this screen still doesn't parse"
        else "Saved — reads as " +
            Currencies.format(offer.payoutCents, offer.currencySymbol, offer.currencyCode)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** A window we could read from, plus what it looked like when scored. */
    private data class WindowPick(
        /** The chosen window's tree, flattened once — the parser reads this. */
        val nodes: List<OfferParser.FlatNode>,
        val id: Int,
        val type: Int,
        val pkg: String,
        val candidates: Int,
        val money: Int,
        val cues: Int,
    )

    /**
     * The window of [pkg] that actually holds the offer. Offer cards live in
     * their own window, and topmost-first is wrong the moment a promo sheet or
     * a dialog sits above the card — so every window the package owns is scored
     * on money plus request wording, and the best one wins. A tie falls back to
     * window order (topmost first), which is the card over the app's own screen.
     */
    private fun pickWindow(pkg: String): WindowPick? {
        val wins = try {
            windows
        } catch (_: Exception) {
            emptyList()
        }
        // The same symbol and ISO code the parser is about to use: a window
        // priced in a currency that only the configured regex knows must score
        // money here, or the window holding the card would look empty.
        val extra = OfferParser.extraMoney(settings.currencyCode, settings.currencySymbol)
        var candidates = 0
        var found = false
        var bestId = -1
        var bestType = -1
        var bestScore = 0
        var bestMoney = 0
        var bestCues = 0
        for (w in wins) {
            val r = w.root ?: continue
            if (r.packageName?.toString() != pkg) {
                r.recycle()
                continue
            }
            candidates++
            // Labels only — no bounds, no ids, no FlatNodes. The winner is
            // flattened once below and that tree goes straight to the parser,
            // so a scan walks the screen twice at most for the whole package.
            val s = OfferParser.scoreTree(r, extra, settings.currencySymbol)
            if (!found || s.score > bestScore) {
                found = true
                bestId = w.id
                bestType = w.type
                bestScore = s.score
                bestMoney = s.money
                bestCues = s.cues
            }
        }
        if (found) {
            val root = try {
                wins.firstOrNull { it.id == bestId }?.root
            } catch (_: Exception) {
                null
            }
            if (root != null) {
                return WindowPick(
                    OfferParser.flatten(root), bestId, bestType, pkg, candidates, bestMoney, bestCues,
                )
            }
        }
        // The window list didn't give us one: fall back to the active root,
        // which is still worth reading even without a window id to report.
        rootInActiveWindow?.let {
            if (it.packageName?.toString() == pkg) {
                val nodes = OfferParser.flatten(it)
                val s = OfferParser.scoreScreen(nodes, extra, settings.currencySymbol)
                return WindowPick(nodes, -1, -1, pkg, candidates, s.money, s.cues)
            }
            it.recycle() // non-matching roots are ours to free
        }
        return null
    }

    /**
     * One line describing every window the service can see: its type and the
     * package that owns its root. Diagnostics uses it to tell "the card lives
     * in another window" from "the card is in this window but unreadable".
     */
    private fun windowInventory(): String = try {
        val wins = windows
        if (wins.isEmpty()) "windows=0"
        else "windows=" + wins.joinToString(" ") { w ->
            val r = w.root
            val name = r?.packageName?.toString() ?: "-"
            r?.recycle()
            "${w.type}:$name"
        }
    } catch (_: Exception) {
        "windows=?"
    }


    /** Hide the overlay only after the offer's been missing for a bit —
     *  single-frame parse misses are normal (animations, partial draws)
     *  and shouldn't flicker the verdict off. */
    private fun maybeOfferGone(now: Long) {
        if (activeOffer == null) return
        if (now - lastSeenAt < MISS_GRACE_MS) return
        offerGone()
    }

    /**
     * Offer the teach bar on a screen that had money but didn't parse. A miss
     * is where teaching matters most, and the card is only live right now —
     * there is no other moment to point at it.
     */
    private fun maybeTeachBar(pkg: String, nodes: List<OfferParser.FlatNode>, taught: Boolean) {
        if (teachSession != null) return
        if (activeOffer != null) return // a verdict is already on screen
        if (pkg != lastMissPkg) {
            lastMissPkg = pkg
            lastMissKey = ""
        }
        val key = ParseHints.screenKey(nodes)
        if (key == lastMissKey) return // same screen — already offered once
        // An earnings or wallet screen carries money but no request cues;
        // teaching on it is noise. A layout the driver taught is the exception.
        if (!taught && !OfferParser.looksLikeOfferForTeaching(
                nodes,
                OfferParser.extraMoney(settings.currencyCode, settings.currencySymbol),
                settings.currencySymbol,
            )
        ) return
        lastMissKey = key
        overlay.showMiss(
            onTeach = { startTeach(pkg) },
            onDismiss = { overlay.hide() },
        )
        // Same lifetime rules as a verdict: gone when the app is, and never
        // longer than the bar's own cap.
        hideToken = "miss:$key"
        hideRunnable?.let { handler.removeCallbacks(it) }
        val token = hideToken
        val r = Runnable { if (hideToken == token) overlay.hide() }
        hideRunnable = r
        handler.postDelayed(r, MISS_BAR_MS)
    }

    /** The money left the screen while the bar was up — a teach bar for a card
     *  that is gone is just a floating label. */
    private fun hideMissBar() {
        if (!hideToken.startsWith("miss:")) return
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideToken = ""
        overlay.hide()
    }

    /**
     * True when one of our own *activity* windows is in front. Our accessibility
     * overlays report the same package name, so a plain package comparison
     * would make every overlay event look like "the user opened GigFlow".
     */
    private fun ourActivityInFront(): Boolean = try {
        val wins = windows
        if (wins.isEmpty()) true
        else wins.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            val root = w.root ?: return@any false
            val ours = root.packageName?.toString() == packageName
            root.recycle()
            ours
        }
    } catch (_: Exception) {
        true
    }

    /** Last-known fix — good enough for tagging where an offer appeared. */
    private fun lastLocation(): Pair<Double, Double>? {
        if (!MileageTracker.hasLocationPermission(this)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) { null }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // The Monitoring switch is the master gate. Android keeps an enabled
        // service bound, so events keep arriving after it's turned off; every
        // one of them has to be inert — no scan, no shift tracking, no overlay.
        if (!settings.enabled) {
            endTeach()
            offerGone()
            return
        }

        // A teach session owns the screen: ignore the card's own events so the
        // scan cannot redraw over the overlay, and end it only when another app
        // (or a different driver app) takes the screen.
        val teaching = teachSession
        if (teaching != null) {
            if (pkg != packageName && (pkg !in watched || pkg != teaching.pkg)) endTeach()
            return
        }

        // Not a driver app: the verdict belongs to a card that is no longer on
        // screen, so drop it. This is what hides the overlay when the user
        // leaves the app, opens the launcher, or switches to another app.
        if (pkg !in watched) {
            // Our own overlays carry this package as well. Only our *activity*
            // being in front means the user opened GigFlow; an overlay's own
            // events must not dismiss the thing that is showing.
            if (pkg == packageName) {
                if (ourActivityInFront()) {
                    endTeach()
                    // The synthetic test offer is meant to be watched from
                    // inside GigFlow — its own activity must not wipe it.
                    val synthetic = activeOffer?.offer?.appPackage == TEST_PKG
                    if (!synthetic) offerGone()
                }
                return
            }
            // Left the driver app: a bar offered here shouldn't block the same
            // screen being offered again when the driver comes back.
            lastMissPkg = ""
            lastMissKey = ""
            // A real verdict dies with its app. The synthetic test offer is the
            // exception — it is shown while this app is in front precisely so
            // the user can watch it run, so our own events must not wipe it.
            val synthetic = activeOffer?.offer?.appPackage == TEST_PKG
            if (activeOffer != null && !synthetic) offerGone()
            return
        }

        // A driver app is in front — feed the auto-shift tracker.
        onDriverAppSeen(now)

        if (now - lastScanAt < 350) return // throttle contentChanged storms
        lastScanAt = now
        scan(pkg, now, attempt = 0, token = ++scanToken)
    }

    /**
     * One read of the driver app's window. [attempt] > 0 is a re-read: offer
     * cards animate in over a few hundred ms, so parsing the first
     * contentChanged event can run before the payout text is laid out.
     */
    private fun scan(pkg: String, now: Long, attempt: Int, token: Int) {
        if (token != scanToken) return // superseded by a newer scan
        val prefs = settings.rulesFor(pkg) // per-app overrides over globals
        // Monitoring switched off while an overlay was up — tear it down.
        if (!prefs.enabled) { offerGone(); return }

        // Symbols beyond the built-in set (R$, ₩, KSh…) match through the
        // user's configured currency so any market parses.
        val curSymbol = settings.currencySymbol
        val extraRe = OfferParser.extraMoney(settings.currencyCode, curSymbol)

        val pick = pickWindow(pkg) ?: run {
            // Window exists but its content isn't retrievable (or the window
            // list hasn't caught up yet) — record it so Diagnostics shows
            // "we saw the app but couldn't read it" rather than nothing.
            Diagnostics.record(
                this, pkg, "idle",
                listOf("(window not readable)", windowInventory()),
            )
            maybeOfferGone(now)
            return
        }

        val nodes = pick.nodes
        val decision = ArrayList<String>(8)
        decision.add("attempt=$attempt nodes=${nodes.size}")
        // Which window produced this read, and why it won. A promo sheet above
        // the card would show up here as the chosen window with no request
        // cues, which is exactly the silent steal this line exists to expose.
        decision.add(
            "window id=${pick.id} type=${pick.type} pkg=${pick.pkg} " +
                "money=${pick.money} cues=${pick.cues}" +
                if (pick.candidates > 1) " (best of ${pick.candidates})" else "",
        )
        if (pick.candidates > 1) decision.add(windowInventory())
        // Android 12+ lets an app mark views data-sensitive; a service that
        // isn't an accessibility tool receives them with no text at all. This
        // is the difference between "Uber renders a canvas" and "Uber redacts
        // the card", so it belongs in the recorded tree.
        val dataSensitive = nodes.count { it.dataSensitive }
        decision.add("sensitive=$dataSensitive blank=${nodes.count { it.text.isEmpty() }}")
        if (dataSensitive > 0 || nodes.isEmpty()) decision.add(windowInventory())
        val hasMoney = nodes.any {
            OfferParser.moneyOf(it.text, extraRe, curSymbol) != null
        }
        // A taught mapping for this exact layout, if the user made one. It is
        // only consulted when the heuristics return nothing (see OfferParser).
        val hint = ParseHints.forScreen(this, pkg, nodes)
        if (hint != null) decision.add("taught hint on this screen")
        val offer = OfferParser.parse(
            pkg, nodes, extraRe, curSymbol, settings.currencyCode, decision, hint,
            settings.distanceUnit,
        )
        if (offer == null) {
            // A screen with a money figure but no parse is a "miss" — the
            // card was probably there but the layout beat the heuristic.
            // Record it so Diagnostics can show what we saw.
            Diagnostics.record(this, pkg, if (hasMoney) "miss" else "idle",
                nodes, decision)
            maybeLogCompletion(pkg, nodes, now, extraRe, curSymbol)
            // Money on screen but nothing parsed: the card may still be
            // animating in, so look again shortly before giving up.
            if (hasMoney && attempt < RETRY_DELAYS_MS.size) {
                handler.postDelayed(
                    { scan(pkg, System.currentTimeMillis(), attempt + 1, token) },
                    RETRY_DELAYS_MS[attempt],
                )
                return
            }
            maybeOfferGone(now)
            if (hasMoney) maybeTeachBar(pkg, nodes, hint != null) else hideMissBar()
            return
        }
        Diagnostics.record(this, pkg, "offer", nodes, decision)
        // A card this screen could parse proves the bar's job here is done;
        // if the same screen later misses, it should get to ask again.
        lastMissKey = ""

        // Same card still on screen — don't rescore/re-overlay every frame.
        if (offer.fingerprint == lastFingerprint && now - lastSeenAt < 60_000) {
            lastSeenAt = now
            return
        }
        lastFingerprint = offer.fingerprint
        lastSeenAt = now

        val scored = RuleEngine.score(offer, prefs)
        activeOffer = scored
        // The rules belong in the same trail as the parse. An auto-decline that
        // skipped a good offer has to be explainable from the screen it saw,
        // not just from the number it acted on.
        Diagnostics.note(
            this, pkg,
            if (scored.reasons.isEmpty()) "rules all pass"
            else "rules fail: ${scored.reasons.joinToString(", ")}",
        )

        val loc = lastLocation()

        OfferLog.add(this, OfferLog.Entry(
            at = offer.seenAt,
            pkg = pkg,
            payoutCents = offer.payoutCents,
            distanceKm = offer.distanceKm,
            durationMin = offer.durationMin,
            perMileCents = scored.perMileCents,
            perHourCents = scored.perHourCents,
            verdict = scored.verdict.name,
            action = "shown",
            lat = loc?.first,
            lng = loc?.second,
            reservation = offer.isReservation,
            currency = offer.currencySymbol,
            currencyCode = offer.currencyCode,
            rules = scored.reasons.joinToString(", ").ifBlank { null },
        ))

        // Mystro-style voice alert for the verdict, when enabled.
        if (settings.voiceAlerts) {
            val label = if (scored.verdict == Verdict.GOOD) "Good offer" else "Skip it"
            tts?.speak(
                "$label, ${spokenAmount(offer.payoutCents, offer.currencySymbol, offer.currencyCode)}",
                TextToSpeech.QUEUE_FLUSH, null, "offer",
            )
        }

        val autoAcceptDelay =
            if (prefs.autoAccept && scored.verdict == Verdict.GOOD && offer.acceptNode != null &&
                (settings.autoAcceptReservations || !offer.isReservation))
                settings.autoAcceptDelaySec else 0

        overlay.show(scored, autoAcceptDelay, onTeach = { startTeach(offer.appPackage) }) { action ->
            cancelAutoAccept()
            when (action) {
                OverlayController.Action.ACCEPT -> offer.acceptNode?.let {
                    acceptGesture(it, offer.appPackage)
                    recordAction(scored, "manual_accept")
                }
                OverlayController.Action.DECLINE -> offer.declineNode?.let {
                    GesturePerformer.tap(this, it.bounds)
                    recordAction(scored, "manual_decline")
                }
                // Offer's on-card timer hit zero — same teardown as the
                // card vanishing; never leave a pending tap behind.
                OverlayController.Action.EXPIRED -> offerGone()
                OverlayController.Action.CANCEL_AUTO -> Unit
            }
            overlay.hide()
        }

        // Optional overlay timeout — 0 = stay until the card disappears, and
        // even then never past MAX_OVERLAY_MS: a verdict for an offer that is
        // long gone is worse than no verdict.
        val secs = prefs.overlaySeconds
        val token = offer.fingerprint
        hideToken = token
        hideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (hideToken == token) overlay.hide() }
        hideRunnable = r
        handler.postDelayed(r, if (secs > 0) secs * 1000L else MAX_OVERLAY_MS)

        // Automation: only when the user explicitly enabled it, and only when
        // we actually found the button's bounds. Auto-accept runs on the
        // configured countdown (like Mystro's) so the driver can cancel.
        if (autoAcceptDelay > 0) {
            scheduleAutoAccept(scored, offer.acceptNode!!, autoAcceptDelay)
        } else if (prefs.autoDecline && scored.verdict == Verdict.BAD) {
            offer.declineNode?.let { node ->
                GesturePerformer.tap(this, node.bounds) { ok ->
                    if (ok) recordAction(scored, "auto_decline")
                }
            }
        }
    }

    /**
     * The accept affordance differs per app: most are a tap on the labelled
     * control, but Amazon Flex uses a "swipe to accept" slider where a tap
     * does nothing. Uber/Lyft need no special gesture — the parser resolves
     * the tappable request card itself for those (tap-anywhere accept).
     */
    private fun acceptGesture(node: NodeRef, pkg: String, done: (Boolean) -> Unit = {}) {
        if (pkg == "com.amazon.rabbit" || node.label.contains("swipe", ignoreCase = true)) {
            GesturePerformer.swipeRight(this, node.bounds, done)
        } else {
            GesturePerformer.tap(this, node.bounds, done)
        }
    }

    /** Tick the overlay countdown down, then tap accept at 0. */
    private fun scheduleAutoAccept(scored: ScoredOffer, node: NodeRef, delaySec: Int) {
        cancelAutoAccept() // a new offer can arrive before the last countdown ended
        var left = delaySec
        val ticker = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) {
                    acceptGesture(node, scored.offer.appPackage) { ok ->
                        if (ok) recordAction(scored, "auto_accept")
                    }
                    overlay.hide()
                } else {
                    overlay.setCountdown(left)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownTicker = ticker
        handler.postDelayed(ticker, 1000)
    }

    /**
     * Auto shift tracking (opt-in): opening any watched driver app starts GPS
     * mileage; 30 minutes without a watched app stops it. The overlay
     * permission we already hold exempts us from background-FGS limits, but
     * guard anyway — worst case is the user taps "Start shift" themselves.
     */
    private fun onDriverAppSeen(now: Long) {
        lastDriverAppAt = now
        if (!settings.autoTrackShift) return
        if (!MileageTracker.hasLocationPermission(this)) return
        if (!MileageTracker.running) {
            try {
                startForegroundService(MileageTracker.startIntent(this))
            } catch (_: Exception) { /* background-start blocked — manual start still works */ }
        }
        // Arm the idle-stop check; every driver-app event pushes it out.
        idleStopCheck?.let { handler.removeCallbacks(it) }
        val check = Runnable {
            if (System.currentTimeMillis() - lastDriverAppAt >= IDLE_STOP_MS &&
                MileageTracker.running
            ) {
                stopService(MileageTracker.stopIntent(this))
            }
        }
        idleStopCheck = check
        handler.postDelayed(check, IDLE_STOP_MS)
    }

    /**
     * Post-trip earnings capture — reads the completed-delivery / earnings
     * summary screen and records the payout as a local earning.
     */
    private fun maybeLogCompletion(
        pkg: String,
        nodes: List<OfferParser.FlatNode>,
        now: Long,
        extra: Regex? = null,
        extraSymbol: String = "$",
    ) {
        if (!settings.autoLogEarnings) return
        val comp = CompletionParser.parse(pkg, nodes, extra, extraSymbol) ?: return
        if (!loggedCompletions.add(comp.fingerprint)) return // already logged this screen
        if (loggedCompletions.size > 200) loggedCompletions.clear()

        val payload = JSONObject()
            .put("amountCents", comp.payoutCents)
            .put("category", "TRIP")
            .put("platformKey", platformKeyFor(pkg))
            .put("notes", "Auto-captured")
            .put("earnedAt", now)
        comp.tipCents?.let { payload.put("tipCents", it) }

        localDb?.insert("earning", payload)
        if (settings.voiceAlerts) {
            tts?.speak(
                // Completion text carries a symbol only; the configured code
                // disambiguates it exactly when the symbol is the configured one.
                "Logged ${spokenAmount(
                    comp.payoutCents, comp.currencySymbol,
                    settings.currencyCode.takeIf { comp.currencySymbol == settings.currencySymbol },
                )}",
                TextToSpeech.QUEUE_ADD, null, "earn",
            )
        }
    }

    /** "12.50 dollars" / "1850 forints" — names and zero-decimal handling
     *  come from the currency registry. */
    private fun spokenAmount(cents: Int, symbol: String, code: String? = null): String =
        Currencies.spokenAmount(cents, symbol, code)

    private fun cancelAutoAccept() {
        countdownTicker?.let { handler.removeCallbacks(it) }
        countdownTicker = null
    }

    private fun recordAction(s: ScoredOffer, action: String) {
        val o = s.offer
        if (o.appPackage == "app.gigflow.test") return // test offer — don't log
        val failed = s.reasons.joinToString(", ").ifBlank { null }
        // Both halves of the audit: the row carries the failed rules, and the
        // trail says which action they produced. Manual actions are recorded
        // the same way so the two can be compared.
        Diagnostics.note(
            this, o.appPackage,
            if (failed == null) "$action (all rules passed)" else "$action — failed: $failed",
        )
        OfferLog.add(this, OfferLog.Entry(
            at = o.seenAt, pkg = o.appPackage, payoutCents = o.payoutCents,
            distanceKm = o.distanceKm, durationMin = o.durationMin,
            perMileCents = s.perMileCents, perHourCents = s.perHourCents,
            verdict = s.verdict.name, action = action,
            lat = null, lng = null,
            reservation = o.isReservation,
            currency = o.currencySymbol,
            currencyCode = o.currencyCode,
            rules = failed,
        ))
    }

    /** Watched-package → platform key used to tag captured earnings. */
    private fun platformKeyFor(pkg: String): String = when {
        "uber" in pkg -> "uber"
        "lyft" in pkg -> "lyft"
        "doordash" in pkg -> "doordash"
        "instacart" in pkg -> "instacart"
        "amazon" in pkg || "rabbit" in pkg -> "amazon-flex"
        "spark" in pkg || "walmart" in pkg -> "spark"
        "wolt" in pkg -> "wolt"
        "foodora" in pkg || "logistics.rider" in pkg -> "foodora"
        else -> "other"
    }

    private fun offerGone() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideToken = ""
        cancelAutoAccept() // never tap bounds for a card that's no longer up
        activeOffer = null
        lastFingerprint = ""
        overlay.hide()
    }

    override fun onInterrupt() {
        endTeach()
        offerGone()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Service toggled off in Settings — tear everything down now, don't
        // wait for onDestroy or a stale `instance` would still answer the UI.
        endTeach()
        offerGone()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        cancelAutoAccept()
        hideRunnable?.let { handler.removeCallbacks(it) }
        idleStopCheck?.let { handler.removeCallbacks(it) }
        overlay.hide()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
