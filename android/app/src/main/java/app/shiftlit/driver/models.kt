package app.shiftlit.driver

/**
 * Screen-space rectangle of a flattened node. Plain ints, deliberately not
 * Rect: the parser and its replay tests have to run on the JVM, where
 * android.* classes are stubs. Gestures build their Path straight from these.
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

/** A node from the target app's view tree we may want to tap. */
data class NodeRef(
    val bounds: Bounds,
    val label: String,
    /** False when the parser only found wording on a non-clickable label;
     *  that is not a control the overlay or automation can tap. */
    val clickable: Boolean = true,
)

/** An offer extracted from a driver app's screen. */
data class DetectedOffer(
    val appPackage: String,
    val payoutCents: Int,
    val tipCents: Int?,
    val distanceKm: Double?,
    val durationMin: Double?,
    val extraDistanceKm: Double?, // e.g. "delivery distance" vs "trip distance"
    val seenAt: Long = System.currentTimeMillis(),
    val acceptNode: NodeRef?,
    val declineNode: NodeRef?,
    val rawTexts: List<String>,
    /** True for scheduled/reservation work (e.g. "Reserved", "Scheduled")
     * rather than a live on-demand ping. */
    val isReservation: Boolean = false,
    /** Display symbol detected on the card — "$", "Ft", "€", … */
    val currencySymbol: String = "$",
    /** ISO code when the card is in the configured currency. The symbol alone
     *  can't tell "¥" (JPY/CNY) apart, and formatting plus speech depend on it. */
    val currencyCode: String? = null,
    /** Seconds the offer gives the driver to decide, if shown on-card. */
    val expiresInSec: Int? = null,
) {
    /**
     * Stable identity for "the same order" while its card is on screen.
     *
     * The card's own countdown ("0:15", "0:14", …) is part of [rawTexts], so
     * a text-based key changes every second: one Uber offer got rescored,
     * re-declined and logged three times before it left the screen. What
     * identifies a job is the app, the money and the trip — none of which move
     * while the card is up.
     */
    val fingerprint: String
        get() = "$appPackage|$payoutCents|${distanceKm ?: "-"}|" +
            "${durationMin?.toInt() ?: "-"}|$isReservation"
}

/** Two outcomes only: every applicable rule passed, or at least one didn't.
 *  There is no borderline band — auto-accept needs GOOD, auto-decline needs
 *  anything else, so a third state could only ever be ambiguous. */
enum class Verdict { GOOD, BAD }

data class ScoredOffer(
    val offer: DetectedOffer,
    val perMileCents: Int?,
    val perHourCents: Int?,
    val verdict: Verdict,
    val reasons: List<String>,
)
