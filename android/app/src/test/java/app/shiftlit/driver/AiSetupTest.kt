package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSetupTest {

    @Test
    fun readsAFencedJsonReply() {
        val reply = """
            ```json
            {"min_per_mile": 1.85, "min_per_hour": 24, "min_payout": 5,
             "max_distance": 12, "unit": "mi", "currency": "USD",
             "note": "The email asks for at least 1.85 a mile."}
            ```
        """.trimIndent()
        val p = AiSetup.parseProposal(reply)!!
        assertEquals(185, p.minPerMileCents)
        assertEquals(2400, p.minPerHourCents)
        assertEquals(500, p.minPayoutCents)
        assertEquals("USD", p.currencyCode)
        assertEquals("mi", p.unit)
        assertTrue(p.note!!.contains("1.85"))
    }

    @Test
    fun valuesWrappedInStringsStillParse() {
        val p = AiSetup.parseProposal(
            """{"min_per_hour": "21.50", "min_payout": "6"}""",
        )!!
        assertEquals(2150, p.minPerHourCents)
        assertEquals(600, p.minPayoutCents)
        assertNull(p.minPerMileCents)
    }

    @Test
    fun nullsStayNullWhenTheEmailHasNoNumbers() {
        val p = AiSetup.parseProposal(
            """{"min_per_mile": null, "min_per_hour": null, "min_payout": null,
               "max_distance": null, "currency": null, "note": "No rates in this email."}""",
        )!!
        assertNull(p.minPerMileCents)
        assertFalse(p.hasRules)
        assertEquals("No rates in this email.", p.note)
    }

    @Test
    fun aProseAnswerIsNotASetOfRules() {
        assertNull(AiSetup.parseProposal("I could not find any numbers in your email."))
    }

    @Test
    fun distanceFollowsTheUnitTheReplyNames() {
        assertEquals(10.0,
            AiSetup.Proposal(maxDistance = 10.0, unit = "km").maxDistanceKm("MI")!!, 0.001)
        assertEquals(16.093,
            AiSetup.Proposal(maxDistance = 10.0, unit = "miles").maxDistanceKm("KM")!!, 0.001)
        // No unit in the reply: trust the driver's own unit.
        assertEquals(10.0,
            AiSetup.Proposal(maxDistance = 10.0).maxDistanceKm("KM")!!, 0.001)
    }

    @Test
    fun promptCarriesCurrencyUnitRulesAndEmail() {
        val prompt = AiSetup.userPrompt(
            "Hello, I want at least \$2.00 a mile and nothing under \$6.",
            "USD", "mi", "min per mile 1.50, min payout 4.00",
        )
        assertTrue(prompt.contains("USD"))
        assertTrue(prompt.contains("min per mile 1.50"))
        assertTrue(prompt.contains("nothing under \$6"))
    }

    @Test
    fun repeatedAsksKeepAStableSystemInstruction() {
        // The provider is told to answer in one JSON object; a drift here would
        // silently turn every reply into a prose note.
        val a = AiSetup.parseProposal("""{"min_payout": 7, "note": "ok"}""")!!
        val b = AiSetup.parseProposal("""{"min_payout": 7, "note": "ok"}""")!!
        assertEquals(a.minPayoutCents, b.minPayoutCents)
        assertEquals(a.note, b.note)
    }

    @Test
    fun readsANodeMappingFromAFencedReply() {
        val reply = """
            ```json
            {"payout": 14, "distance": 18, "duration": null,
             "accept": 31, "decline": 0,
             "note": "The payout sits next to the timer and the x is unlabeled."}
            ```
        """.trimIndent()
        val fix = AiSetup.parseScreenFix(reply, 40)!!
        assertEquals(14, fix.payout)
        assertEquals(18, fix.distance)
        assertNull(fix.duration)
        assertEquals(31, fix.accept)
        assertEquals(0, fix.decline)
        assertTrue(fix.canSave)
        assertTrue(fix.note!!.contains("unlabeled"))
    }

    @Test
    fun indicesOutsideTheCaptureAreDropped() {
        val fix = AiSetup.parseScreenFix(
            """{"payout": 99, "distance": -1, "decline": 3, "note": "x"}""",
            10,
        )!!
        assertNull(fix.payout)
        assertNull(fix.distance)
        assertEquals(3, fix.decline)
    }

    @Test
    fun aProseScreenAnswerIsNotAMapping() {
        assertNull(AiSetup.parseScreenFix("I think the payout is the third one.", 10))
    }

    @Test
    fun sanitizeKeepsOnlyPicksThatFitTheirRole() {
        val nodes = listOf(
            Diagnostics.Node("New offer", "title", listOf(0, 0, 100, 40), false),
            Diagnostics.Node("\$12.50", "payout", listOf(0, 40, 100, 80), false),
            Diagnostics.Node("4.2 mi", "distance", listOf(0, 80, 100, 120), false),
            Diagnostics.Node("Leave at store", "note", listOf(0, 120, 100, 160), false),
            Diagnostics.Node("", "close", listOf(340, 0, 380, 40), true),
        )
        val fix = AiSetup.sanitize(
            AiSetup.ScreenFix(payout = 0, distance = 2, duration = 3, accept = 1, decline = 4),
            nodes,
        )
        // "New offer" is not money, and "Leave at store" has no duration in it.
        assertNull(fix.payout)
        assertEquals(2, fix.distance)
        assertNull(fix.duration)
        assertEquals(1, fix.accept)
        assertEquals(4, fix.decline)
        assertTrue(fix.canSave)

        // An amount and a distance alone teach nothing — a card needs an amount
        // or a button, exactly like the point-at-the-card flow.
        val amountsOnly = AiSetup.sanitize(
            AiSetup.ScreenFix(payout = 0, distance = 2), nodes,
        )
        assertNull(amountsOnly.payout)
        assertFalse(amountsOnly.canSave)
    }

    @Test
    fun screenPromptListsIndicesAndTheDecisionTrail() {
        val nodes = listOf(
            Diagnostics.Node("New offer", "t", listOf(0, 0, 10, 10), false),
            Diagnostics.Node("\$8.50", "p", listOf(0, 10, 10, 20), false),
            Diagnostics.Node("", "x", listOf(0, 20, 10, 30), true),
        )
        val prompt = AiSetup.screenPrompt(
            "Wolt", "com.wolt.driver", "miss", "v:a,b", nodes,
            listOf("money=2 payout=8.5", "no distance and no duration → not an offer"),
            "USD (\$)", "mi",
        )
        assertEquals(3, prompt.listed)
        assertTrue(prompt.text.contains("\"index\":2"))
        assertTrue(prompt.text.contains("\"clickable\":true"))
        assertTrue(prompt.text.contains("no distance and no duration"))
        assertTrue(prompt.text.contains("com.wolt.driver"))
        // Unlabeled controls need their rectangle; nothing else does.
        assertTrue(prompt.text.contains("\"at\":\"0,20,10,30\""))
    }

    @Test
    fun screenPromptStaysBounded() {
        val nodes = (0 until 900).map { i ->
            Diagnostics.Node("a fairly long node label number $i", "id/$i", listOf(0, 0, 0, 0), false)
        }
        val prompt = AiSetup.screenPrompt(
            "App", "pkg", "miss", "", nodes, emptyList(), "USD (\$)", "mi",
        )
        assertTrue(prompt.listed <= 400)
        assertTrue(prompt.text.length <= 20_100)
        assertTrue(prompt.text.contains("more nodes not shown"))
    }
}
