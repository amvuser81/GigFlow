package app.gigflow.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = "id:com.deliveroo.driver:id/amount",
            distance = "id:com.deliveroo.driver:id/km",
            duration = "id:com.deliveroo.driver:id/min",
        )
        val offer = OfferParser.parse(f.pkg, f.nodes, hint = hint)
        assertNotNull("the taught mapping should read this screen", offer)
        val o = offer!!
        assertEquals(185_000, o.payoutCents)
        assertEquals(12.4, o.distanceKm!!, 0.001)
        assertEquals(22.0, o.durationMin!!, 0.001)
        assertEquals("Ft", o.currencySymbol)
    }

    @Test
    fun bogusHintCannotFabricateAnOffer() {
        val f = ReplayFixtures.bareNumbers
        // The title node holds no amount, so a hint pointing at it is useless —
        // the parser must not fall back to some other number on the screen.
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = "id:com.deliveroo.driver:id/title",
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
        assertEquals("1 850 Ft", ParseHints.resolve(nodes, "id:com.wolt.driver:id/payout")?.text)
        assertEquals("22 perc", ParseHints.resolve(nodes, "idx:3")?.text)
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/gone"))
        assertNull(ParseHints.resolve(nodes, "idx:99"))
    }
}
