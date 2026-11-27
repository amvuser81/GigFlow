package app.shiftlit.driver

/**
 * Captured screens, in the same shape Diagnostics keeps for a snap — the
 * replay corpus for the parser.
 *
 * Each fixture is a layout the assistant really meets: a Hungarian Wolt card,
 * an Uber card that quotes miles, a customer receipt whose total looks like a
 * payout, and a card whose numbers carry no units at all.
 */
object ReplayFixtures {

    data class Fixture(
        val name: String,
        val pkg: String,
        val nodes: List<OfferParser.FlatNode>,
    )

    private fun n(text: String, id: String? = null, tap: Boolean = false) =
        OfferParser.FlatNode(text, id, Bounds(0, 0, 400, 48), tap)

    /** 1 850 Ft for 12,4 km — the card the USD-scale gate used to eat. */
    val woltHu = Fixture(
        "Wolt Budapest — 1 850 Ft / 12,4 km",
        "com.wolt.driver",
        listOf(
            n("Új fuvar", "com.wolt.driver:id/offer_title"),
            n("1 850 Ft", "com.wolt.driver:id/payout"),
            n("12,4 km", "com.wolt.driver:id/distance"),
            n("22 perc", "com.wolt.driver:id/duration"),
            n("Elfogadás", "com.wolt.driver:id/accept", tap = true),
            n("Elutasítás", "com.wolt.driver:id/decline", tap = true),
        ),
    )

    val uberUs = Fixture(
        "Uber — \$12.40 / 8.2 mi / 24 min",
        "com.ubercab.driver",
        listOf(
            n("\$12.40", "com.ubercab.driver:id/fare"),
            n("8.2 mi", "com.ubercab.driver:id/distance"),
            n("24 min", "com.ubercab.driver:id/time"),
            n("Accept", "com.ubercab.driver:id/accept", tap = true),
        ),
    )

    /** A receipt: the total is real money, but not this trip's pay. */
    val customerTotal = Fixture(
        "customer receipt read as an offer",
        "com.ubercab.driver",
        listOf(
            n("\$1,850.00", "com.ubercab.driver:id/total"),
            n("12 km", "com.ubercab.driver:id/distance"),
            n("20 min", "com.ubercab.driver:id/time"),
        ),
    )

    /** ¥ is both yen and yuan; only the configured code says which. */
    val cny = Fixture(
        "Meituan — ¥40 / 10 km",
        "com.sankuai.meituan.delivery",
        listOf(
            n("New order", "com.sankuai.meituan.delivery:id/title"),
            n("¥40", "com.sankuai.meituan.delivery:id/fare"),
            n("10 km", "com.sankuai.meituan.delivery:id/distance"),
            n("25 min", "com.sankuai.meituan.delivery:id/time"),
        ),
    )

    /** Numbers without km/perc: the heuristics find nothing to score. */
    val bareNumbers = Fixture(
        "bare numbers, no units",
        "com.deliveroo.driver",
        listOf(
            n("New order", "com.deliveroo.driver:id/title"),
            n("1 850 Ft", "com.deliveroo.driver:id/amount"),
            n("12,4", "com.deliveroo.driver:id/km"),
            n("22", "com.deliveroo.driver:id/min"),
        ),
    )
}
