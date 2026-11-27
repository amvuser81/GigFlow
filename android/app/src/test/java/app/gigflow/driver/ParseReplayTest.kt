package app.gigflow.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays captured screens through the parser.
 *
 * Two of these are the teach-mode safety net: a hint has to rescue a screen the
 * heuristics miss, and it must never change — or break — one they already read.
 */
class ParseReplayTest {

    @Test
    fun forintCardSurvivesThePlausibilityGate() {
        val offer = OfferParser.parse(ReplayFixtures.woltHu.pkg, ReplayFixtures.woltHu.nodes)
        assertNotNull("1 850 Ft for 12,4 km was thrown away by the gate", offer)
        val o = offer!!
        assertEquals(185_000, o.payoutCents)
        assertEquals("Ft", o.currencySymbol)
        assertEquals(12.4, o.distanceKm!!, 0.001)
        assertEquals(22.0, o.durationMin!!, 0.001)
    }

    @Test
    fun cnyResolvesByIsoCodeNotByTheYenSymbol() {
        val f = ReplayFixtures.cny
        val extra = OfferParser.extraMoney("CNY", "¥")
        val offer = OfferParser.parse(f.pkg, f.nodes, extra, "¥", "CNY")
        assertNotNull("¥40 for 10 km must not be read as 40 yen", offer)
        val o = offer!!
        assertEquals(4_000, o.payoutCents)
        assertEquals("¥", o.currencySymbol)
        assertEquals("CNY", o.currencyCode)
        assertEquals(10.0, o.distanceKm!!, 0.001)
        // The contract this rests on: "¥" alone resolves to the first
        // registered entry (JPY); the configured code is what disambiguates.
        assertEquals(7.2, Currencies.usdScale("¥", "CNY"), 0.001)
        assertEquals(150.0, Currencies.usdScale("¥"), 0.001)
    }

    @Test
    fun storedHistoryResolvesItsCurrency() {
        // New rows carry the code.
        assertEquals("CNY", Currencies.codeFor("¥", "CNY", "¥", "CNY"))
        // Old rows didn't: fall back to the setting when the symbol is the
        // configured one, and stay null for a foreign card.
        assertEquals("CNY", Currencies.codeFor("¥", null, "¥", "CNY"))
        assertNull(Currencies.codeFor("$", null, "¥", "CNY"))
        // A stored code outlives a settings change.
        assertEquals("EUR", Currencies.codeFor("€", "EUR", "¥", "CNY"))
        // End to end: how the feed renders a CNY row written before codes.
        assertEquals(
            "¥40.00",
            Currencies.format(4_000, "¥", Currencies.codeFor("¥", null, "¥", "CNY")),
        )
    }

    @Test
    fun formattingAndSpeechFollowTheIsoCodeToo() {
        // JPY writes whole yen and is spoken as "yen"; CNY has cents.
        assertEquals("¥40", Currencies.format(4_000, "¥", "JPY"))
        assertEquals("¥40.00", Currencies.format(4_000, "¥", "CNY"))
        assertEquals("40 yen", Currencies.spokenAmount(4_000, "¥", "JPY"))
        assertEquals("40.00 yuan", Currencies.spokenAmount(4_000, "¥", "CNY"))
        // Given only the ambiguous symbol, the old wrong answer is what remains.
        assertEquals("¥40", Currencies.format(4_000, "¥"))
        assertEquals("40 yen", Currencies.spokenAmount(4_000, "¥"))
    }

    @Test
    fun milesConvertAndTheUsdCardStillParses() {
        val offer = OfferParser.parse(ReplayFixtures.uberUs.pkg, ReplayFixtures.uberUs.nodes)
        assertNotNull(offer)
        val o = offer!!
        assertEquals(1_240, o.payoutCents)
        assertEquals(13.2, o.distanceKm!!, 0.05)
        assertEquals(24.0, o.durationMin!!, 0.001)
    }

    @Test
    fun receiptSizedPayoutIsStillRejected() {
        assertNull(
            "a \$1,850 customer total must not read as a payout",
            OfferParser.parse(ReplayFixtures.customerTotal.pkg, ReplayFixtures.customerTotal.nodes),
        )
    }

