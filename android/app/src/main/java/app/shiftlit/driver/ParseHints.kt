package app.shiftlit.driver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-taught parse hints — "on this screen of this app, that node is the
 * payout / the distance / the duration".
 *
 * A hint is keyed by package + a screen signature, so it can only ever apply
 * to the layout it was taught on. When the app redesigns that screen the
 * signature changes and the hint quietly stops applying instead of pointing
 * at the wrong text. Hints are a fallback, never an override: [OfferParser]
 * consults them only after the heuristics return nothing, so a taught mapping
 * cannot change a screen that already parses.
 */
object ParseHints {

    private const val FILE = "shiftlit_hints"
    private const val KEY = "hints"
    private const val MAX_PER_APP = 12

    data class Hint(
        val pkg: String,
        val screen: String,
        val payout: String? = null,
        val distance: String? = null,
        val duration: String? = null,
        val accept: String? = null,
        val decline: String? = null,
        val taughtAt: Long = System.currentTimeMillis(),
        /** "teach" for pointer picks, "ai" for a provider proposal. */
        val source: String = "teach",
        /** Model that proposed an AI hint, shown in the mappings list. */
        val model: String? = null,
        /** AI-picked controls stay untrusted for automation until the driver
         *  confirms them against a fresh capture. Taught controls default true. */
        val buttonsConfirmed: Boolean = true,
        val confirmedAt: Long = 0,
        /** True until an AI hint has produced an offer on a live screen. */
        val pendingCheck: Boolean = false,
        val lastTriedAt: Long = 0,
        val lastWorkedAt: Long = 0,
    ) {
        val isAi: Boolean get() = source == "ai"
    }

    /**
     * Node anchor. Apps routinely give one view id to many nodes — every row of
     * a list, a container and its label — so the id alone matches all of them
     * (teaching one row appeared to select ten). The occurrence index makes the
     * anchor point at exactly one node.
     */
    fun anchor(nodes: List<OfferParser.FlatNode>, index: Int): String? {
        val n = nodes.getOrNull(index) ?: return null
        val b = n.bounds
        val id = n.viewId
        return when {
            // An unlabeled control (the "×" on an offer, an icon button) has no
            // text or id to point at; where it sits on screen is the only handle
            // left, and a corner control keeps that from offer to offer.
            n.text.isEmpty() -> "pos:${b.left},${b.top},${b.right},${b.bottom}"
            // Index among *labeled* nodes only: unlabeled clickables are
            // interleaved into the tree now, and counting them would shift
            // every index anchor saved before they were listed.
            id == null -> "idx:" + nodes.take(index).count { it.text.isNotEmpty() }
            else -> {
                val occurrence = nodes.take(index).count { it.viewId == id }
                "id:$id@$occurrence"
            }
        }
    }

    fun resolve(nodes: List<OfferParser.FlatNode>, anchor: String?): OfferParser.FlatNode? {
        if (anchor.isNullOrEmpty()) return null
        if (anchor.startsWith("idx:")) {
            val n = anchor.substring(4).toIntOrNull() ?: return null
            return nodes.filter { it.text.isNotEmpty() }.getOrNull(n)
        }
        if (anchor.startsWith("pos:")) {
            val b = anchor.substring(4).split(',').map { it.toIntOrNull() ?: return null }
            if (b.size != 4) return null
            nodes.firstOrNull {
                it.bounds.left == b[0] && it.bounds.top == b[1] &&
                    it.bounds.right == b[2] && it.bounds.bottom == b[3]
            }?.let { return it }
            // Insets and spacing nudge a corner control by a few pixels between
            // cards. Accept the clickable node that still covers the taught
            // rectangle; anything under half of it is a different control.
            val taught = Bounds(b[0], b[1], b[2], b[3])
            val area = (b[2] - b[0]) * (b[3] - b[1])
            if (area <= 0) return null
            return nodes.filter { it.clickable }
                .maxByOrNull { overlap(it.bounds, taught) }
                ?.takeIf { overlap(it.bounds, taught) * 2 >= area }
        }
        if (!anchor.startsWith("id:")) return null
        val body = anchor.substring(3)
        val at = body.lastIndexOf('@')
        // Anchors saved before the occurrence was recorded ("id:<viewId>") cannot
        // tell rows apart. Resolving one would score whichever node happened to
        // come first, so they are treated as stale: the hint yields nothing and
        // the screen goes back to a miss, where it can be re-taught.
        if (at < 0) return null
        val n = body.substring(at + 1).toIntOrNull() ?: return null
        return nodes.filter { it.viewId == body.substring(0, at) }.getOrNull(n)
    }

