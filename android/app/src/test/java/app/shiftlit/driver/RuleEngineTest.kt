package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private fun offer(
        payoutCents: Int,
        distanceKm: Double? = 1.0,
        durationMin: Double? = 5.0,
    ) = DetectedOffer(
        appPackage = "com.ubercab.driver",
        payoutCents = payoutCents,
        tipCents = null,
        distanceKm = distanceKm,
        durationMin = durationMin,
        extraDistanceKm = null,
        acceptNode = null,
        declineNode = null,
        rawTexts = emptyList(),
    )

    @Test
    fun oneMissedRuleIsEnoughToSkip() {
        // $2.00 against a $4.00 floor, with per-mile and per-hour both clearing
        // their targets: the floor is the only failure, and it decides.
        val scored = RuleEngine.score(offer(payoutCents = 200), RulePrefs())
        assertEquals(Verdict.BAD, scored.verdict)
        assertTrue(scored.reasons.contains("payout $2.00 < $4.00 floor"))
    }

    @Test
    fun everyRulePassingIsStillGood() {
        val scored = RuleEngine.score(offer(payoutCents = 500), RulePrefs())
        assertEquals(Verdict.GOOD, scored.verdict)
        assertTrue(scored.reasons.isEmpty())
    }

    @Test
    fun reasonsNameEveryFailedRule() {
        // $2.00 over 60 km: under the floor, under per-mile, and past the
        // distance cap all at once — the log has to be able to say all three.
        val scored = RuleEngine.score(
            offer(payoutCents = 200, distanceKm = 60.0, durationMin = 5.0),
            RulePrefs(),
        )
        assertEquals(Verdict.BAD, scored.verdict)
        assertEquals(3, scored.reasons.size)
        assertTrue(scored.reasons.contains("per mi $0.05 < $1.50 target"))
        assertTrue(scored.reasons.contains("payout $2.00 < $4.00 floor"))
        assertTrue(scored.reasons.contains("distance 37.3 mi > 24.9 mi cap"))
    }

    @Test
    fun reasonsFollowTheDistanceUnitSetting() {
        val scored = RuleEngine.score(
            offer(payoutCents = 200, distanceKm = 60.0, durationMin = 5.0),
            RulePrefs(distanceUnit = "KM"),
        )
        // 60 km against a 40 km cap, quoted in km; the per-km rate is 200/60.
        assertTrue(scored.reasons.contains("distance 60.0 km > 40.0 km cap"))
        assertTrue(scored.reasons.any { it.startsWith("per km ") })
    }
}
