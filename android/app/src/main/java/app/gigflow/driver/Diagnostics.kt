package app.gigflow.driver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app diagnostic snapshots — what the assistant last saw in each watched
 * driver app. This is the user-facing debugging surface: when a screen looks
 * like an offer but doesn't parse, we keep the nodes and the parser's decision
 * so the driver can check "why nothing happened" instead of guessing.
 *
 * The structured [Node] list (text, view id, bounds, clickable) is kept so a
 * snapshot can be re-parsed later — that is what makes it possible to teach
 * the parser on a layout it could not read.
 *
 * Stored in SharedPreferences as one JSON object keyed by package name.
 */
object Diagnostics {

    private const val FILE = "gigflow_diag"
    private const val KEY = "snaps"
    private const val MAX_TEXTS = 60
    private const val MAX_NODES = 80

    // Throttle plain "idle" writes — only interesting outcomes and stale
    // snapshots get persisted, not every accessibility event.
    private const val IDLE_WRITE_MS = 30_000L

    /** One captured view node — enough to replay the parse later. */
    data class Node(
        val text: String,
        val viewId: String?,
        val bounds: List<Int>, // left, top, right, bottom
        val clickable: Boolean,
    )

    data class Snap(
        val at: Long,
        val outcome: String, // "offer" | "miss" | "idle"
        val texts: List<String>,
        val nodes: List<Node> = emptyList(),
        /** [ParseHints.screenKey] of the full node list at capture time — the
         *  teach flow keys a hint on this. Empty for old/text-only snapshots. */
        val screen: String = "",
        /** Why the parser produced (or refused) a result, one line per step. */
        val decision: List<String> = emptyList(),
    )

    private fun load(ctx: Context): JSONObject = try {
        JSONObject(
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}",
        )
    } catch (_: Exception) { JSONObject() }

    /** Text-only snapshot — used when the window itself wasn't readable. */
    fun record(ctx: Context, pkg: String, outcome: String, texts: List<String>) =
        write(ctx, pkg, outcome, texts, emptyList(), "", emptyList())

    /** Full snapshot: every node plus the parser's decision trail. */
    fun record(
        ctx: Context,
        pkg: String,
        outcome: String,
        nodes: List<OfferParser.FlatNode>,
        decision: List<String>,
    ) = write(
        ctx,
        pkg,
        outcome,
        nodes.map { it.text },
        // Keyed from the full list: the cap below could drop the ids a
        // signature computed later from the snapshot would rely on.
        screen = ParseHints.screenKey(nodes),
        nodes = nodes.take(MAX_NODES).map { n ->
            Node(
                text = n.text,
                viewId = n.viewId,
                bounds = listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom),
                clickable = n.clickable,
            )
        },
        decision = decision,
    )

    private fun write(
        ctx: Context,
        pkg: String,
        outcome: String,
        texts: List<String>,
        nodes: List<Node>,
        screen: String,
        decision: List<String>,
    ) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val all = load(ctx)
        val prev = all.optJSONObject(pkg)
        val joined = texts.take(MAX_TEXTS).joinToString("\n")
        // Skip repeat writes: idle is throttled, and an unchanged outcome
        // with identical texts doesn't need re-persisting every frame. An
        // empty stored snapshot (app was still on its splash) never blocks
        // real content, or the first 30s of every launch would look broken.
        if (prev != null &&
            System.currentTimeMillis() - prev.optLong("at") < IDLE_WRITE_MS &&
            (prev.optString("texts").isNotEmpty() || joined.isEmpty()) &&
            (outcome == "idle" ||
                (prev.optString("outcome") == outcome && prev.optString("texts") == joined))
        ) return

        val nodeArr = JSONArray()
        nodes.forEach { n ->
            nodeArr.put(
                JSONObject()
                    .put("text", n.text)
                    .put("viewId", n.viewId ?: "")
                    .put("bounds", JSONArray(n.bounds))
                    .put("clickable", n.clickable),
            )
        }
        val decisionArr = JSONArray()
        decision.forEach { decisionArr.put(it) }

        val o = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("outcome", outcome)
            .put("texts", joined)
            .put("screen", screen)
            .put("nodes", nodeArr)
            .put("decision", decisionArr)
        prefs.edit().putString(KEY, all.put(pkg, o).toString()).apply()
    }

    fun get(ctx: Context, pkg: String): Snap? {
        val all = load(ctx)
        val o = all.optJSONObject(pkg) ?: return null
        return Snap(
            at = o.optLong("at"),
            outcome = o.optString("outcome", "idle"),
            texts = o.optString("texts").split("\n").filter { it.isNotBlank() },
            nodes = o.optJSONArray("nodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { n ->
                        val b = n.optJSONArray("bounds")
                        Node(
                            text = n.optString("text"),
                            viewId = n.optString("viewId").ifBlank { null },
                            bounds = if (b == null) emptyList()
                            else (0 until b.length()).map { b.optInt(it) },
                            clickable = n.optBoolean("clickable"),
                        )
                    }
                }
            } ?: emptyList(),
            screen = o.optString("screen"),
            decision = o.optJSONArray("decision")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
        )
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