    private val DIGITS = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * Screen fingerprint. View ids identify the layout; text doesn't (amounts
     * change every offer). Apps that expose no ids fall back to their wording
     * with digits masked, in tree order, so a different screen — or a
     * reordered one, which would move an index anchor — keys differently.
     */
    /** An anchor saved before the occurrence was part of it ("id:<viewId>"). */
    fun isLegacy(anchor: String?): Boolean =
        anchor != null && anchor.startsWith("id:") && !anchor.substring(3).contains('@')

    /** The mapping saved for exactly this layout key, if any. */
    fun forKey(ctx: Context, pkg: String, screen: String): Hint? =
        if (screen.isBlank()) null else forApp(ctx, pkg).firstOrNull { it.screen == screen }

    /**
     * Why a saved mapping can no longer be used on [nodes], or null when it
     * still points at real nodes. Only anchors are checked — a text pattern has
     * no target to lose.
     */
    fun staleReason(hint: Hint, nodes: List<OfferParser.FlatNode>): String? {
        val anchors = listOf(
            "payout" to hint.payout,
            "distance" to hint.distance,
            "duration" to hint.duration,
            "accept" to hint.accept,
            "decline" to hint.decline,
        )
        anchors.forEach { (role, a) ->
            if (a == null) return@forEach
            if (isLegacy(a)) return "its $role was saved before rows could be told apart"
            if (resolve(nodes, a) == null) return "the $role it pointed at is gone from this screen"
        }
        return null
    }

    fun screenKey(nodes: List<OfferParser.FlatNode>): String {
        val ids = nodes.mapNotNull { it.viewId }.distinct().sorted().take(40)
        if (ids.isNotEmpty()) return "v:" + ids.joinToString(",")
        val shape = nodes.map { DIGITS.replace(it.text, "#") }
            .filter { it.isNotEmpty() } // unlabeled nodes must not change the key
            .distinct().take(8)
        return "t:" + shape.joinToString("|")
    }

    /** Pixels shared by two rectangles — the tolerance measure for a `pos:`
     *  anchor whose control shifted slightly with the card's insets. */
    private fun overlap(a: Bounds, b: Bounds): Int {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bo = minOf(a.bottom, b.bottom)
        return if (r > l && bo > t) (r - l) * (bo - t) else 0
    }

    // --- Storage -------------------------------------------------------------

    private var cache: Map<String, List<Hint>>? = null

    private fun load(ctx: Context): Map<String, List<Hint>> {
        cache?.let { return it }
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        val out = HashMap<String, List<Hint>>()
        if (!raw.isNullOrEmpty()) {
            try {
                val root = JSONObject(raw)
                root.keys().forEach { pkg ->
                    val arr = root.optJSONArray(pkg) ?: return@forEach
                    val list = ArrayList<Hint>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(
                            Hint(
                                pkg = pkg,
                                screen = o.optString("screen"),
                                payout = o.optString("payout").ifBlank { null },
                                distance = o.optString("distance").ifBlank { null },
                                duration = o.optString("duration").ifBlank { null },
                                accept = o.optString("accept").ifBlank { null },
                                decline = o.optString("decline").ifBlank { null },
                                taughtAt = o.optLong("taughtAt"),
                                source = o.optString("source", "teach").ifBlank { "teach" },
                                model = o.optString("model").ifBlank { null },
                                buttonsConfirmed = o.optBoolean("buttonsConfirmed", true),
                                confirmedAt = o.optLong("confirmedAt"),
                                pendingCheck = o.optBoolean("pendingCheck", false),
                                lastTriedAt = o.optLong("lastTriedAt"),
                                lastWorkedAt = o.optLong("lastWorkedAt"),
                            ),
                        )
                    }
                    out[pkg] = list
                }
            } catch (_: Exception) {
                cache = emptyMap()
                return emptyMap()
            }
        }
        cache = out
        return out
    }

    private fun store(ctx: Context, map: Map<String, List<Hint>>) {
        val root = JSONObject()
        map.forEach { (pkg, list) ->
            val arr = JSONArray()
            list.forEach { h ->
                arr.put(
                    JSONObject()
                        .put("screen", h.screen)
                        .put("payout", h.payout ?: "")
                        .put("distance", h.distance ?: "")
                        .put("duration", h.duration ?: "")
                        .put("accept", h.accept ?: "")
                        .put("decline", h.decline ?: "")
                        .put("taughtAt", h.taughtAt)
                        .put("source", h.source)
                        .put("model", h.model ?: "")
                        .put("buttonsConfirmed", h.buttonsConfirmed)
                        .put("confirmedAt", h.confirmedAt)
                        .put("pendingCheck", h.pendingCheck)
                        .put("lastTriedAt", h.lastTriedAt)
                        .put("lastWorkedAt", h.lastWorkedAt),
                )
            }
            root.put(pkg, arr)
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, root.toString()).apply()
        cache = map
    }

    private fun forApp(ctx: Context, pkg: String): List<Hint> = load(ctx)[pkg] ?: emptyList()

    /** The hint taught for exactly this layout, if any. */
    fun forScreen(ctx: Context, pkg: String, nodes: List<OfferParser.FlatNode>): Hint? {
        val key = screenKey(nodes)
        return forApp(ctx, pkg).firstOrNull { it.screen == key }
    }

    /** AI-picked controls may drive automation only after a fresh-capture
     *  confirmation. Taught hints (and no hint at all) are unaffected. */
    fun automationAllowed(hint: Hint?): Boolean =
        hint == null || !hint.isAi || hint.buttonsConfirmed

    /**
     * A mapping can only be confirmed on a capture taken after it was saved.
     * The capture the model was shown — and the snapshot the fix sheet opened
     * from — is never proof that the controls are where it says they are.
     */
    fun canConfirmOn(hint: Hint, capturedAt: Long): Boolean =
        hint.taughtAt <= 0 || capturedAt > hint.taughtAt

    /**
     * Why [hint]'s AI-picked controls cannot drive automation on the capture
     * taken at [capturedAt], or null when they can. Pure so the gate is
     * testable without a Context. A button that isn't clickable is rejected
     * here as well as at save time: an anchor can resolve to a label inside a
     * button, and tapping that label would do nothing.
     */
    fun confirmProblem(
        hint: Hint,
        nodes: List<OfferParser.FlatNode>,
        capturedAt: Long,
    ): String? {
        if (!hint.isAi) return null
        if (!canConfirmOn(hint, capturedAt)) {
            return "This is the capture the mapping was built from. Wait for a new " +
                "screen read, then confirm here."
        }
        if (hint.accept == null && hint.decline == null) return null
        if (staleReason(hint, nodes) != null) {
            return "These controls are no longer on this screen — re-teach it."
        }
        for ((role, anchor) in listOf("accept" to hint.accept, "decline" to hint.decline)) {
            if (anchor == null) continue
            val node = resolve(nodes, anchor)
                ?: return "The $role control is no longer on this screen — re-teach it."
            if (!node.clickable) {
                return "The $role node is not clickable, so automation can't tap it — " +
                    "re-teach this screen with a tappable control."
            }
        }
        return null
    }

    /**
     * Confirms the AI-picked accept/decline on a capture taken at [capturedAt].
     * Returns false when that capture is not newer than the mapping, when an
     * anchor no longer resolves, or when a control isn't clickable — in each
     * case the mapping should be re-taught rather than trusted.
     */
    fun confirmButtons(
        ctx: Context,
        hint: Hint,
        nodes: List<OfferParser.FlatNode>,
        capturedAt: Long,
    ): Boolean {
        if (!hint.isAi) return true
        if (confirmProblem(hint, nodes, capturedAt) != null) return false
        save(ctx, hint.copy(
            buttonsConfirmed = true,
            confirmedAt = System.currentTimeMillis(),
            // Confirming the controls does not prove the mapping reads an
            // offer; only a successful live parse clears pendingCheck.
        ))
        return true
    }

    /** Records what the next live parse of an AI hint did. */
    fun recordResult(ctx: Context, pkg: String, screen: String, worked: Boolean) {
        val hint = forKey(ctx, pkg, screen) ?: return
        val now = System.currentTimeMillis()
        save(ctx, hint.copy(
            lastTriedAt = now,
            lastWorkedAt = if (worked) now else hint.lastWorkedAt,
            pendingCheck = if (worked) false else hint.pendingCheck,
        ))
    }

    /** Every saved mapping, newest first — the management surface. */
    fun all(ctx: Context): List<Hint> =
        load(ctx).values.flatten().sortedByDescending { it.taughtAt }

    fun remove(ctx: Context, hint: Hint) = clear(ctx, hint.pkg, hint.screen)

    fun save(ctx: Context, hint: Hint) {
        val map = load(ctx).toMutableMap()
        val list = (map[hint.pkg] ?: emptyList())
            .filterNot { it.screen == hint.screen }
            .toMutableList()
        list.add(0, hint)
        map[hint.pkg] = list.take(MAX_PER_APP)
        store(ctx, map)
    }

    fun clear(ctx: Context, pkg: String, screen: String) {
        val map = load(ctx).toMutableMap()
        val list = (map[pkg] ?: return).filterNot { it.screen == screen }
        if (list.isEmpty()) map.remove(pkg) else map[pkg] = list
        store(ctx, map)
    }
}
