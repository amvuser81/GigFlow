package app.shiftlit.driver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional AI-assisted setup: the driver brings their own API key. When the
 * assistant misses an offer card, the provider reads the same diagnostics
 * capture a human maintainer would get, explains the miss, and points at the
 * nodes that hold the payout, distance, duration and accept/decline controls.
 *
 * That mapping is saved as an ordinary taught hint — the identical mechanism
 * the point-at-the-card flow uses — so it is tested against the capture before
 * anything is written. Nothing here is required by the assistant and nothing
 * is proxied: the request goes from this phone straight to the provider, with
 * the key the driver entered. There is no Shiftlit server to send anything to.
 */
enum class AiProvider(
    val label: String,
    val defaultModel: String,
    val keyUrl: String,
    val style: Style,
) {
    OPENAI(
        "OpenAI", "gpt-4o-mini",
        "https://platform.openai.com/api-keys", Style.OPENAI,
    ),
    ANTHROPIC(
        "Anthropic", "claude-3-5-haiku-latest",
        "https://console.anthropic.com/settings/keys", Style.ANTHROPIC,
    ),
    GEMINI(
        "Google Gemini", "gemini-2.0-flash",
        "https://aistudio.google.com/app/apikey", Style.GEMINI,
    ),
    OPENROUTER(
        "OpenRouter", "openai/gpt-4o-mini",
        "https://openrouter.ai/keys", Style.OPENAI,
    ),
    CUSTOM("Custom endpoint", "", "", Style.OPENAI);

    enum class Style { OPENAI, ANTHROPIC, GEMINI }

    companion object {
        fun from(name: String?): AiProvider =
            values().firstOrNull { it.name == name } ?: OPENAI
    }
}

object AiSetup {

    /** One diagnostics capture is bounded instead of trimmed by the caller. */
    private const val MAX_SCREEN_CHARS = 20_000
    private const val MAX_SCREEN_NODES = 400
    private const val MAX_NODE_TEXT = 240

    /** Earlier reads are context, not a second tree: keep them small. */
    private const val MAX_HISTORY_READS = 6
    private const val MAX_HISTORY_CHARS = 2_400

    /** Node indices the provider chose on one captured screen. */
    data class ScreenFix(
        val payout: Int? = null,
        val distance: Int? = null,
        val duration: Int? = null,
        val accept: Int? = null,
        val decline: Int? = null,
        val note: String? = null,
    ) {
        val hasMapping: Boolean
            get() = payout != null || distance != null || duration != null ||
                accept != null || decline != null

        /** Distance and duration alone can't identify a card; an amount or a
         *  button has to be part of the mapping, same as pointing at one. */
        val canSave: Boolean
            get() = payout != null || accept != null || decline != null
    }

    sealed class FixResult {
        data class Ok(val fix: ScreenFix) : FixResult()
        data class Err(val message: String) : FixResult()
    }

    private const val FIX_SYSTEM = """You debug Shiftlit, an Android app that reads gig-driver offer cards from the accessibility tree. A screen did not score as an offer, or scored wrongly. You are given the captured nodes in tree order, each with a stable index, and the parser's decision trail.

Answer with exactly one JSON object and nothing else - no prose, no code fence:

{"payout": index|null, "distance": index|null, "duration": index|null,
 "accept": index|null, "decline": index|null,
 "note": "two or three sentences"}

Rules:
- Use only indices printed in the node list. Never invent an index.
- payout is the money the driver earns for this trip - not a tip, a fee, a
  bonus, a customer total, or a wallet balance.
- distance is the trip length, duration is the trip time.
- accept is the control that takes the request (a button, a slider, or the
  whole card); decline is the control that refuses it, including an unlabeled
  "x". Prefer a node marked clickable for both.
- Use null for a role that is not on the screen.
- Unlabeled clickable nodes have no text; their at= field is the screen
  rectangle, which is how you tell a close button from a nav item.
- Earlier reads are background only: their indices belong to their own
  captures, so never take an index from an earlier read. Use them to say
  whether the miss repeats or the layout changed between reads.
- The note is shown to the driver: say what stopped the parse and why you
  picked these nodes, in plain words. No jargon."""