    @Test
    fun taughtHintRescuesABareNumberScreen() {
        val f = ReplayFixtures.bareNumbers
        assertNull(
            "this screen is supposed to miss without a hint",
            OfferParser.parse(f.pkg, f.nodes),
        )
        fun at(text: String) = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == text })!!
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = at("1 850 Ft"),
            distance = at("12,4"),
            duration = at("22"),
        )
        val offer = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "KM")
        assertNotNull("the taught mapping should read this screen", offer)
        val o = offer!!
        assertEquals(185_000, o.payoutCents)
        assertEquals(12.4, o.distanceKm!!, 0.001)
        assertEquals(22.0, o.durationMin!!, 0.001)
        assertEquals("Ft", o.currencySymbol)
    }

    @Test
    fun anchorsStayUniqueWhenOneIdCoversManyRows() {
        fun row(t: String) = OfferParser.FlatNode(t, "app:id/row", Bounds(0, 0, 400, 48), false)
        val nodes = listOf(row("A"), row("B"), row("C"))
        val first = ParseHints.anchor(nodes, 0)!!
        val third = ParseHints.anchor(nodes, 2)!!
        assertNotEquals("one id must not give every row the same anchor", first, third)
        assertEquals("A", ParseHints.resolve(nodes, first)?.text)
        assertEquals("C", ParseHints.resolve(nodes, third)?.text)
    }

    @Test
    fun taughtButtonsApplyEvenWhenTheAmountsParse() {
        // A card with no labelled accept/decline — the × is an unlabeled,
        // id-less clickable node.
        val x = OfferParser.FlatNode("", null, Bounds(1000, 20, 1080, 100), true)
        val nodes = listOf(
            OfferParser.FlatNode("1 850 Ft", "app:id/payout", Bounds(0, 100, 800, 200), false),
            OfferParser.FlatNode("12,4 km", null, Bounds(0, 200, 800, 260), false),
            OfferParser.FlatNode("22 perc", null, Bounds(0, 260, 800, 320), false),
            x,
        )
        val pkg = "com.wolt.driver"
        val plain = OfferParser.parse(pkg, nodes)!!
        assertEquals(185_000, plain.payoutCents)
        assertNull("an unnamed × is invisible to the label search", plain.declineNode)

        val hint = ParseHints.Hint(
            pkg = pkg,
            screen = ParseHints.screenKey(nodes),
            decline = ParseHints.anchor(nodes, nodes.lastIndex),
        )
        val taught = OfferParser.parse(pkg, nodes, hint = hint)!!
        assertNotNull("the taught × must reach the offer", taught.declineNode)
        assertEquals("taught buttons never touch the price", 185_000, taught.payoutCents)
    }

    @Test
    fun unlabeledControlsAreTaughtByPosition() {
        val accept = OfferParser.FlatNode("Accept", "app:id/accept", Bounds(0, 400, 400, 480), true)
        val x = OfferParser.FlatNode("", null, Bounds(1000, 20, 1080, 100), true)
        val nodes = listOf(accept, x)
        val anchor = ParseHints.anchor(nodes, 1)!!
        assertTrue("an unlabeled node has no id to anchor on: $anchor", anchor.startsWith("pos:"))
        assertEquals(x.bounds, ParseHints.resolve(nodes, anchor)?.bounds)
        assertNull(
            "a control that moved is not the one that was taught",
            ParseHints.resolve(nodes - x, anchor),
        )
    }

    @Test
    fun taughtBareDistancesFollowTheConfiguredUnit() {
        val f = ReplayFixtures.bareNumbers
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == "1 850 Ft" }),
            distance = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == "12,4" }),
        )
        // A bare "12,4" on a US card is 12.4 mi; the parser stores km.
        val mi = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "MI")!!
        assertEquals(20.0, mi.distanceKm!!, 0.001)
        val km = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "KM")!!
        assertEquals(12.4, km.distanceKm!!, 0.001)
    }

    @Test
    fun trailingDollarCardsParseWithoutAConfiguredCurrencyRegex() {
        // The built-in matcher only knew a prefixed "$", so "18,50 $" fell
        // through entirely and the card read as having no money on it.
        val nodes = listOf(
            OfferParser.FlatNode("18,50 $", null, Bounds(0, 0, 800, 80), false),
            OfferParser.FlatNode("12,4 km", null, Bounds(0, 80, 800, 140), false),
            OfferParser.FlatNode("22 perc", null, Bounds(0, 140, 800, 200), false),
        )
        val offer = OfferParser.parse("app.test", nodes)
        assertNotNull("a trailing $ must match the built-in money matcher", offer)
        assertEquals(1_850, offer!!.payoutCents)
    }

    @Test
    fun unlabeledNodesDoNotShiftIndexAnchorsOrTextKeys() {
        fun labeled(t: String) = OfferParser.FlatNode(t, null, Bounds(0, 0, 10, 10), false)
        fun unlabeled() = OfferParser.FlatNode("", null, Bounds(0, 0, 10, 10), true)
        val plain = listOf(labeled("A"), labeled("B"))
        val withBlank = listOf(labeled("A"), unlabeled(), labeled("B"))
        assertEquals(
            "a blank node must not change a text-only screen key",
            ParseHints.screenKey(plain),
            ParseHints.screenKey(withBlank),
        )
        assertEquals(
            "an index anchor saved before blanks were listed must still land",
            "B",
            ParseHints.resolve(withBlank, ParseHints.anchor(plain, 1))?.text,
        )
    }

    @Test
    fun positionAnchorsTolerateASmallShift() {
        val taught = "pos:1000,20,1080,100"
        val shifted = listOf(OfferParser.FlatNode("", null, Bounds(996, 24, 1076, 104), true))
        assertEquals(
            "a control nudged by the card's insets must still resolve",
            shifted[0],
            ParseHints.resolve(shifted, taught),
        )
        val elsewhere = listOf(OfferParser.FlatNode("", null, Bounds(0, 900, 80, 980), true))
        assertNull(
            "a control in another corner is not the one that was taught",
            ParseHints.resolve(elsewhere, taught),
        )
    }

    @Test
    fun bogusHintCannotFabricateAnOffer() {
        val f = ReplayFixtures.bareNumbers
        // The title node holds no amount, so a hint pointing at it is useless —
        // the parser must not fall back to some other number on the screen.
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = ParseHints.anchor(f.nodes, 0), // the "New order" title
        )
        assertNull(OfferParser.parse(f.pkg, f.nodes, hint = hint))
    }

    @Test
    fun hintNeverChangesAScreenThatAlreadyParses() {
        for (f in listOf(ReplayFixtures.woltHu, ReplayFixtures.uberUs)) {
            val baseline = OfferParser.parse(f.pkg, f.nodes)
            assertNotNull("baseline must parse: ${f.name}", baseline)
            val b = baseline!!
            f.nodes.indices.forEach { i ->
                val anchor = ParseHints.anchor(f.nodes, i)!!
                val hint = ParseHints.Hint(
                    pkg = f.pkg,
                    screen = ParseHints.screenKey(f.nodes),
                    payout = anchor,
                    distance = anchor,
                    duration = anchor,
                )
                val got = OfferParser.parse(f.pkg, f.nodes, hint = hint)
                assertNotNull("${f.name}: hint on node $i killed the parse", got)
                val g = got!!
                assertEquals("${f.name}: payout changed by hint $i", b.payoutCents, g.payoutCents)
                assertEquals("${f.name}: distance changed by hint $i", b.distanceKm!!, g.distanceKm!!, 0.001)
                assertEquals("${f.name}: duration changed by hint $i", b.durationMin!!, g.durationMin!!, 0.001)
            }
        }
    }

    @Test
    fun screenKeysDistinguishLayoutsAndRespectOrderWithoutIds() {
        val a = ReplayFixtures.woltHu.nodes
        val b = ReplayFixtures.uberUs.nodes
        assertNotEquals(ParseHints.screenKey(a), ParseHints.screenKey(b))
        // View ids identify the layout, so node order must not matter.
        assertEquals(ParseHints.screenKey(a), ParseHints.screenKey(a.reversed()))
        // Without ids the key is the wording skeleton, and order matters there
        // because the anchors are positional.
        val noIds = a.map { it.copy(viewId = null) }
        assertNotEquals(ParseHints.screenKey(noIds), ParseHints.screenKey(noIds.reversed()))
    }

    @Test
    fun anchorsResolveToTheNodeTheyPointAt() {
        val nodes = ReplayFixtures.woltHu.nodes
        assertEquals("1 850 Ft", ParseHints.resolve(nodes, ParseHints.anchor(nodes, 1))?.text)
        assertEquals("22 perc", ParseHints.resolve(nodes, "idx:3")?.text)
        // Pre-fix anchors carried no occurrence and are no longer resolvable.
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/payout"))
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/gone@0"))
        assertNull(ParseHints.resolve(nodes, "idx:99"))
    }

    @Test
    fun preFixAnchorsGoBackToAMissInsteadOfGuessingARow() {
        // Two amounts share one id — the layout that made the old anchor pick a
        // row at random. Bare numbers elsewhere keep the heuristics out, so the
        // hint is the only thing that can produce an offer here.
        val nodes = listOf(
            OfferParser.FlatNode("1 200 Ft", "app:id/amount", Bounds(0, 0, 400, 48), false),
            OfferParser.FlatNode("3 600 Ft", "app:id/amount", Bounds(0, 48, 400, 96), false),
            OfferParser.FlatNode("12,4", "app:id/km", Bounds(0, 96, 400, 144), false),
            OfferParser.FlatNode("25", "app:id/min", Bounds(0, 144, 400, 192), false),
        )
        val pkg = "app.test"
        assertNull(
            "an id-only anchor must not resolve to a row",
            ParseHints.resolve(nodes, "id:app:id/amount"),
        )
        val stale = ParseHints.Hint(
            pkg = pkg,
            screen = ParseHints.screenKey(nodes),
            payout = "id:app:id/amount",
            distance = "id:app:id/km",
            duration = "id:app:id/min",
        )
        assertNull(
            "a stale payout anchor must miss rather than score the first amount",
            OfferParser.parse(pkg, nodes, hint = stale),
        )
        assertEquals(
            "the sheet must be able to say which part is out of date",
            "its payout was saved before rows could be told apart",
            ParseHints.staleReason(stale, nodes),
        )
        val trail = ArrayList<String>()
        OfferParser.parse(pkg, nodes, trace = trail, hint = stale)
        assertTrue(
            "the report should explain the miss, got $trail",
            trail.any { it.contains("saved mapping is out of date") },
        )
        // With occurrences recorded, the same screen teaches correctly.
        val fresh = stale.copy(
            payout = ParseHints.anchor(nodes, 1),
            distance = ParseHints.anchor(nodes, 2),
            duration = ParseHints.anchor(nodes, 3),
        )
        val offer = OfferParser.parse(pkg, nodes, hint = fresh, unit = "KM")
        assertNotNull(offer)
        assertEquals(360_000, offer!!.payoutCents)
        assertNull("a fresh mapping is not out of date", ParseHints.staleReason(fresh, nodes))
    }

    @Test
    fun staleReasonExplainsAReTargetThatLeft() {
        val nodes = listOf(
            OfferParser.FlatNode("3 600 Ft", "app:id/amount", Bounds(0, 0, 400, 48), false),
        )
        val gone = ParseHints.Hint(
            pkg = "app.test",
            screen = ParseHints.screenKey(nodes),
            payout = "id:app:id/amount@4",
        )
        assertEquals(
            "a mapping whose row is gone must say so",
            "the payout it pointed at is gone from this screen",
            ParseHints.staleReason(gone, nodes),
        )
        assertEquals(
            "a legacy button anchor is just as stale",
            "its accept was saved before rows could be told apart",
            ParseHints.staleReason(gone.copy(payout = null, accept = "id:app:id/accept"), nodes),
        )
        assertNull(
            "a mapping whose anchors all resolve is not stale",
            ParseHints.staleReason(gone.copy(payout = "id:app:id/amount@0"), nodes),
        )
    }
}
