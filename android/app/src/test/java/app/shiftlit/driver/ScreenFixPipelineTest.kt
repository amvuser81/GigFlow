package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-fix path end to end, minus HTTP and pixels: a captured card, the
 * exact reply shape a provider is asked for, and the real parser reading the
 * saved mapping.
 *
 * The capture is deliberately one the heuristics cannot read — a bare distance
 * and a bare duration, with no unit words — so a pass cannot come from the
 * heuristics quietly doing the work.
 */
class ScreenFixPipelineTest {

    private val pkg = "com.wolt.driver"
    private val symbol = "$"
    private val extra = OfferParser.extraMoney("USD", symbol)
    private val card = listOf(
        Diagnostics.Node("New offer", "id/title", listOf(0, 0, 1080, 120), false),
        Diagnostics.Node("\$12.50", "id/payout", listOf(40, 140, 500, 220), false),
        Diagnostics.Node("4.2", "id/distance", listOf(40, 240, 500, 300), false),
        Diagnostics.Node("18", "id/duration", listOf(40, 320, 500, 380), false),
        Diagnostics.Node("Accept", "id/accept", listOf(40, 900, 700, 1000), true),
        Diagnostics.Node("", "id/close", listOf(980, 40, 1060, 120), true),
    )

    private fun flatOf(nodes: List<Diagnostics.Node>): List<OfferParser.FlatNode> =
        nodes.map { n ->
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

    private fun parseWith(hint: ParseHints.Hint?): DetectedOffer? = OfferParser.parse(
        pkg, flatOf(card), extra, symbol, "USD", null, hint, "MI",
    )

    @Test
    fun aCannedReplyTeachesTheParserToReadAMissedCard() {
        val screen = ParseHints.screenKey(flatOf(card))

        // Premise: this card misses on its own. Without this, the hint below
        // could pass because the heuristics read the card all along.
        assertNull(
            "the capture must miss without a hint, or this test proves nothing",
            parseWith(null),
        )

        val prompt = AiSetup.screenPrompt(
            "Wolt", pkg, "miss", screen, card,
            listOf(
                "money=1 payout=12.5 from \"\$12.50\"",
                "no distance and no duration → not an offer",
            ),
            "USD (\$)", "mi", emptyList(),
        )
        assertEquals("the prompt lists every captured node", card.size, prompt.listed)

        // The shape the system prompt asks for, fenced the way models often do.
        val reply = """
            ```json
            {"payout": 1, "distance": 2, "duration": 3, "accept": 4, "decline": 5,
             "note": "Bare distance and time; the parser needs these nodes."}
            ```
        """.trimIndent()

        val fix = AiSetup.sanitize(
            AiSetup.parseScreenFix(reply, prompt.listed)!!,
            card, extra, symbol,
        )
        assertEquals(1, fix.payout)
        assertEquals(2, fix.distance)
        assertEquals(3, fix.duration)
        assertEquals(4, fix.accept)
        assertEquals(5, fix.decline)

        // Exactly what the sheet saves.
        val hint = AiSetup.hintFor(fix, pkg, screen, flatOf(card))!!
        assertEquals("\$12.50", ParseHints.resolve(flatOf(card), hint.payout)!!.text)
        assertEquals("id/close", ParseHints.resolve(flatOf(card), hint.decline)!!.viewId)
        assertNull(ParseHints.staleReason(hint, flatOf(card)))

        // The anchors are stable on a fresh capture of the same layout.
        val reread = flatOf(card)
        assertEquals("Accept", ParseHints.resolve(reread, hint.accept)!!.text)
        assertEquals("4.2", ParseHints.resolve(reread, hint.distance)!!.text)

        val offer = parseWith(hint)!!
        assertEquals(1250, offer.payoutCents)
        assertEquals("4.2 mi read as km", 6.8, offer.distanceKm!!, 0.001)
        assertEquals(18.0, offer.durationMin!!, 0.001)
        assertEquals("Accept", offer.acceptNode?.label)
        assertNotNull("the unlabeled close button must still resolve", offer.declineNode)
        assertEquals(980, offer.declineNode!!.bounds.left)
    }

    @Test
    fun aWrongDistancePickCannotBecomeAWorkingMapping() {
        val screen = ParseHints.screenKey(flatOf(card))
        // The model points "distance" at the title: no digits, so it is dropped
        // rather than saved as a mapping that would read nonsense.
        val reply = """{"payout": 1, "distance": 0, "duration": null,
                        "accept": 4, "decline": 5, "note": "guess"}"""
        val fix = AiSetup.sanitize(
            AiSetup.parseScreenFix(reply, card.size)!!, card, extra, symbol,
        )
        assertNull(fix.distance)
        assertTrue(fix.canSave) // the amount and buttons are still usable picks

        val hint = AiSetup.hintFor(fix, pkg, screen, flatOf(card))!!
        // No distance and no duration survive, so the parser refuses the card
        // instead of inventing one — which is what disables Save in the sheet.
        assertNull(parseWith(hint))
    }

    @Test
    fun aFixWithoutAnAmountOrButtonIsNotAHint() {
        assertNull(
            AiSetup.hintFor(
                AiSetup.ScreenFix(distance = 2), pkg,
                ParseHints.screenKey(flatOf(card)), flatOf(card),
            ),
        )
    }
}