    /** A prompt and how many nodes it actually lists, so reply indices can be
     *  range-checked against what the model was shown. */
    data class ScreenPrompt(val text: String, val listed: Int)

    /**
     * One capture, as the maintainer's email report would show it: app, parser
     * decision trail, then one JSON object per node. Indices are the snapshot's
     * own, so a reply maps straight back to [Diagnostics.Snap.nodes].
     */
    fun screenPrompt(
        appName: String,
        pkg: String,
        outcome: String,
        screen: String,
        nodes: List<Diagnostics.Node>,
        decision: List<String>,
        currency: String,
        unit: String,
        history: List<Diagnostics.Entry> = emptyList(),
    ): ScreenPrompt {
        val sb = StringBuilder()
        sb.append("Driver app: ").append(appName).append(" (").append(pkg).append(")\n")
        sb.append("Outcome: ").append(outcome).append('\n')
        if (screen.isNotBlank()) sb.append("Screen key: ").append(screen).append('\n')
        sb.append("Currency: ").append(currency).append('\n')
        sb.append("Distance unit shown to the driver: ").append(unit).append("\n\n")
        if (decision.isNotEmpty()) {
            sb.append("Parser decision trail:\n")
            decision.take(20).forEachIndexed { i, d ->
                sb.append("  ").append(i + 1).append(". ").append(d).append('\n')
            }
            sb.append('\n')
        }
        // The sequence is what turns "it missed once" into "it misses every
        // time this card appears": same screen, same decision, same texts.
        val hist = StringBuilder()
        var budget = MAX_HISTORY_CHARS
        for (e in history.take(MAX_HISTORY_READS)) {
            val block = StringBuilder()
            block.append("  - ").append(e.outcome)
                .append(" · ").append(e.nodeCount).append(" nodes")
            if (e.screen.isNotBlank() && e.screen != screen) {
                block.append(" · screen ").append(e.screen.take(60))
            }
            block.append('\n')
            if (e.decision.isNotEmpty()) {
                block.append("    decision: ")
                    .append(e.decision.take(4).joinToString(" / "))
                    .append('\n')
            }
            val head = e.texts.filter { it.isNotBlank() }.take(3)
            if (head.isNotEmpty()) {
                block.append("    texts: ")
                    .append(head.joinToString(" | ") { quoted(it.take(80)) })
                    .append('\n')
            }
            if (block.length > budget) break
            budget -= block.length
            hist.append(block)
        }
        if (hist.isNotEmpty()) {
            sb.append("Earlier reads of this app, newest first (their indices are not ")
                .append("this capture's — use them only to judge whether the miss repeats):\n")
                .append(hist)
                .append('\n')
        }
        sb.append("Nodes (index | \"text\" | id | clickable; index is the snapshot position):\n")
        var listed = 0
        for ((i, n) in nodes.withIndex()) {
            if (listed >= MAX_SCREEN_NODES) break
            val line = StringBuilder()
                .append(i).append(" | ")
                .append(quoted(n.text.take(MAX_NODE_TEXT)))
                .append(" | id=").append(n.viewId.orEmpty().ifBlank { "-" })
                .append(" | clickable=").append(n.clickable)
            // Only an unlabeled control needs its rectangle: that is how a
            // model can tell a close button from a nav icon.
            if (n.text.isBlank() && n.bounds.size == 4) {
                line.append(" | at=").append(n.bounds.joinToString(","))
            }
            line.append('\n')
            if (sb.length + line.length > MAX_SCREEN_CHARS) break
            sb.append(line)
            listed++
        }
        if (listed < nodes.size) {
            sb.append("(").append(nodes.size - listed)
                .append(" more nodes not shown - use only the indices above)\n")
        }
        return ScreenPrompt(sb.toString(), listed)
    }

