package app.gigflow.driver

/**
 * One "point at the card" teaching session over a captured tree.
 *
 * Pure JVM on purpose: the same taps on the same nodes must always produce the
 * same hint, and that is what the tests replay. A session never carries state
 * across screens — every capture starts empty, so re-teaching cannot inherit an
 * anchor from a card that has gone.
 */
class TeachSession(
    val pkg: String,
    val nodes: List<OfferParser.FlatNode>,
) {
    /** Key of the captured layout — the same function the live parser uses. */
    val screen: String = ParseHints.screenKey(nodes)

    val roles: List<String> = ROLES

    /** Role -> index in [nodes]. Index, not node: the same node can appear
     *  twice and an anchor's occurrence depends on which one was tapped. */
    private val picks = LinkedHashMap<String, Int>()

    fun nodeFor(role: String): OfferParser.FlatNode? = picks[role]?.let { nodes.getOrNull(it) }

    fun anchorFor(role: String): String? = picks[role]?.let { ParseHints.anchor(nodes, it) }

    fun isPicked(role: String): Boolean = picks.containsKey(role)

    fun pickedRoles(): List<String> = picks.keys.toList()

    /**
     * Index of the node under the point: smallest area wins, so the payout text
     * beats the clickable card that contains it, and an "×" beats its header.
     * Same-size nodes prefer the clickable one (an icon's own hit target).
     *
     * For [role] "Payout" a node carrying a readable amount wins outright —
     * tapping a money line inside a card should not teach the card.
     */
    fun hitTest(x: Int, y: Int, role: String? = null): Int? {
        val under = nodes.indices.filter { nodes[it].bounds.contains(x, y) }
        if (under.isEmpty()) return null
        if (role == "Payout") {
            under.filter { OfferParser.moneyOf(nodes[it].text) != null }
                .minByOrNull { area(it) }
                ?.let { return it }
        }
        return under.minWithOrNull(
            compareBy<Int>({ area(it) }, { if (nodes[it].clickable) 0 else 1 }),
        )
    }

    /** Map the point to [role]. Returns the node so the caller can mark it. */
    fun pick(role: String, x: Int, y: Int): OfferParser.FlatNode? {
        val i = hitTest(x, y, role) ?: return null
        picks[role] = i
        return nodes[i]
    }

    /** Undo the most recent pick — the driver usually misses the last tap. */
    fun undo(): String? = picks.keys.lastOrNull()?.also { picks.remove(it) }

    /** Amounts alone cannot teach a screen; a button can. */
    fun isSavable(): Boolean =
        picks["Payout"] != null || picks["Accept"] != null || picks["Decline"] != null

    fun buildHint(): ParseHints.Hint? {
        if (!isSavable()) return null
        return ParseHints.Hint(
            pkg = pkg,
            screen = screen,
            payout = anchorFor("Payout"),
            distance = anchorFor("Distance"),
            duration = anchorFor("Duration"),
            accept = anchorFor("Accept"),
            decline = anchorFor("Decline"),
        )
    }

    private fun area(i: Int): Int = nodes[i].bounds.width() * nodes[i].bounds.height()

    companion object {
        val ROLES = listOf("Payout", "Distance", "Duration", "Accept", "Decline")
    }
}
