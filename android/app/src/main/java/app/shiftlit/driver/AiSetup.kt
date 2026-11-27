package app.shiftlit.driver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional AI-assisted setup: the driver brings their own API key, pastes an
 * email, and the provider they chose turns it into rule values.
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
    ): Result {
        if (apiKey.isBlank()) return Result.Err("Add your API key first.")
        val mdl = model.ifBlank { provider.defaultModel }
        if (mdl.isBlank()) return Result.Err("Set a model name for this endpoint.")
        val base = when (provider) {
            AiProvider.OPENAI -> "https://api.openai.com/v1"
            AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
            AiProvider.CUSTOM -> baseUrl.trim().trimEnd('/')
        }
        if (provider == AiProvider.CUSTOM && base.isBlank()) {
            return Result.Err("Add the endpoint URL first.")
        }
        // A plain-http endpoint would put the user's key on the wire in clear
        // text; there is no reason to allow it, including for localhost demos.
        if (!base.startsWith("https://")) {
            return Result.Err("The endpoint must start with https:// — plain http would send your key in the clear.")
        }

        val prompt = userPrompt(email, currency, unit, current)
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
                        .put(JSONObject().put("role", "system").put("content", SYSTEM))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
            }
            AiProvider.Style.ANTHROPIC -> {
                headers.add("x-api-key" to apiKey)
                headers.add("anthropic-version" to "2023-06-01")
                JSONObject()
                    .put("model", mdl)
                    .put("max_tokens", 600)
                    .put("temperature", 0)
                    .put("system", SYSTEM)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
            }
            AiProvider.Style.GEMINI -> {
                headers.add("x-goog-api-key" to apiKey)
                JSONObject()
                    .put("system_instruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", SYSTEM)),
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
                Result.Err(httpError(provider, code, raw))
            } else {
                val content = contentOf(provider.style, raw)
                if (content.isNullOrBlank()) {
                    Result.Err(provider.label + " sent an empty answer.")
                } else {
                    // A model that ignored the JSON request still said something
                    // useful; show it as a note instead of an error.
                    Result.Ok(parseProposal(content) ?: Proposal(note = content.take(600)))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.Err(provider.label + " took too long to answer.")
        } catch (e: java.io.IOException) {
            Result.Err("Couldn't reach " + provider.label + ": " + (e.message ?: "network error"))
        } catch (e: Exception) {
            Result.Err("Unexpected error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            open?.disconnect()
        }
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