    /** One prompt line's text, quoted and squeezed onto a single line. */
    private fun quoted(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n', '\r', '\t' -> b.append(' ')
                else -> b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    /**
     * Reads the node mapping out of a reply. Indices outside the captured list
     * are dropped rather than trusted; roles are mapped later by [sanitize],
     * which knows what each node's text must look like.
     */
    fun parseScreenFix(reply: String, listed: Int): ScreenFix? {
        if (!reply.contains('{')) return null
        fun idx(key: String): Int? {
            val m = Regex("\"" + key + "\"\\s*:\\s*\"?(\\d+)\"?").find(reply) ?: return null
            return m.groupValues[1].toIntOrNull()?.takeIf { it in 0 until listed }
        }
        val fix = ScreenFix(
            payout = idx("payout"),
            distance = idx("distance"),
            duration = idx("duration"),
            accept = idx("accept"),
            decline = idx("decline"),
            note = string(reply, "note")?.trim()?.takeIf { it.isNotEmpty() },
        )
        return if (fix.hasMapping || fix.note != null) fix else null
    }

    /**
     * Drops picks that cannot be what the role needs: a payout has to carry a
     * money figure, a distance or duration has to carry a number. Buttons are
     * kept as picked — an unlabeled "x" has no text at all — and the real
     * [OfferParser] run on the capture is the final gate before saving.
     */
    fun sanitize(
        fix: ScreenFix,
        nodes: List<Diagnostics.Node>,
        extra: Regex? = null,
        symbol: String = "$",
    ): ScreenFix {
        fun textAt(i: Int?): String? = i?.let { nodes.getOrNull(it)?.text }
        val payout = fix.payout?.takeIf { i ->
            val t = textAt(i) ?: return@takeIf false
            OfferParser.moneyOf(t, extra, symbol) != null
        }
        fun number(i: Int?): Int? =
            i?.takeIf { textAt(it)?.any(Char::isDigit) == true }
        return fix.copy(
            payout = payout,
            distance = number(fix.distance),
            duration = number(fix.duration),
            accept = fix.accept?.takeIf { it in nodes.indices },
            decline = fix.decline?.takeIf { it in nodes.indices },
        )
    }

    /**
     * The taught hint a settled [fix] describes on [nodes], or null when there
     * is no amount or button to save. The sheet saves exactly this, so the
     * conversion is testable without a Context.
     */
    fun hintFor(
        fix: ScreenFix,
        pkg: String,
        screen: String,
        nodes: List<OfferParser.FlatNode>,
    ): ParseHints.Hint? {
        if (!fix.canSave) return null
        return ParseHints.Hint(
            pkg = pkg,
            screen = screen,
            payout = fix.payout?.let { ParseHints.anchor(nodes, it) },
            distance = fix.distance?.let { ParseHints.anchor(nodes, it) },
            duration = fix.duration?.let { ParseHints.anchor(nodes, it) },
            accept = fix.accept?.let { ParseHints.anchor(nodes, it) },
            decline = fix.decline?.let { ParseHints.anchor(nodes, it) },
        )
    }

    /**
     * Runs one screen-fix request. Call from a background thread; the callback
     * runs on the calling thread. Never logs the key, never puts it in the URL.
     */
    fun askScreenFix(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        appName: String,
        pkg: String,
        outcome: String,
        screen: String,
        nodes: List<Diagnostics.Node>,
        decision: List<String>,
        currency: String,
        unit: String,
        history: List<Diagnostics.Entry> = emptyList(),
    ): FixResult {
        val prompt = screenPrompt(
            appName, pkg, outcome, screen, nodes, decision, currency, unit, history,
        )
        return when (val r = send(
            provider, apiKey, model, baseUrl, FIX_SYSTEM, prompt.text, 700,
        )) {
            is Reply.Err -> FixResult.Err(r.message)
            is Reply.Ok -> {
                val fix = parseScreenFix(r.content, prompt.listed)
                if (fix == null) {
                    FixResult.Err(
                        provider.label + " answered without a node mapping. " +
                            r.content.replace('\n', ' ').take(180),
                    )
                } else {
                    FixResult.Ok(fix)
                }
            }
        }
    }

    private sealed class Reply {
        data class Ok(val content: String) : Reply()
        data class Err(val message: String) : Reply()
    }

    /** One authenticated POST, in whichever shape the chosen provider expects. */
    private fun send(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        system: String,
        prompt: String,
        maxTokens: Int,
    ): Reply {
        if (apiKey.isBlank()) return Reply.Err("Add your API key first.")
        val mdl = model.ifBlank { provider.defaultModel }
        if (mdl.isBlank()) return Reply.Err("Set a model name for this endpoint.")
        val base = when (provider) {
            AiProvider.OPENAI -> "https://api.openai.com/v1"
            AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
            AiProvider.CUSTOM -> baseUrl.trim().trimEnd('/')
        }
        if (provider == AiProvider.CUSTOM && base.isBlank()) {
            return Reply.Err("Add the endpoint URL first.")
        }
        // A plain-http endpoint would put the user's key on the wire in clear
        // text; there is no reason to allow it, including for localhost demos.
        if (!base.startsWith("https://")) {
            return Reply.Err(
                "The endpoint must start with https:// — plain http would send your key in the clear.",
            )
        }

        val url = when (provider.style) {
            AiProvider.Style.GEMINI -> base + "/models/" + mdl + ":generateContent"
            AiProvider.Style.ANTHROPIC -> base + "/messages"
            AiProvider.Style.OPENAI ->
                if (base.endsWith("/chat/completions")) base else base + "/chat/completions"
        }

        val headers = ArrayList<Pair<String, String>>(3)
        val body = when (provider.style) {
            AiProvider.Style.OPENAI -> {
                headers.add("Authorization" to "Bearer " + apiKey)
                JSONObject()
                    .put("model", mdl)
                    .put("temperature", 0)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
            }
            AiProvider.Style.ANTHROPIC -> {
                headers.add("x-api-key" to apiKey)
                headers.add("anthropic-version" to "2023-06-01")
                JSONObject()
                    .put("model", mdl)
                    .put("max_tokens", maxTokens)
                    .put("temperature", 0)
                    .put("system", system)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
            }
            AiProvider.Style.GEMINI -> {
                headers.add("x-goog-api-key" to apiKey)
                JSONObject()
                    .put("system_instruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", system)),
                    ))
                    .put("contents", JSONArray().put(JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", prompt)),
                    )))
                    .toString()
            }
        }

        var open: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            open = conn
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Reply.Err(httpError(provider, code, raw))
            } else {
                val content = contentOf(provider.style, raw)
                if (content.isNullOrBlank()) {
                    Reply.Err(provider.label + " sent an empty answer.")
                } else {
                    Reply.Ok(content)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Reply.Err(provider.label + " took too long to answer.")
        } catch (e: java.io.IOException) {
            Reply.Err("Couldn't reach " + provider.label + ": " + (e.message ?: "network error"))
        } catch (e: Exception) {
            Reply.Err("Unexpected error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            open?.disconnect()
        }
    }

    private fun string(reply: String, key: String): String? {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(reply)
            ?: return null
        return m.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun httpError(provider: AiProvider, code: Int, raw: String): String {
        val detail = providerMessage(raw).take(160)
        val base = when (code) {
            401, 403 -> provider.label + " rejected the key (HTTP " + code + ")."
            404 -> provider.label + " doesn't know that model (HTTP 404)."
            429 -> provider.label + " says you're over the rate or credit limit (HTTP 429)."
            in 500..599 -> provider.label + " had a server error (HTTP " + code + ")."
            else -> provider.label + " returned HTTP " + code + "."
        }
        return if (detail.isBlank()) base else base + " " + detail
    }

    private fun providerMessage(raw: String): String = runCatching {
        val o = JSONObject(raw)
        val e = o.optJSONObject("error")
        val msg = when {
            e != null -> e.optString("message")
            o.has("message") -> o.optString("message")
            else -> ""
        }
        msg.replace('\n', ' ').replace('\r', ' ')
    }.getOrDefault("")

    private fun contentOf(style: AiProvider.Style, raw: String): String? = runCatching {
        val o = JSONObject(raw)
        when (style) {
            AiProvider.Style.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            AiProvider.Style.ANTHROPIC -> o.optJSONArray("content")?.optJSONObject(0)
                ?.optString("text")
            AiProvider.Style.GEMINI -> {
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                if (parts == null) "" else (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
            }
        }
    }.getOrNull()
}
