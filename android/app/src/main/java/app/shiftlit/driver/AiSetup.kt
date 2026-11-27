package app.shiftlit.driver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional AI-assisted setup: the driver brings their own API key, pastes an
 * email, and the provider they chose turns it into rule values.
 *
 * It has a second job: when the assistant misses an offer card, the AI can
 * read the same diagnostics capture a human maintainer would get, explain the
 * miss, and point at the nodes that hold the payout, distance, duration and
 * accept/decline controls. That mapping is saved as an ordinary taught hint —
 * the identical mechanism the point-at-the-card flow uses — so it is tested
 * against the capture before anything is written.
 *
 * Nothing here is required by the assistant and nothing is proxied: the request
 * goes from this phone straight to the provider, with the key the driver
 * entered. There is no Shiftlit server to send anything to.
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

    /** Bounds the prompt cost when someone pastes a long diagnostics dump. */
    private const val MAX_EMAIL_CHARS = 8000

    /** One diagnostics capture is bounded instead of trimmed by the caller. */
    private const val MAX_SCREEN_CHARS = 20_000
    private const val MAX_SCREEN_NODES = 400
    private const val MAX_NODE_TEXT = 240

    /** Rule values a provider proposed from an email. Every field is optional. */
    data class Proposal(
        val minPerMileCents: Int? = null,
        val minPerHourCents: Int? = null,
        val minPayoutCents: Int? = null,
        val maxDistance: Double? = null,
        val unit: String? = null,
        val currencyCode: String? = null,
        val note: String? = null,
    ) {
        val hasRules: Boolean
            get() = minPerMileCents != null || minPerHourCents != null ||
                minPayoutCents != null || maxDistance != null

        /**
         * The proposed distance in km. The model is asked to echo the driver's
         * unit, so a reply that names miles is converted rather than trusted to
         * be in the driver's unit.
         */
        fun maxDistanceKm(driverUnit: String): Double? {
            val v = maxDistance ?: return null
            val u = (unit ?: driverUnit).lowercase()
            return if (u.startsWith("k")) v else v * 1.609344
        }
    }

    sealed class Result {
        data class Ok(val proposal: Proposal) : Result()
        data class Err(val message: String) : Result()
    }

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

    private const val SYSTEM = """You turn a driver's email into rules for Shiftlit, an offer-scoring app.
Answer with exactly one JSON object and nothing else - no prose, no code fence:

{"min_per_mile": number|null, "min_per_hour": number|null,
 "min_payout": number|null, "max_distance": number|null,
 "unit": "mi"|"km"|null, "currency": "ISO 4217 code"|null,
 "note": "one or two sentences"}

Rules:
- Amounts are in the driver's own currency units, never cents.
- max_distance uses the distance unit given below; echo that unit in "unit".
- Only use numbers the email supports. Use null when it does not say.
- Echo the driver's currency unless the email clearly names another one.
- The note explains the proposal and flags anything rules cannot express
  (zones, hours, per-order bonuses)."""

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
- The note is shown to the driver: say what stopped the parse and why you
  picked these nodes, in plain words. No jargon."""

    /** The prompt sent with the pasted email; kept pure so it can be tested. */
    fun userPrompt(email: String, currency: String, unit: String, current: String): String =
        "Driver's currency: " + currency + "\n" +
            "Distance unit: " + unit + "\n" +
            "Current rules: " + current + "\n" +
            "Email:\n\"\"\"\n" +
            email.take(MAX_EMAIL_CHARS) +
            "\n\"\"\"\n"

    /**
     * Reads the rule values out of a model reply. Models wrap JSON in prose or
     * fences often enough that strict parsing would reject a usable answer, so
     * this pulls the named fields and ignores everything around them.
     */
    fun parseProposal(reply: String): Proposal? {
        if (!reply.contains('{')) return null
        val p = Proposal(
            minPerMileCents = cents(reply, "min_per_mile")?.coerceIn(1, 1_000_000),
            minPerHourCents = cents(reply, "min_per_hour")?.coerceIn(1, 10_000_000),
            minPayoutCents = cents(reply, "min_payout")?.coerceIn(0, 10_000_000),
            maxDistance = number(reply, "max_distance")?.takeIf { it > 0 }
                ?.coerceIn(0.5, 1000.0),
            unit = string(reply, "unit")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            currencyCode = string(reply, "currency")?.trim()?.uppercase()
                ?.takeIf { it.matches(Regex("[A-Z]{3}")) },
            note = string(reply, "note")?.trim()?.takeIf { it.isNotEmpty() },
        )
        return if (p.hasRules || p.note != null) p else null
    }

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
    ): FixResult {
        val prompt = screenPrompt(
            appName, pkg, outcome, screen, nodes, decision, currency, unit,
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

    /**
     * Runs one request. Call from a background thread; the callback runs on the
     * calling thread. Never logs the key, and never puts it in the URL.
     */
    fun ask(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        email: String,
        currency: String,
        unit: String,
        current: String,
    ): Result = when (val r = send(
        provider, apiKey, model, baseUrl, SYSTEM,
        userPrompt(email, currency, unit, current), 600,
    )) {
        is Reply.Err -> Result.Err(r.message)
        // A model that ignored the JSON request still said something useful;
        // show it as a note instead of an error.
        is Reply.Ok ->
            Result.Ok(parseProposal(r.content) ?: Proposal(note = r.content.take(600)))
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

    private fun number(reply: String, key: String): Double? {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?").find(reply)
            ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }

    private fun cents(reply: String, key: String): Int? =
        number(reply, key)?.takeIf { it > 0 }?.let { Math.round(it * 100).toInt() }

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
