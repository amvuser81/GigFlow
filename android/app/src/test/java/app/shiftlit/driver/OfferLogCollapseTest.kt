package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rows written before the stable order identity existed must fold together in
 * the feed: one Uber card logged a "shown" row per countdown tick plus its own
 * action row.
 */
class OfferLogCollapseTest {

    private fun row(
        at: Long,
        action: String,
        payout: Int = 1402,
        km: Double = 21.4,
        durationMin: Double = 57.0,
        rules: String? = null,
    ) = OfferLog.Entry(
        at = at,
        pkg = "com.ubercab.driver",
        payoutCents = payout,
        distanceKm = km,
        durationMin = durationMin,
        perMileCents = 66,
        perHourCents = 1476,
        verdict = "BAD",
        action = action,
        rules = rules,
    )

    @Test
    fun countdownDuplicatesCollapseToOne() {
        val rules = "payout $14.02 < $15.00 floor"
        // Newest first, exactly how the bug wrote them: shown, action, shown.
        val rows = listOf(
            row(30_002, "shown", rules = rules),
            row(30_001, "auto_decline", rules = rules),
            row(30_000, "shown", rules = rules),
        )
        val out = OfferLog.collapse(rows)
        assertEquals(1, out.size)
        // The action wins over the plain "shown" rows around it.
        assertEquals("auto_decline", out[0].action)
        assertEquals(rules, out[0].rules)
    }

    @Test
    fun differentPayoutStaysASeparateRow() {
        val rows = listOf(
            row(31_000, "shown", payout = 1502),
            row(30_000, "auto_decline"),
        )
        assertEquals(2, OfferLog.collapse(rows).size)
    }

    @Test
    fun sameNumbersMuchLaterStaySeparate() {
        // Two identical jobs hours apart are two orders, not a duplicate.
        val rows = listOf(
            row(10_800_000, "shown"),
            row(30_000, "auto_decline"),
        )
        assertEquals(2, OfferLog.collapse(rows).size)
    }
}
