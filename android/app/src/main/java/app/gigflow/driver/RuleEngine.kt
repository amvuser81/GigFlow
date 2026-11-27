package app.gigflow.driver

/**
 * Offer rule engine — scores a parsed offer against the driver's
 * thresholds and produces a verdict plus human-readable reasons.
 * Thresholds are currency-agnostic (compared in cents of whatever
 * currency the offer was in).
 */
data class RulePrefs(
    val enabled: Boolean = true,
    val autoAccept: Boolean = false,
    val autoDecline: Boolean = false,
    val minPerMileCents: Int = 150,
    val minPerHourCents: Int = 2000,
    val minPayoutCents: Int = 400,
    val maxDistanceKm: Double = 40.0,
    val overlaySeconds: Int = 0, // 0 = until offer disappears
)

object RuleEngine {

    fun score(offer: DetectedOffer, prefs: RulePrefs): ScoredOffer {
        val perMile = offer.distanceKm
            ?.takeIf { it > 0 }
            ?.let { (offer.payoutCents / (it / 1.609344)).toInt() } // $/mile
        val perHour = offer.durationMin
            ?.takeIf { it > 0 }
            ?.let { (offer.payoutCents * 60.0 / it).toInt() }

        val reasons = mutableListOf<String>()
        var score = 0
        var checks = 0

        if (perMile != null) {
            checks++
            if (perMile >= prefs.minPerMileCents) score++ else reasons.add("below per-distance target")
        }
        if (perHour != null) {
            checks++
            if (perHour >= prefs.minPerHourCents) score++ else reasons.add("below per-hour target")
        }
        checks++
        if (offer.payoutCents >= prefs.minPayoutCents) score++ else reasons.add("below payout floor")
        if (offer.distanceKm != null) {
            checks++
            if (offer.distanceKm <= prefs.maxDistanceKm) score++ else reasons.add("too far")
        }

        // Strict: one missed rule is enough. Auto-decline keys off BAD, so a
        // card that fails any threshold is declined instead of needing most of
        // them to fail — the old majority band would take a $2.00 job that
        // cleared per-mile and per-hour but sat under the payout floor. With
        // no borderline state, nothing readable (checks == 0) is also BAD:
        // silence must never be mistaken for approval.
        val verdict = if (checks > 0 && score == checks) Verdict.GOOD else Verdict.BAD

        return ScoredOffer(offer, perMile, perHour, verdict, reasons)
    }
}
