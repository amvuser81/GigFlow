package app.gigflow.driver

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic offer-card extractor.
 *
 * Driver apps change their layouts constantly and don't publish view-tree
 * contracts, so this intentionally works off *textual patterns* rather than
 * view IDs: find currency figures, distance and duration text, and the
 * accept/decline affordances. Tuned per-app where a reliable anchor exists.
 */
object OfferParser {

    // A parse that survives the heuristics can still be nonsense — a customer
    // total read as the payout, a bonus banner read as a rate. Readings outside
    // these ranges are treated as "couldn't read" rather than shown as a
    // confident, wrong verdict. Wide on purpose: real offers land well inside.
    //
    // The bounds are USD per km / USD per hour: the rate is normalized through
    // Currencies.usdScale first, or a 1 850 Ft card would read as 154/km and be
    // thrown away for being in a currency the numbers weren't written for.
    private val PLAUSIBLE_PER_KM = 0.15..25.0    // ≈ $0.24–40 per mile
    private val PLAUSIBLE_PER_HOUR = 1.5..250.0

    // Money: symbol/code-prefixed ($12.50, €8.90) or code-suffixed (850 Ft,
    // 12,50 EUR). Drivers in Europe get Ft/kr/zł cards — $-only parsing
    // means nothing is ever detected. Deliberately case-sensitive: lowercase
    // "ft" is feet, not forint. The (?![\p{L}\p{N}]) tail instead of \b keeps
    // tokens ending in ł/č/₺ matching — and stays inside what Android's
    // ICU regex engine can compile (no (?U), no lookbehind).
    val MONEY = Regex(
        "([$€£₺₴]|USD|EUR|GBP)\\s*(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)|" +
            "(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)\\s*" +
            "(Ft|HUF|huf|EUR|eur|kr|Kr|KR|SEK|NOK|DKK|PLN|pln|zł|Zł|RON|ron|" +
            "lei|CZK|czk|Kč|TL|TRY|uah|UAH|₺|₴|USD|usd)(?![\\p{L}\\p{N}])",
    )
    private val CURRENCY_WORDS = mapOf(
        "$" to "$", "€" to "€", "£" to "£",
        "ft" to "Ft", "huf" to "Ft", "eur" to "€", "gbp" to "£",
        "kr" to "kr", "sek" to "kr", "nok" to "kr", "dkk" to "kr",
        "pln" to "zł", "zł" to "zł", "ron" to "lei", "lei" to "lei",
        "czk" to "Kč", "kč" to "Kč", "tl" to "₺", "try" to "₺", "₺" to "₺",
        "uah" to "₴", "₴" to "₴", "usd" to "$",
    )

    /**
     * Locale-safe amount parse: "12,50"→12.5, "1.850"→1850 (3+ digits after
     * the last separator = thousands), "2 340"→2340, "1,234.56"→1234.56.
     */
    private fun parseAmount(raw: String): Double? {
        val s = raw.replace(" ", "").replace(" ", "").replace("'", "")
        val lastSep = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
        val normalized = if (lastSep >= 0 && s.length - lastSep - 1 in 1..2) {
            s.substring(0, lastSep).filter { it.isDigit() } + "." + s.substring(lastSep + 1)
        } else {
            s.filter { it.isDigit() }
        }
        return normalized.toDoubleOrNull()
    }

    /**
     * Money matcher for the user's *configured* currency, covering symbols
     * the built-in set doesn't know (R$, ₩, "KSh", …). Both the symbol and
     * the ISO code match, prefix or suffix. Cached — building a regex on
     * every 350ms scan would be wasteful.
     */
    private var extraKey = ""
    private var extraRe: Regex? = null

    /** ISO codes that double as everyday words — "TOP 100 drivers" must not
     *  parse as 100 pa'anga. The symbol still matches for these currencies. */
    private val WORDY_CODES = setOf(
        "ALL", "AND", "ARE", "BAM", "BOB", "BUT", "CAN", "FOR", "GEL", "GET",
        "HAS", "HIT", "HOT", "MAD", "MAN", "NEW", "NOW", "OFF", "ONE", "PAY",
        "PEN", "PER", "RON", "SAR", "SEE", "SOS", "TOP", "TRY", "TWO", "USE",
        "VES", "WON", "YER", "CUSTOM",
    )

