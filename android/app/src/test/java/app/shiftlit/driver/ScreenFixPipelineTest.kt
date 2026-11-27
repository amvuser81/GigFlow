package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // The preview the sheet shows before Save is this same parse, formatted.
        val preview = AiSetup.previewOf(offer, km = false)
        assertEquals("\$12.50", preview.payout)
        assertEquals("4.2 mi", preview.distance)
        assertEquals("18 min", preview.duration)
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

    @Test
    fun anAiPickedButtonIsNotTrustedForAutomationUntilConfirmed() {
        val screen = ParseHints.screenKey(flatOf(card))
        val fix = AiSetup.ScreenFix(
            payout = 1, distance = 2, duration = 3, accept = 4, decline = 5,
        )
        val ai = AiSetup.hintFor(
            fix, pkg, screen, flatOf(card), source = "ai", model = "test/model",
        )!!
        assertEquals("ai", ai.source)
        assertEquals("test/model", ai.model)
        assertFalse("an AI-picked control starts unconfirmed", ai.buttonsConfirmed)
        assertFalse(ParseHints.automationAllowed(ai))
        assertTrue("AI mappings start pending until a live read proves them", ai.pendingCheck)

        // Amounts only: there is no control for automation to fire, so nothing
        // needs confirming.
        val amounts = AiSetup.hintFor(
            fix.copy(accept = null, decline = null),
            pkg, screen, flatOf(card), source = "ai",
        )!!
        assertTrue(amounts.buttonsConfirmed)
        assertTrue(ParseHints.automationAllowed(amounts))

        // The point-at-the-card flow is confirmation by construction.
        val taught = AiSetup.hintFor(fix, pkg, screen, flatOf(card))!!
        assertTrue(taught.buttonsConfirmed)
        assertTrue(ParseHints.automationAllowed(taught))
        assertTrue(ParseHints.automationAllowed(null))
    }

    @Test
    fun aiButtonsNeedANewerCaptureToConfirm() {
        val flat = flatOf(card)
        val fix = AiSetup.ScreenFix(
            payout = 1, distance = 2, duration = 3, accept = 4, decline = 5,
        )
        val ai = AiSetup.hintFor(
            fix, pkg, ParseHints.screenKey(flat), flat,
            source = "ai", model = "test/model",
        )!!

        // The capture the mapping was built from — and anything older — is
        // never proof that the controls are where the model said.
        val same = ParseHints.confirmProblem(ai, flat, ai.taughtAt)!!
        assertTrue(same.contains("capture the mapping was built from"))
        assertNotNull(ParseHints.confirmProblem(ai, flat, ai.taughtAt - 1))

        // A later capture with both controls still there and tappable can.
        assertNull(ParseHints.confirmProblem(ai, flat, ai.taughtAt + 1))
    }

    @Test
    fun aNonClickableAcceptIsRejectedBeforeSavingOrConfirming() {
        val nodes = listOf(
            Diagnostics.Node("New offer", "id/title", listOf(0, 0, 1080, 120), false),
            Diagnostics.Node("\$12.50", "id/payout", listOf(40, 140, 500, 220), false),
            Diagnostics.Node("4.2", "id/distance", listOf(40, 240, 500, 300), false),
            Diagnostics.Node("18", "id/duration", listOf(40, 320, 500, 380), false),
            Diagnostics.Node("Accept", "id/accept", listOf(40, 900, 700, 1000), false),
            Diagnostics.Node("Decline", "id/decline", listOf(720, 900, 1040, 1000), true),
        )
        val flat = flatOf(nodes)
        val fix = AiSetup.ScreenFix(
            payout = 1, distance = 2, duration = 3, accept = 4, decline = 5,
        )
        val sanity = AiSetup.sanity(fix, nodes, extra, symbol)
        assertFalse(sanity.ok)
        assertTrue(sanity.blockers.any { it.contains("accept") && it.contains("not clickable") })
        assertNull(
            "a non-clickable control must never become an AI hint",
            AiSetup.hintFor(fix, pkg, ParseHints.screenKey(flat), flat, source = "ai"),
        )

        // Even a hint saved by an earlier build is rejected at confirmation.
        val saved = ParseHints.Hint(
            pkg = pkg,
            screen = ParseHints.screenKey(flat),
            accept = ParseHints.anchor(flat, 4),
            decline = ParseHints.anchor(flat, 5),
            source = "ai",
            buttonsConfirmed = false,
            taughtAt = 1_000,
        )
        val problem = ParseHints.confirmProblem(saved, flat, 2_000)!!
        assertTrue(problem.contains("accept") && problem.contains("not clickable"))
    }

    @Test
    fun sanityRefusesATipOrFeeAsTheTripPayout() {
        val nodes = listOf(
            Diagnostics.Node("New offer", "id/title", listOf(0, 0, 1080, 120), false),
            Diagnostics.Node("\$5.00 tip", "id/tip", listOf(40, 140, 500, 220), false),
            Diagnostics.Node("4.2", "id/distance", listOf(40, 240, 500, 300), false),
            Diagnostics.Node("18", "id/duration", listOf(40, 320, 500, 380), false),
            Diagnostics.Node("Accept", "id/accept", listOf(40, 900, 700, 1000), true),
        )
        val sanity = AiSetup.sanity(
            AiSetup.ScreenFix(payout = 1, distance = 2, duration = 3, accept = 4),
            nodes, extra, symbol,
        )
        assertFalse(sanity.ok)
        assertTrue(sanity.blockers.any { it.contains("tip") })
    }

    @Test
    fun sanityRefusesOneNodeDoingTwoJobs() {
        val sanity = AiSetup.sanity(
            AiSetup.ScreenFix(
                payout = 1, distance = 2, duration = 2, accept = 4, decline = 4,
            ),
            card, extra, symbol,
        )
        assertFalse(sanity.ok)
        assertTrue(sanity.blockers.any { it.contains("distance and duration") })
        assertTrue(sanity.blockers.any { it.contains("accept and decline") })
    }

    @Test
    fun sanityRefusesSwappedAcceptAndDeclinePicks() {
        val nodes = listOf(
            Diagnostics.Node("New offer", "id/title", listOf(0, 0, 1080, 120), false),
            Diagnostics.Node("\$12.50", "id/payout", listOf(40, 140, 500, 220), false),
            Diagnostics.Node("4.2", "id/distance", listOf(40, 240, 500, 300), false),
            Diagnostics.Node("18", "id/duration", listOf(40, 320, 500, 380), false),
            Diagnostics.Node("Decline", "id/decline", listOf(40, 900, 520, 1000), true),
            Diagnostics.Node("Accept", "id/accept", listOf(560, 900, 1040, 1000), true),
        )
        val sanity = AiSetup.sanity(
            AiSetup.ScreenFix(payout = 1, distance = 2, duration = 3, accept = 4, decline = 5),
            nodes, extra, symbol,
        )
        assertFalse(sanity.ok)
        assertTrue(sanity.blockers.any { it.contains("swapped") })
    }

    @Test
    fun sanityFlagsAnUnnamedControlForExplicitConfirmation() {
        val fix = AiSetup.ScreenFix(
            payout = 1, distance = 2, duration = 3, accept = 4, decline = 5,
        )
        val sanity = AiSetup.sanity(fix, card, extra, symbol)
        // The capture's close button is unnamed; saving it needs a human tick.
        assertTrue(sanity.ok)
        assertTrue(sanity.warnings.any { it.contains("Decline is an unnamed control") })
    }

    @Test
    fun aCleanCannedFixHasNoBlockers() {
        // The same deterministic pipeline the sheet runs: reply -> parse ->
        // sanitize -> hint -> real parser -> sanity. No network, no pixels.
        val screen = ParseHints.screenKey(flatOf(card))
        val reply = """{"payout": 1, "distance": 2, "duration": 3,
                        "accept": 4, "decline": 5, "note": "fix"}"""
        val fix = AiSetup.sanitize(
            AiSetup.parseScreenFix(reply, card.size)!!, card, extra, symbol,
        )
        assertTrue(AiSetup.sanity(fix, card, extra, symbol).ok)
        val hint = AiSetup.hintFor(fix, pkg, screen, flatOf(card), source = "ai")!!
        val offer = parseWith(hint)!!
        val preview = AiSetup.previewOf(offer, km = false)
        assertEquals("\$12.50", preview.payout)
        assertEquals("4.2 mi", preview.distance)
        assertEquals("18 min", preview.duration)
    }
}
