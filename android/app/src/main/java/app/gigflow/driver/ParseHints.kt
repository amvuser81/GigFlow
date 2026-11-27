package app.gigflow.driver

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

    private const val FILE = "gigflow_hints"
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
    ) {
        val roles: Int
            get() = listOfNotNull(payout, distance, duration, accept, decline).size
    }

    /**
     * Node anchor: the view id when the app exposes one (stable across
     * amounts), otherwise the node's index in the flattened list.
     */
    fun anchor(nodes: List<OfferParser.FlatNode>, index: Int): String? {
        val n = nodes.getOrNull(index) ?: return null
        return n.viewId?.let { "id:$it" } ?: "idx:$index"
    }

    fun resolve(nodes: List<OfferParser.FlatNode>, anchor: String?): OfferParser.FlatNode? {
        if (anchor.isNullOrEmpty()) return null
        return when {
            anchor.startsWith("id:") -> {
                val id = anchor.substring(3)
                nodes.firstOrNull { it.viewId == id }
            }
            anchor.startsWith("idx:") ->
                nodes.getOrNull(anchor.substring(4).toIntOrNull() ?: return null)
            else -> null
        }
    }

    /**
     * Screen fingerprint: the sorted view ids on the screen. Text is useless
     * here (amounts and names change every offer); view ids identify the
     * layout. Capped so a long list can't make the key unbounded.
     */
    fun screenKey(nodes: List<OfferParser.FlatNode>): String {
        val ids = nodes.mapNotNull { it.viewId }.distinct().sorted().take(24)
        return if (ids.isEmpty()) "none" else ids.joinToString(",")
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
                        .put("taughtAt", h.taughtAt),
                )
            }
            root.put(pkg, arr)
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, root.toString()).apply()
        cache = map
    }

    fun all(ctx: Context, pkg: String): List<Hint> = load(ctx)[pkg] ?: emptyList()

    /** The hint taught for exactly this layout, if any. */
    fun forScreen(ctx: Context, pkg: String, nodes: List<OfferParser.FlatNode>): Hint? {
        val key = screenKey(nodes)
        return all(ctx, pkg).firstOrNull { it.screen == key }
    }

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