    fun extraMoney(code: String, symbol: String): Regex? {
        val toks = listOf(symbol, code)
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    it.lowercase() !in CURRENCY_WORDS &&
                    it.uppercase() !in WORDY_CODES
            }
            .distinct()
        if (toks.isEmpty()) return null
        val key = toks.joinToString("|")
        if (key == extraKey) return extraRe
        val alt = toks.joinToString("|") { Regex.escape(it) }
        val num = "(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)"
        // Lookahead, not \b — \b can't anchor after non-word symbols (₺, ₩, R$)
        // so "100 ₺" would silently fail at end-of-string.
        extraRe = Regex("(?:$alt)\\s*$num|$num\\s*(?:$alt)(?![\\p{L}\\p{N}])")
        extraKey = key
        return extraRe
    }

    /** Extract amount + display symbol from a text, or null. [extra] is the
     *  configured-currency matcher from [extraMoney]; its hits report
     *  [extraSymbol] as the currency. */
    fun moneyOf(text: String, extra: Regex? = null, extraSymbol: String = "$"): Pair<Double, String>? {
        MONEY.find(text)?.let { m ->
            val amount = parseAmount(m.groupValues[2].ifEmpty { m.groupValues[3] })
                ?: return@let
            val token = m.groupValues[1].ifEmpty { m.groupValues[4] }
            return amount to (CURRENCY_WORDS[token.lowercase()] ?: "$")
        }
        val m = extra?.find(text) ?: return null
        val amount = parseAmount(m.groupValues[1].ifEmpty { m.groupValues[2] })
            ?: return null
        return amount to extraSymbol
    }
    // Comma decimals ("12,4 km") are how most of Europe writes them.
    private val MILES = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*(mi|miles?|mile)\b""", RegexOption.IGNORE_CASE)
    private val KM = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)
    // "min" covers most EU languages; Hungarian offers show "perc" instead.
    private val MINUTES = Regex(
        """(\d{1,3})\s*(minutes|minuten|minutter|minuter|minute|mins?|perc|percet|minut)\b""",
        RegexOption.IGNORE_CASE,
    )
    // "1 hr 20 min", "2 h", "1 óra 30 perc", "2 godz.", "3 timer"…
    private val HOURS_MIN = Regex(
        """(\d{1,2})\s*(h|hr|hrs|hour|hours|óra|godz|hod|tuntia|tim|timer)\b\.?\s*(\d{1,2})?\s*(min|perc|minut)?""",
        RegexOption.IGNORE_CASE,
    )
    // "tip" spans most markets; add the ones that differ (HU/DE/PL/FR/ES/IT/…).
    private val TIP_WORD = Regex(
        """tip|gratuit|borraval|trinkgeld|napiw|pourboire|propina|mancia|dricks|drikkepenge|juomarah|spropitn|bahş|чаєв""",
        RegexOption.IGNORE_CASE,
    )

    // Accept wording across the markets Wolt/foodora actually localize to.
    // Word stems keep the table compact (Hungarian "Elfogadás/Elfogadom",
    // Polish "Akceptuj/Przyjmij"…). "swipe" catches Flex's slider label.
    private val ACCEPT_WORDS = Regex(
        """accept|confirm|match me|claim|grab it|start delivery|swipe|""" +
            """accepter|aceptar|aceitar|acceptă|akzept|aksep|annehm|akcept|przyjm|přijm|prija|""" +
            """elfogad|átvett|átvesz|hyväks|godkänn|godkjenn|godta|kabul|zaprim|prejmi|""" +
            """прийма|прийнят|приеми|acceptez|pastāst|patvirt""",
        RegexOption.IGNORE_CASE,
    )
    // "Schedule"/"Book" = claiming a *reserved* block (Amazon Flex) — only
    // applied on screens we already flagged as reservations, so the Schedule
    // nav tab can't be mistaken for an accept affordance.
    private val RESERVE_ACCEPT = Regex("""schedule|book|reserve|foglal|boka""", RegexOption.IGNORE_CASE)
    private val DECLINE_WORDS = Regex(
        """decline|\bpass\b|reject|no thanks|skip|close|dismiss|refus|rechaz|recus|ablehn|avvis|afvis|""" +
            """odmítn|odmiet|odrzu|hylk|elutasít|visszautasít|reddet|atmest|noraid|""" +
            """відхил|отказ|norite|refuza""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMER_HINTS = Regex("""^\d{1,2}$|seconds|másodperc|sekund""", RegexOption.IGNORE_CASE)
    // Offer expiry countdown: "0:12", "12s", "12 sec", "expires in 12".
    private val EXPIRY = Regex(
        """^(\d{1,2}):(\d{2})$|^(\d{1,2})\s*(s|sec|seconds?|mp)\b|expires? in (\d{1,2})""",
        RegexOption.IGNORE_CASE,
    )
    private val RESERVATION = Regex(
        """reserv|scheduled|foglal|ütemez|rezerv|planowan|zaplanowan|bokad|planlagt|varattu|забронь|зарезерв""",
        RegexOption.IGNORE_CASE,
    )

    /** Apps where the whole request card accepts on tap — no labelled button.
     *  Uber docs: "tap anywhere on the black bar"; Lyft: "tap anywhere". */
    private val TAP_ANYWHERE_PKGS = setOf("com.ubercab.driver", "com.lyft.android.driver")

    data class FlatNode(
        val text: String,
        val viewId: String?,
        val bounds: Bounds,
        val clickable: Boolean,
    )

    fun flatten(root: AccessibilityNodeInfo): List<FlatNode> {
        val out = ArrayList<FlatNode>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val t = n.text?.toString()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (t.isNotEmpty()) t else cd
            // Copy everything before recycle() — FlatNode keeps no node refs.
            val r = Rect()
            n.getBoundsInScreen(r)
            val viewId = n.viewIdResourceName
            val clickable = n.isClickable
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let(stack::add)
            }
            n.recycle()
            if (label.isNotEmpty()) {
                out.add(FlatNode(label, viewId, Bounds(r.left, r.top, r.right, r.bottom), clickable))
            }
        }
        return out
    }

    fun parse(
        pkg: String,
        nodes: List<FlatNode>,
        extra: Regex? = null,
        extraSymbol: String = "$",
        /** ISO code of the configured currency — disambiguates shared symbols
         *  such as "¥" (JPY and CNY) and "kr" (SEK/NOK/DKK). */
        currencyCode: String? = null,
        /** Optional decision trail, one line per step — surfaced in Diagnostics. */
        trace: MutableList<String>? = null,
        /** User-taught node mapping for a screen the heuristics can't read. */
        hint: ParseHints.Hint? = null,
    ): DetectedOffer? {
        // Hints only ever apply where the heuristics fail. A screen that already
        // parses is never re-read through a taught mapping, so a wrong hint can
        // neither change nor break a result that works today.
        val auto = parseOnce(pkg, nodes, extra, extraSymbol, currencyCode, trace, null)
        if (auto != null) {
            if (hint != null) trace?.add("hint: ignored (screen already parses)")
            return auto
        }
        if (hint == null) return null
        trace?.add("hint: heuristics missed, trying taught nodes")
        return parseOnce(pkg, nodes, extra, extraSymbol, currencyCode, trace, hint)
    }

    private fun parseOnce(
        pkg: String,
        nodes: List<FlatNode>,
        extra: Regex?,
        extraSymbol: String,
        currencyCode: String?,
        trace: MutableList<String>?,
        hint: ParseHints.Hint?,
    ): DetectedOffer? {
        val texts = nodes.map { it.text }

        // Collect money figures with their context. The card total is usually
        // the largest standalone amount; "incl. tip" lines become tips.
        data class Money(val node: FlatNode, val amount: Double, val currency: String, val raw: String)
        val moneyNodes = nodes.mapNotNull { n ->
            moneyOf(n.text, extra, extraSymbol)?.let { (amount, cur) -> Money(n, amount, cur, n.text) }
        }
        if (moneyNodes.isEmpty() && hint?.payout == null) {
            trace?.add("money=0 → not an offer")
            return null
        }

        // A taught payout wins over the heuristics; it is the only reason this
        // branch exists, and it is parsed as leniently as the node allows.
        val taughtPayout = hint?.payout?.let { a -> ParseHints.resolve(nodes, a) }
        val taughtMoney = taughtPayout?.let { n ->
            moneyOf(n.text, extra, extraSymbol) ?: lenientMoney(n.text, extraSymbol)
        }
        // A taught payout that can't be read means the hint is stale (the app
        // renamed the node). Don't guess with the next money figure on screen.
        if (hint?.payout != null && taughtMoney == null) {
            trace?.add("hint: taught payout unreadable → not an offer")
            return null
        }
        val payout = taughtMoney?.first?.takeIf { it > 0 }
            ?: moneyNodes
                .filter { it.amount > 0.5 && !TIP_WORD.containsMatchIn(it.raw) }
                .maxOfOrNull { it.amount } ?: return null
        val payoutMoney = moneyNodes.firstOrNull { it.amount == payout }
        val currency = taughtMoney?.second ?: (payoutMoney?.currency ?: "$")
        trace?.add(
            if (taughtMoney != null) "taught payout=$payout from ${taughtPayout?.text}"
            else "money=${moneyNodes.size} payout=$payout from ${payoutMoney?.raw ?: "?"}",
        )

        val tip = moneyNodes
            .filter { TIP_WORD.containsMatchIn(it.raw) }
            .maxOfOrNull { it.amount }

        // Distances: take the largest plausible one (total trip distance).
        val distanceKm = hint?.distance?.let { a -> ParseHints.resolve(nodes, a) }
            ?.let { lenientDistance(it.text) }?.takeIf { it in 0.2..500.0 }
            ?: texts.mapNotNull { distanceOf(it) }
                .filter { it in 0.2..500.0 }.maxOrNull()

        // Duration: ignore countdown timers (tiny ints, "seconds").
        val durationMin = hint?.duration?.let { a -> ParseHints.resolve(nodes, a) }
            ?.let { lenientDuration(it.text) }?.takeIf { it in 1.0..600.0 }
            ?: texts.mapNotNull { durationOf(it) }
                .filter { it in 1.0..600.0 }.maxOrNull()

        // Expiry countdown — "0:15" / "12s" style, Mystro shows it ticking.
        val expiresInSec = texts.mapNotNull { t ->
            EXPIRY.find(t.trim())?.let { m ->
                val mm = m.groupValues[1].toIntOrNull()
                if (mm != null) mm * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
                else (m.groupValues[3].ifEmpty { m.groupValues[5] }).toIntOrNull()
            }
        }.filter { it in 1..300 }.minOrNull() // Instacart batch timers run ~4min

        trace?.add("distance=$distanceKm duration=$durationMin")

        // Need at least payout + one of distance/duration to call it an offer.
        if (distanceKm == null && durationMin == null) {
            trace?.add("no distance and no duration → not an offer")
            return null
        }

        // Plausibility gate — a rate that makes no sense means we picked the
        // wrong number (or the wrong screen), not that this was a great offer.
        // Distances are km and the bounds are USD, so normalize through the
        // card's own currency first ("at 360 Ft/USD" in the trace).
        // Only trust the configured code when the amount came through the
        // configured symbol: a "$" card seen by a CNY user is still USD.
        val scale = Currencies.usdScale(currency, currencyCode.takeIf { currency == extraSymbol })
        val perKm = distanceKm?.takeIf { it > 0 }?.let { payout / it / scale }
        val perHour = durationMin?.takeIf { it > 0 }?.let { payout * 60.0 / it / scale }
        val noted = if (scale != 1.0) " at %.0f %s/USD".format(scale, currency) else ""
        if ((perKm != null && perKm !in PLAUSIBLE_PER_KM) ||
            (perHour != null && perHour !in PLAUSIBLE_PER_HOUR)
        ) {
            trace?.add(
                "implausible$noted: ${perKm?.let { "%.2f".format(it) }} USD/km, " +
                    "${perHour?.let { "%.1f".format(it) }} USD/hr → not an offer",
            )
            return null
        }
        trace?.add(
            "rate ok$noted: ${perKm?.let { "%.2f".format(it) }} USD/km, " +
                "${perHour?.let { "%.1f".format(it) }} USD/hr",
        )

        fun nodeMatching(re: Regex, clickableOnly: Boolean): NodeRef? =
            nodes.firstOrNull { n ->
                re.containsMatchIn(n.text) && (!clickableOnly || n.clickable) && n.bounds.width() > 0
            }?.let { it.ref() }

        // Uber/Lyft accept the request when *anywhere* on the card is tapped —
        // there is no labelled Accept control, so the affordance is the card
        // itself: the smallest clickable node containing the payout text.
        fun tapAnywhereAccept(payoutNode: FlatNode?): NodeRef? {
            if (pkg !in TAP_ANYWHERE_PKGS) return null
            val m = payoutNode ?: return null
            val cx = m.bounds.centerX(); val cy = m.bounds.centerY()
            return nodes
                .filter { it.clickable && it.bounds.contains(cx, cy) }
                .minByOrNull { it.bounds.width() * it.bounds.height() }
                ?.let { it.ref() }
        }

        val isReservation = texts.any { RESERVATION.containsMatchIn(it) }

        val taughtAccept = hint?.accept?.let { a -> ParseHints.resolve(nodes, a) }
        val taughtDecline = hint?.decline?.let { a -> ParseHints.resolve(nodes, a) }

        val accept = taughtAccept?.let { it.ref() }
            ?: nodeMatching(ACCEPT_WORDS, clickableOnly = true)
            ?: nodeMatching(ACCEPT_WORDS, clickableOnly = false)
            ?: if (isReservation && pkg == "com.amazon.rabbit") {
                nodeMatching(RESERVE_ACCEPT, clickableOnly = true)
                    ?: nodeMatching(RESERVE_ACCEPT, clickableOnly = false)
            } else null
            ?: tapAnywhereAccept(payoutMoney?.node)
        val decline = taughtDecline?.let { it.ref() }
            ?: nodeMatching(DECLINE_WORDS, clickableOnly = true)
            ?: nodeMatching(DECLINE_WORDS, clickableOnly = false)

        trace?.add("accept=${accept?.label ?: "none"} decline=${decline?.label ?: "none"}")

        return DetectedOffer(
            appPackage = pkg,
            payoutCents = (payout * 100).toInt(),
            tipCents = tip?.let { (it * 100).toInt() },
            distanceKm = distanceKm?.let { Math.round(it * 10) / 10.0 },
            durationMin = durationMin,
            extraDistanceKm = null,
            acceptNode = accept,
            declineNode = decline,
            rawTexts = texts,
            currencySymbol = currency,
            currencyCode = currencyCode.takeIf { currency == extraSymbol },
            expiresInSec = expiresInSec,
            isReservation = isReservation,
        )
    }

    // --- Parsing rules shared by the heuristic and taught paths --------------

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    private fun FlatNode.ref() = NodeRef(bounds, text)

    private fun distanceOf(text: String): Double? =
        MILES.find(text)?.groupValues?.get(1)?.let(::num)?.times(1.609344)
            ?: KM.find(text)?.groupValues?.get(1)?.let(::num)

    private fun durationOf(text: String): Double? {
        if (TIMER_HINTS.containsMatchIn(text)) return null
        return HOURS_MIN.find(text)?.let { m ->
            val h = m.groupValues[1].toDouble()
            // Group 2 is the unit word ("hr", "óra"); minutes digits
            // live in group 3.
            val mnt = m.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            h * 60 + mnt
        } ?: MINUTES.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // --- Taught nodes --------------------------------------------------------

    /** Loosest number a taught node can hold: "1 850 Ft", "12,4", "22". */
    private fun lenientNumber(text: String): Double? =
        Regex("""\d+(?:[.,]\d+)?""").find(text.replace(" ", ""))?.value
            ?.replace(',', '.')?.toDoubleOrNull()

    private fun lenientMoney(text: String, symbol: String): Pair<Double, String>? {
        val raw = Regex("""\d[\d\s.,'\u00A0]*\d|\d""").find(text)?.value ?: return null
        return parseAmount(raw)?.let { it to symbol }
    }

    private fun lenientDistance(text: String): Double? =
        distanceOf(text) ?: lenientNumber(text) // bare number on a distance row = km

    private fun lenientDuration(text: String): Double? {
        durationOf(text)?.let { return it }
        // A card showing "0:22" where a duration belongs.
        val clock = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim())
        if (clock != null) {
            return clock.groupValues[1].toDouble() * 60 + clock.groupValues[2].toDouble()
        }
        return lenientNumber(text) // bare number = minutes, the usual unit
    }
}
