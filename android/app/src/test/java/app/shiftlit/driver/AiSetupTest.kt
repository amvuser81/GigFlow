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
}
