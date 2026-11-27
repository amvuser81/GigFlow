package app.gigflow.driver

import org.junit.Assert.assertEquals
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
    fun anchorsResolveToTheNodeTheyPointAt() {
        val nodes = ReplayFixtures.woltHu.nodes
        assertEquals("1 850 Ft", ParseHints.resolve(nodes, "id:com.wolt.driver:id/payout")?.text)
        assertEquals("22 perc", ParseHints.resolve(nodes, "idx:3")?.text)
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/gone"))
        assertNull(ParseHints.resolve(nodes, "idx:99"))
    }
}
