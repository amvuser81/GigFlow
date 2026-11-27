package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * One offer, one identity: the card's countdown text ticks every second while
 * the job itself does not change (see DetectedOffer.fingerprint).
 */
class OfferFingerprintTest {

    private fun offer(
        raw: List<String>,
        payoutCents: Int = 1402,
        distanceKm: Double? = 21.4,
        durationMin: Double? = 57.0,
    ) = DetectedOffer(
        appPackage = "com.ubercab.driver",
        payoutCents = payoutCents,
        tipCents = null,
        distanceKm = distanceKm,
        durationMin = durationMin,
        extraDistanceKm = null,
        acceptNode = null,
        declineNode = null,
        rawTexts = raw,
    )

    @Test
    fun countdownTickKeepsTheSameIdentity() {
        // The real Home Depot card: payout, trip and a 15-second countdown.
        val first = offer(listOf("$14.02", "57 min (21.4 mi)", "HOME DEPOT 3855", "0:15"))
        val ticked = offer(listOf("$14.02", "57 min (21.4 mi)", "HOME DEPOT 3855", "0:11"))
        assertEquals(first.fingerprint, ticked.fingerprint)
    }

    @Test
    fun differentMoneyIsADifferentOrder() {
        val a = offer(listOf("$14.02", "57 min (21.4 mi)"))
        val b = offer(listOf("$15.02", "57 min (21.4 mi)"), payoutCents = 1502)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun differentTripIsADifferentOrder() {
        val a = offer(listOf("$14.02", "57 min (21.4 mi)"))
        val b = offer(listOf("$14.02", "22 min (8.1 mi)"), distanceKm = 8.1, durationMin = 22.0)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }
}
