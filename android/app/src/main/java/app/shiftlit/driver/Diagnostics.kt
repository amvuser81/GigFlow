package app.shiftlit.driver

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

    private const val FILE = "shiftlit_diag"
    private const val KEY_PREFIX = "snap:"
    private const val LEGACY_KEY = "snaps"
    private const val MAX_TEXTS = 60
    /** Scored screens keep the card-sized window: the report rarely needs more. */
    private const val MAX_NODES = 200

    /** A miss is what gets reported and taught, and it is written at most a few
     *  times per screen (once per re-read), so keeping far more of the tree is
     *  cheap there — a wider window is what makes the email useful. */
    private const val MAX_MISS_NODES = 400

    // Throttle plain "idle" writes — only interesting outcomes and stale
    // snapshots get persisted, not every accessibility event.
    private const val IDLE_WRITE_MS = 30_000L

    /** Reads kept per app, newest first. Each one now carries its full
     *  capture, so ten is the storage budget instead of twenty stubs. */
    private const val MAX_HISTORY = 10
    private const val HIST_PREFIX = "hist:"
    private const val HIST_TEXTS = MAX_TEXTS

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
        /** Nodes on the screen before truncation — the report says
         *  "200 of 431 captured" rather than implying that was all of it. */
        val nodeCount: Int = 0,
        /** [ParseHints.screenKey] of the full node list at capture time — the
         *  teach flow keys a hint on this. Empty for old/text-only snapshots. */
        val screen: String = "",
        /** Why the parser produced (or refused) a result, one line per step. */
        val decision: List<String> = emptyList(),
    )

    /** One read in the per-app history. It keeps the same tree and parser
     *  trail as the current snapshot, so an old offer can still be mapped. */
    data class Entry(
        val at: Long,
        val outcome: String,
        val screen: String,
        val nodeCount: Int,
        val texts: List<String>,
        val decision: List<String>,
        val nodes: List<Node> = emptyList(),
    )

    private fun key(pkg: String) = KEY_PREFIX + pkg

    private fun nodesJson(nodes: List<Node>): JSONArray {
        val arr = JSONArray()
        nodes.forEach { n ->
            arr.put(
                JSONObject()
                    .put("text", n.text)
                    .put("viewId", n.viewId ?: "")
                    .put("bounds", JSONArray(n.bounds))
                    .put("clickable", n.clickable),
            )
        }
        return arr
    }

    private fun nodesFrom(arr: JSONArray?): List<Node> = arr?.let { a ->
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { n ->
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
    } ?: emptyList()

    /**
     * One key per package, not one JSON blob for every app: a miss keeps up to
     * [MAX_MISS_NODES] nodes, and re-serializing every watched app's tree on
     * each scan is where that would stop being cheap.
     */
    private fun load(ctx: Context, pkg: String): JSONObject? = try {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = p.getString(key(pkg), null)
        if (raw != null) JSONObject(raw) else legacy(p)?.optJSONObject(pkg)
    } catch (_: Exception) { null }

    /** Builds before per-package keys kept everything under one "snaps" object;
     *  split it once so an update doesn't blank the last-seen rows. */
    private fun legacy(p: android.content.SharedPreferences): JSONObject? {
        val raw = p.getString(LEGACY_KEY, null) ?: return null
        val root = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val edit = p.edit().remove(LEGACY_KEY)
        root.keys().forEach { k ->
            root.optJSONObject(k)?.let { edit.putString(key(k), it.toString()) }
        }
        edit.apply()
        return root
    }

    /** Text-only snapshot — used when the window itself wasn't readable. */
    fun record(ctx: Context, pkg: String, outcome: String, texts: List<String>) =
        write(
            ctx, pkg, outcome, texts = texts,
            nodes = emptyList(), nodeCount = 0, screen = "", decision = emptyList(),
        )

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
        // Unlabeled clickable nodes are part of the teaching list but read as
        // blank lines in the snapshot text.
        nodes.map { it.text }.filter { it.isNotBlank() },
        // Keyed from the full list: the cap below could drop the ids a
        // signature computed later from the snapshot would rely on.
        screen = ParseHints.screenKey(nodes),
        nodeCount = nodes.size,
        nodes = nodes.take(if (outcome == "miss") MAX_MISS_NODES else MAX_NODES).map { n ->
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
        nodeCount: Int,
        screen: String,
        decision: List<String>,
    ) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val prev = load(ctx, pkg)
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

        val nodeArr = nodesJson(nodes)
        val decisionArr = JSONArray()
        decision.forEach { decisionArr.put(it) }

        val o = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("outcome", outcome)
            .put("texts", joined)
            .put("screen", screen)
            .put("nodeCount", nodeCount)
            .put("nodes", nodeArr)
            .put("decision", decisionArr)
        prefs.edit().putString(key(pkg), o.toString()).apply()
        pushHistory(ctx, pkg, outcome, screen, texts, decision, nodeCount, nodes)
    }

    fun get(ctx: Context, pkg: String): Snap? {
        val o = load(ctx, pkg) ?: return null
        return Snap(
            at = o.optLong("at"),
            outcome = o.optString("outcome", "idle"),
            texts = o.optString("texts").split("\n").filter { it.isNotBlank() },
            nodes = nodesFrom(o.optJSONArray("nodes")),
            nodeCount = o.optInt("nodeCount"),
            screen = o.optString("screen"),
            decision = o.optJSONArray("decision")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
        )
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Append one line to the trail of the last read, and to the top history
     * entry, without touching the stored tree. For what is only known after
     * the parse: which rules passed or failed, and what was done about it.
     * Idempotent — the same line is never added twice.
     */
    fun note(ctx: Context, pkg: String, line: String) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load(ctx, pkg)?.let { o ->
            val arr = o.optJSONArray("decision") ?: JSONArray()
            if ((0 until arr.length()).none { arr.optString(it) == line }) {
                arr.put(line)
                o.put("decision", arr)
                prefs.edit().putString(key(pkg), o.toString()).apply()
            }
        }
        val k = HIST_PREFIX + pkg
        val raw = prefs.getString(k, null) ?: return
        val hist = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return
        }
        val top = hist.optJSONObject(0) ?: return
        val arr = top.optJSONArray("decision") ?: JSONArray()
        if ((0 until arr.length()).any { arr.optString(it) == line }) return
        arr.put(line)
        top.put("decision", arr)
        prefs.edit().putString(k, hist.toString()).apply()
    }

    /** The last [MAX_HISTORY] reads of [pkg], newest first. */
    fun history(ctx: Context, pkg: String): List<Entry> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(HIST_PREFIX + pkg, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until minOf(arr.length(), MAX_HISTORY)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                at = o.optLong("at"),
                outcome = o.optString("outcome"),
                screen = o.optString("screen"),
                nodeCount = o.optInt("nodeCount"),
                texts = o.optJSONArray("texts")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                } ?: emptyList(),
                decision = o.optJSONArray("decision")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                } ?: emptyList(),
                nodes = nodesFrom(o.optJSONArray("nodes")),
            )
        }
    }

    /**
     * Remember one read with its full tree, texts and parser trail. Ten full
     * captures fit comfortably and let an offer be inspected after it is gone.
     */
    private fun pushHistory(
        ctx: Context,
        pkg: String,
        outcome: String,
        screen: String,
        texts: List<String>,
        decision: List<String>,
        nodeCount: Int,
        nodes: List<Node>,
    ) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val k = HIST_PREFIX + pkg
        val prev = try {
            JSONArray(prefs.getString(k, null) ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        fun sameDecision(o: JSONObject): Boolean {
            val a = o.optJSONArray("decision") ?: return decision.isEmpty()
            if (a.length() != decision.size) return false
            for (i in 0 until a.length()) if (a.optString(i) != decision[i]) return false
            return true
        }
        fun sameTexts(o: JSONObject): Boolean {
            val a = o.optJSONArray("texts") ?: return texts.isEmpty()
            if (a.length() != minOf(texts.size, HIST_TEXTS)) return false
            for (i in 0 until a.length()) if (a.optString(i) != texts[i]) return false
            return true
        }
        // An identical read is already at the top of the list. Skipping it
        // avoids rewriting the whole history on every animation frame.
        val last = if (prev.length() > 0) prev.optJSONObject(0) else null
        if (last != null &&
            last.optString("outcome") == outcome &&
            last.optString("screen") == screen &&
            last.optInt("nodeCount") == nodeCount &&
            sameTexts(last) && sameDecision(last)
        ) return
        val entry = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("outcome", outcome)
            .put("screen", screen)
            .put("nodeCount", nodeCount)
            .put("texts", JSONArray(texts.take(HIST_TEXTS)))
            .put("decision", JSONArray(decision))
            .put("nodes", nodesJson(nodes))
        val out = JSONArray()
        out.put(entry)
        var kept = 0
        for (i in 0 until prev.length()) {
            if (kept >= MAX_HISTORY - 1) break
            val o = prev.optJSONObject(i) ?: continue
            // The same read repeated is noise, not history.
            if (o.optString("outcome") == outcome &&
                o.optString("screen") == screen &&
                o.optInt("nodeCount") == nodeCount &&
                sameTexts(o) && sameDecision(o)
            ) continue
            out.put(o)
            kept++
        }
        prefs.edit().putString(k, out.toString()).apply()
    }
}
