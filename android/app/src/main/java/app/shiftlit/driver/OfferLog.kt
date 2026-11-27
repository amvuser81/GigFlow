package app.shiftlit.driver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Rolling on-device log of scored offers — capped at 200 entries. */
object OfferLog {

    private const val KEY = "offer_log"
    private const val MAX = 200

    data class Entry(
        val at: Long,
        val pkg: String,
        val payoutCents: Int,
        val distanceKm: Double?,
        val durationMin: Double?,
        val perMileCents: Int?,
        val perHourCents: Int?,
        val verdict: String,
        val action: String, // "shown" | "auto_accept" | "auto_decline" | "manual_accept" | "manual_decline"
        val lat: Double? = null,
        val lng: Double? = null,
        val reservation: Boolean = false,
        val currency: String = "$",
        /** ISO code when it was known at capture. Rows written before this
         *  existed leave it null and the feed falls back to the setting. */
        val currencyCode: String? = null,
        /** The rules that failed, comma-joined — null when every rule passed.
         *  An auto-decline has to be explainable from the log alone, so the
         *  reason travels with the row instead of only living in Diagnostics. */
        val rules: String? = null,
        /** Order identity (see [DetectedOffer.fingerprint]). The row written
         *  when the card is scored and the action recorded a moment later are
         *  the same order, so they merge into one entry instead of two. */
        val key: String? = null,
    )

    /** How long after the first capture an action still belongs to that row. */
    private const val MERGE_WINDOW_MS = 120_000L

    private fun load(prefs: android.content.SharedPreferences): JSONArray = try {
        JSONArray(prefs.getString(KEY, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray() // corrupt log starts over rather than crashing the reader
    }

    fun add(context: Context, e: Entry) {
        val prefs = context.getSharedPreferences("shiftlit_log", Context.MODE_PRIVATE)
        val arr = load(prefs)

        // One row per order. The first read logs "shown"; the accept/decline
        // that follows merges into it, and a rescan of the still-visible card
        // cannot write a second row or downgrade the recorded action.
        if (e.key != null) {
            val head = if (arr.length() > 0) arr.optJSONObject(0) else null
            if (head != null && head.optString("key") == e.key &&
                e.at - head.optLong("at", 0L) < MERGE_WINDOW_MS
            ) {
                merge(head, e)
                prefs.edit().putString(KEY, arr.toString()).apply()
                return
            }
        }

        val o = JSONObject()
            .put("at", e.at)
            .put("pkg", e.pkg)
            .put("payout", e.payoutCents)
            .put("verdict", e.verdict)
            .put("action", e.action)
        e.distanceKm?.let { o.put("km", it) }
        e.durationMin?.let { o.put("min", it) }
        e.perMileCents?.let { o.put("perMile", it) }
        e.perHourCents?.let { o.put("perHour", it) }
        e.lat?.let { o.put("lat", it) }
        e.lng?.let { o.put("lng", it) }
        if (e.reservation) o.put("reservation", true)
        o.put("currency", e.currency)
        e.currencyCode?.let { o.put("code", it) }
        e.rules?.let { o.put("rules", it) }
        e.key?.let { o.put("key", it) }
        val next = JSONArray()
        next.put(o)
        for (i in 0 until minOf(arr.length(), MAX - 1)) next.put(arr.get(i))
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    fun all(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences("shiftlit_log", Context.MODE_PRIVATE)
        val arr = load(prefs)
        return collapse((0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                at = o.getLong("at"),
                pkg = o.getString("pkg"),
                payoutCents = o.getInt("payout"),
                distanceKm = if (o.has("km")) o.getDouble("km") else null,
                durationMin = if (o.has("min")) o.getDouble("min") else null,
                perMileCents = if (o.has("perMile")) o.getInt("perMile") else null,
                perHourCents = if (o.has("perHour")) o.getInt("perHour") else null,
                verdict = o.getString("verdict"),
                action = o.getString("action"),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lng = if (o.has("lng")) o.getDouble("lng") else null,
                reservation = o.optBoolean("reservation"),
                currency = o.optString("currency", "$"),
                currencyCode = o.optString("code").ifBlank { null },
                rules = o.optString("rules").ifBlank { null },
                key = o.optString("key").ifBlank { null },
            )
        })
    }

    /**
     * Fold rows that describe the same order into one, for entries already in
     * the store.
     *
     * Before the stable identity existed, the card's ticking countdown made a
     * single Uber offer log several times, and the action was written as its
     * own row. Those rows are already on the phone, so the feed collapses them
     * on read: same app, money, trip and reservation within
     * [MERGE_WINDOW_MS], with a recorded action winning over a plain "shown".
     * [entries] is newest first, as stored.
     */
    fun collapse(entries: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>(entries.size)
        for (e in entries) {
            val prev = out.lastOrNull()
            if (prev != null && sameOrder(prev, e)) {
                out[out.size - 1] = combine(prev, e)
            } else {
                out.add(e)
            }
        }
        return out
    }

    /** Same fields the live fingerprint uses, so old and new rows agree. */
    private fun identity(e: Entry) = "${e.pkg}|${e.payoutCents}|" +
        "${e.distanceKm?.let { "%.1f".format(it) } ?: "-"}|" +
        "${e.durationMin?.toInt() ?: "-"}|${e.reservation}"

    private fun sameOrder(a: Entry, b: Entry) =
        identity(a) == identity(b) && Math.abs(a.at - b.at) <= MERGE_WINDOW_MS

    /** Keep the newer row, fill what it lacks from the older one. */
    private fun combine(newer: Entry, older: Entry): Entry = newer.copy(
        action = when {
            newer.action != "shown" -> newer.action
            older.action != "shown" -> older.action
            else -> newer.action
        },
        distanceKm = newer.distanceKm ?: older.distanceKm,
        durationMin = newer.durationMin ?: older.durationMin,
        perMileCents = newer.perMileCents ?: older.perMileCents,
        perHourCents = newer.perHourCents ?: older.perHourCents,
        rules = newer.rules ?: older.rules,
        lat = newer.lat ?: older.lat,
        lng = newer.lng ?: older.lng,
        key = newer.key ?: older.key,
    )

    /**
     * Fold a later capture of the same order into [head]: fill in metrics the
     * first frame couldn't read yet, keep the action already recorded, and let
     * the newest non-null rules win.
     */
    private fun merge(head: JSONObject, e: Entry) {
        val prev = head.optString("action")
        head.put("action", if (prev.isNotEmpty() && prev != "shown") prev else e.action)
        if (!head.has("km")) e.distanceKm?.let { head.put("km", it) }
        if (!head.has("min")) e.durationMin?.let { head.put("min", it) }
        if (!head.has("perMile")) e.perMileCents?.let { head.put("perMile", it) }
        if (!head.has("perHour")) e.perHourCents?.let { head.put("perHour", it) }
        if (!head.has("lat")) e.lat?.let { head.put("lat", it) }
        if (!head.has("lng")) e.lng?.let { head.put("lng", it) }
        e.rules?.let { head.put("rules", it) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences("shiftlit_log", Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
