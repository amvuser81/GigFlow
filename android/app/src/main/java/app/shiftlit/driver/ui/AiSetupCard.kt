package app.shiftlit.driver.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.shiftlit.driver.AiProvider
import app.shiftlit.driver.AiSetup
import app.shiftlit.driver.Bounds
import app.shiftlit.driver.Currencies
import app.shiftlit.driver.Diagnostics
import app.shiftlit.driver.OfferParser
import app.shiftlit.driver.ParseHints
import app.shiftlit.driver.SettingsRepository
import app.shiftlit.driver.ui.Ios.Palette
import app.shiftlit.driver.ui.Ios.dp

/** One captured screen offered to the AI, with the app it came from. */
data class ScreenCapture(val name: String, val pkg: String, val snap: Diagnostics.Snap)

/**
 * "AI setup" — optional, bring your own key.
 *
 * The assistant works without any of this. What it adds is two second ways to
 * set it up with the driver's own key: paste an email for rule values, or hand
 * it a screen the parser missed so it can point at the payout, distance,
 * duration and buttons. Neither writes anything until the driver taps Apply.
 */
fun aiSetupCard(
    ctx: Context,
    p: Palette,
    settings: SettingsRepository,
    onChanged: () -> Unit,
    lastDiagnostics: (() -> String?)? = null,
    captures: (() -> List<ScreenCapture>)? = null,
): View {
    val card = Sections.card(ctx, p)
    val provider = settings.aiProviderOrDefault()

    card.addView(Sections.row(ctx, p,
        title = "AI provider",
        subtitle = if (provider == AiProvider.CUSTOM)
            settings.aiBaseUrl.ifBlank { "No endpoint yet" }
        else provider.defaultModel,
        value = provider.label,
        iconGlyph = "bolt", iconTint = p.tint,
        chevron = true,
    ) { providerSheet(ctx, p, settings, onChanged) })
    card.addView(Sections.separator(ctx, p))

    if (provider == AiProvider.CUSTOM) {
        card.addView(Sections.row(ctx, p,
            title = "Endpoint",
            subtitle = "HTTPS only — your key goes straight there",
            value = settings.aiBaseUrl.ifBlank { "Not set" },
            iconGlyph = "link", iconTint = p.gray,
            chevron = true,
        ) { endpointSheet(ctx, p, settings, onChanged) })
        card.addView(Sections.separator(ctx, p))
    }

    card.addView(Sections.row(ctx, p,
        title = "API key",
        subtitle = if (settings.aiKey.isBlank())
            "You need a key from the provider you picked"
        else "Saved on this phone, sent only to this provider",
        value = if (settings.aiKey.isBlank()) "Not set" else "Saved",
        iconGlyph = "doc",
        iconTint = if (settings.aiKey.isBlank()) p.orange else p.green,
        chevron = true,
    ) { keySheet(ctx, p, settings, onChanged) })
    card.addView(Sections.separator(ctx, p))

    card.addView(Sections.row(ctx, p,
        title = "Model",
        subtitle = "Blank uses the provider's default",
        value = settings.aiModel.ifBlank { provider.defaultModel.ifBlank { "Set a model" } },
        iconGlyph = "slider", iconTint = p.gray,
        chevron = true,
    ) { modelSheet(ctx, p, settings, onChanged) })
    card.addView(Sections.separator(ctx, p))

    card.addView(Sections.row(ctx, p,
        title = "Set rules from an email",
        subtitle = if (settings.aiKey.isBlank())
            "Add your API key first"
        else "Paste an email; review the proposed thresholds, then apply",
        iconGlyph = "tag", iconTint = p.tint,
        chevron = true,
    ) {
        if (settings.aiKey.isBlank()) {
            Toast.makeText(ctx, "Add your API key first", Toast.LENGTH_SHORT).show()
            keySheet(ctx, p, settings, onChanged)
        } else {
            emailSheet(ctx, p, settings, onChanged, lastDiagnostics)
        }
    })
    card.addView(Sections.separator(ctx, p))

    card.addView(Sections.row(ctx, p,
        title = "Fix a missed screen",
        subtitle = if (settings.aiKey.isBlank())
            "Add your API key first"
        else if (captures == null)
            "Open Diagnostics on a screen the assistant missed"
        else "It reads one capture, explains the miss, and points at the payout, distance and buttons",
        iconGlyph = "grid", iconTint = p.tint,
        chevron = true,
    ) {
        if (settings.aiKey.isBlank()) {
            Toast.makeText(ctx, "Add your API key first", Toast.LENGTH_SHORT).show()
            keySheet(ctx, p, settings, onChanged)
        } else if (captures == null) {
            Toast.makeText(ctx, "Open Diagnostics on a missed screen instead", Toast.LENGTH_SHORT).show()
        } else {
            capturePicker(ctx, p, settings, onChanged, captures)
        }
    })

    card.addView(TextView(ctx).apply {
        text = "Optional. Your key stays in this app's private storage. Only the email you paste " +
            "or the one screen you pick goes to the provider you choose, and only when you tap " +
            "Ask. Shiftlit runs no server."
        textSize = Ios.T_CAPTION
        setTextColor(p.label3)
        setPadding(dp(ctx, 16f), dp(ctx, 6f), dp(ctx, 16f), dp(ctx, 10f))
    })
    return card
}

private fun sheetColumn(ctx: Context, p: Palette, title: String, subtitle: String): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20f), dp(ctx, 4f), dp(ctx, 20f), dp(ctx, 12f))
        addView(TextView(ctx).apply {
            text = title
            textSize = Ios.T_HEADLINE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.label)
        })
        addView(TextView(ctx).apply {
            text = subtitle
            textSize = Ios.T_FOOTNOTE
            setTextColor(p.label2)
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 12f))
        })
    }

private fun providerSheet(
    ctx: Context, p: Palette, settings: SettingsRepository, onChanged: () -> Unit,
) {
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "AI provider",
        "Bring your own key. Shiftlit calls the provider directly from this phone — " +
            "there is no middleman and no account with us.")
    AiProvider.values().forEach { pr ->
        val selected = settings.aiProviderOrDefault() == pr
        col.addView(Sections.row(ctx, p,
            title = pr.label,
            subtitle = if (pr == AiProvider.CUSTOM)
                "Any OpenAI-compatible chat endpoint"
            else pr.defaultModel.takeIf { it.isNotBlank() },
            value = if (selected) "Selected" else null,
            iconGlyph = if (selected) "checkmark" else "bolt",
            iconTint = if (selected) p.tint else p.gray,
        ) {
            settings.aiProvider = pr.name
            // Models differ per provider; a leftover name would 404.
            settings.aiModel = ""
            sheet.dismiss()
            onChanged()
        })
        col.addView(Sections.separator(ctx, p))
    }
    val chosen = settings.aiProviderOrDefault()
    if (chosen.keyUrl.isNotBlank()) {
        col.addView(Sections.row(ctx, p,
            title = "Get a key",
            subtitle = chosen.keyUrl,
            iconGlyph = "link", iconTint = p.tint,
            chevron = true,
        ) { openUrl(ctx, chosen.keyUrl) })
    }
    sheet.add(col).show()
}

private fun keySheet(
    ctx: Context, p: Palette, settings: SettingsRepository, onChanged: () -> Unit,
) {
    val provider = settings.aiProviderOrDefault()
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "API key",
        "Paste the key you created with " + provider.label + ". It is kept in this app's " +
            "private storage and sent only to that provider.")
    val field = EditText(ctx).apply {
        hint = if (settings.aiKey.isBlank()) "Your API key" else "Paste a new key to replace it"
        setHintTextColor(p.label3)
        setTextColor(p.label)
        textSize = Ios.T_BODY
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sheet.add(col)

    val save = iosButton(ctx, p, "Save key")
    sheet.add(LinearLayout(ctx).apply {
        setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
        addView(save)
    })
    if (settings.aiKey.isNotBlank()) {
        sheet.add(TextView(ctx).apply {
            text = "Clear saved key"
            textSize = Ios.T_FOOTNOTE
            setTextColor(p.red)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 12f), 0, dp(ctx, 4f))
            setOnClickListener {
                settings.aiKey = ""
                sheet.dismiss()
                onChanged()
            }
        })
    }
    save.setOnClickListener {
        val key = field.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(ctx, "Paste the key first", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        settings.aiKey = key
        Toast.makeText(ctx, "Key saved", Toast.LENGTH_SHORT).show()
        sheet.dismiss()
        onChanged()
    }
    sheet.show()
}

private fun modelSheet(
    ctx: Context, p: Palette, settings: SettingsRepository, onChanged: () -> Unit,
) {
    val provider = settings.aiProviderOrDefault()
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Model",
        "Leave it blank to use " + provider.label + "'s default" +
            (if (provider.defaultModel.isBlank()) "." else " (" + provider.defaultModel + ")."))
    val field = EditText(ctx).apply {
        hint = provider.defaultModel.ifBlank { "model name" }
        setHintTextColor(p.label3)
        setTextColor(p.label)
        textSize = Ios.T_BODY
        setText(settings.aiModel)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sheet.add(col)
    val save = iosButton(ctx, p, "Save model")
    sheet.add(LinearLayout(ctx).apply {
        setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
        addView(save)
    })
    save.setOnClickListener {
        settings.aiModel = field.text.toString().trim()
        sheet.dismiss()
        onChanged()
    }
    sheet.show()
}

private fun endpointSheet(
    ctx: Context, p: Palette, settings: SettingsRepository, onChanged: () -> Unit,
) {
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Endpoint",
        "The base URL of an OpenAI-compatible API. Shiftlit appends /chat/completions. " +
            "It must use https:// so your key is never sent in clear text.")
    val field = EditText(ctx).apply {
        hint = "https://host/v1"
        setHintTextColor(p.label3)
        setTextColor(p.label)
        textSize = Ios.T_BODY
        setText(settings.aiBaseUrl)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sheet.add(col)
    val save = iosButton(ctx, p, "Save endpoint")
    sheet.add(LinearLayout(ctx).apply {
        setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
        addView(save)
    })
    save.setOnClickListener {
        val url = field.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("https://")) {
            Toast.makeText(ctx, "Use an https:// URL", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        settings.aiBaseUrl = url
        sheet.dismiss()
        onChanged()
    }
    sheet.show()
}

private fun emailSheet(
    ctx: Context,
    p: Palette,
    settings: SettingsRepository,
    onChanged: () -> Unit,
    lastDiagnostics: (() -> String?)?,
) {
    val provider = settings.aiProviderOrDefault()
    val isKm = settings.distanceUnit == "KM"
    val unit = if (isKm) "km" else "mi"
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Set rules from an email",
        "Paste an email or your own notes — a welcome mail, a rate card, what you actually " +
            "want to earn. " + provider.label + " reads it with your key and proposes thresholds.")

    val field = EditText(ctx).apply {
        hint = "Paste the whole email here…"
        setHintTextColor(p.label3)
        setTextColor(p.label)
        textSize = Ios.T_BODY
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 6
        maxLines = 10
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    if (lastDiagnostics != null) {
        col.addView(TextView(ctx).apply {
            text = "Use the last diagnostics read"
            textSize = Ios.T_FOOTNOTE
            setTextColor(p.tint)
            setPadding(0, dp(ctx, 8f), 0, 0)
            setOnClickListener {
                val dump = lastDiagnostics()
                if (dump.isNullOrBlank()) {
                    Toast.makeText(ctx, "No diagnostics read yet", Toast.LENGTH_SHORT).show()
                } else {
                    field.setText(dump)
                    field.setSelection(field.text.length)
                }
            }
        })
    }

    val status = TextView(ctx).apply {
        textSize = Ios.T_FOOTNOTE
        setTextColor(p.label2)
        setPadding(0, dp(ctx, 10f), 0, 0)
    }
    col.addView(status)

    val ask = iosButton(ctx, p, "Ask " + provider.label)
    col.addView(ask, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(ctx, 12f) })

    val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(resultBox)
    sheet.add(col)

    var proposal: AiSetup.Proposal? = null

    fun renderResult() {
        resultBox.removeAllViews()
        val prop = proposal ?: return
        fun line(body: String, size: Float, color: Int, top: Float, bold: Boolean) {
            resultBox.addView(TextView(ctx).apply {
                this.text = body
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, top), 0, 0)
            })
        }
        line("Proposed rules", Ios.T_FOOTNOTE, p.label3, 14f, bold = true)
        prop.minPerMileCents?.let {
            resultBox.addView(ruleRow(ctx, p,
                "Min per " + unit, Ios.money(it, settings.currencySymbol, settings.currencyCode)))
        }
        prop.minPerHourCents?.let {
            resultBox.addView(ruleRow(ctx, p,
                "Min per hour", Ios.money(it, settings.currencySymbol, settings.currencyCode)))
        }
        prop.minPayoutCents?.let {
            resultBox.addView(ruleRow(ctx, p,
                "Min payout", Ios.money(it, settings.currencySymbol, settings.currencyCode)))
        }
        prop.maxDistanceKm(unit)?.let { km ->
            val shown = if (isKm) km else km / 1.609344
            resultBox.addView(ruleRow(ctx, p,
                "Max distance", "%.1f %s".format(shown, unit)))
        }
        prop.note?.let { line(it, Ios.T_FOOTNOTE, p.label2, 8f, false) }
        prop.currencyCode?.let { code ->
            if (Currencies.forCode(code) != null && code != settings.currencyCode) {
                line("Currency: " + code + " — it is applied with the rules.", Ios.T_FOOTNOTE,
                    p.label2, 6f, false)
            }
        }
        if (!prop.hasRules) {
            line("No rule values in that text. Try an email that names rates.", Ios.T_FOOTNOTE,
                p.orange, 8f, false)
            return
        }
        line("Check these before applying — the model can misread an email.",
            Ios.T_CAPTION, p.label3, 8f, false)
        val apply = iosButton(ctx, p, "Apply these rules", p.green)
        resultBox.addView(apply, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 10f) })
        apply.setOnClickListener {
            prop.minPerMileCents?.let { settings.minPerMileCents = it }
            prop.minPerHourCents?.let { settings.minPerHourCents = it }
            prop.minPayoutCents?.let { settings.minPayoutCents = it }
            prop.maxDistanceKm(unit)?.let { settings.maxDistanceKm = it }
            prop.currencyCode?.let { code ->
                if (Currencies.forCode(code) != null) settings.currencyCode = code
            }
            sheet.dismiss()
            Toast.makeText(ctx, "Rules applied", Toast.LENGTH_SHORT).show()
            onChanged()
        }
    }

    ask.setOnClickListener {
        val text = field.text.toString().trim()
        if (text.length < 20) {
            status.setTextColor(p.orange)
            status.text = "Paste a bit more — the AI needs the actual email text."
            return@setOnClickListener
        }
        val current = buildString {
            append("min per ").append(unit).append(' ')
            append("%.2f".format(if (isKm) settings.minPerMileCents / 1.609344 / 100.0
                                 else settings.minPerMileCents / 100.0))
            append(", min per hour ").append("%.2f".format(settings.minPerHourCents / 100.0))
            append(", min payout ").append("%.2f".format(settings.minPayoutCents / 100.0))
            append(", max distance ")
            append("%.1f".format(if (isKm) settings.maxDistanceKm
                                 else settings.maxDistanceKm / 1.609344))
            append(' ').append(unit)
        }
        val sent = text.take(8000).length
        proposal = null
        resultBox.removeAllViews()
        status.setTextColor(p.label2)
        status.text = "Sending " + sent + " characters to " + provider.label + ". Nothing else leaves the phone."
        ask.isEnabled = false
        ask.text = "Asking " + provider.label + "…"
        Thread {
            val result = AiSetup.ask(
                provider = provider,
                apiKey = settings.aiKey,
                model = settings.aiModel,
                baseUrl = settings.aiBaseUrl,
                email = text,
                currency = settings.currencyCode + " (" + settings.currencySymbol + ")",
                unit = unit,
                current = current,
            )
            field.post {
                ask.isEnabled = true
                ask.text = "Ask " + provider.label
                when (result) {
                    is AiSetup.Result.Err -> {
                        status.setTextColor(p.orange)
                        status.text = result.message
                    }
                    is AiSetup.Result.Ok -> {
                        status.text = ""
                        proposal = result.proposal
                        renderResult()
                    }
                }
            }
        }.start()
    }

    sheet.show()
}

private fun ruleRow(ctx: Context, p: Palette, label: String, value: String): View =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 6f), 0, 0)
        addView(TextView(ctx).apply {
            text = label
            textSize = Ios.T_BODY
            setTextColor(p.label2)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = value
            textSize = Ios.T_BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.label)
        })
    }

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(ctx, url, Toast.LENGTH_SHORT).show()
    }
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/** Pick which captured screen to hand to the provider. */
private fun capturePicker(
    ctx: Context,
    p: Palette,
    settings: SettingsRepository,
    onChanged: () -> Unit,
    captures: () -> List<ScreenCapture>,
) {
    val list = captures().filter { it.snap.nodes.isNotEmpty() }
        .sortedByDescending { it.snap.at }
    val provider = settings.aiProviderOrDefault()
    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Fix a missed screen",
        "Pick one capture. Its node text goes to " + provider.label + " with your key; the " +
            "mapping it proposes is tested on that capture and only saved when you say so.")

    if (list.isEmpty()) {
        col.addView(TextView(ctx).apply {
            text = "No screen captured yet. Open a driver app, let Shiftlit read an offer " +
                "card, then come back — or use Diagnostics on a screen that missed."
            textSize = Ios.T_FOOTNOTE
            setTextColor(p.label2)
            setPadding(0, dp(ctx, 4f), 0, 0)
        })
    }
    list.take(8).forEach { c ->
        col.addView(Sections.row(ctx, p,
            title = c.name,
            subtitle = c.snap.outcome + " · " + c.snap.nodes.size + " nodes · " +
                ago(c.snap.at),
            iconGlyph = "grid", iconTint = p.tint,
            chevron = true,
        ) {
            sheet.dismiss()
            screenFixSheet(ctx, p, settings, onChanged, c.name, c.pkg, c.snap)
        })
        col.addView(Sections.separator(ctx, p))
    }
    col.addView(TextView(ctx).apply {
        text = "Cancel"
        textSize = Ios.T_BODY
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(p.tint)
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 12f), 0, dp(ctx, 4f))
        setOnClickListener { sheet.dismiss() }
    })
    sheet.add(col).show()
}

/**
 * Ask the provider to read one diagnostics capture and point at the nodes the
 * parser needs: payout, distance, duration, accept, decline.
 *
 * Nothing is written until the proposed mapping is run through the real
 * [OfferParser] on that capture. A mapping that doesn't produce an offer is
 * shown as a failed test and cannot be saved, so a plausible-sounding answer
 * can't quietly change how a screen is read.
 */
fun screenFixSheet(
    ctx: Context,
    p: Palette,
    settings: SettingsRepository,
    onChanged: () -> Unit,
    name: String,
    pkg: String,
    snap: Diagnostics.Snap,
) {
    val flat = snap.nodes.map { n ->
        OfferParser.FlatNode(
            n.text,
            n.viewId,
            Bounds(
                n.bounds.getOrElse(0) { 0 },
                n.bounds.getOrElse(1) { 0 },
                n.bounds.getOrElse(2) { 0 },
                n.bounds.getOrElse(3) { 0 },
            ),
            n.clickable,
        )
    }
    if (flat.isEmpty()) return
    val provider = settings.aiProviderOrDefault()
    val isKm = settings.distanceUnit == "KM"
    val unit = if (isKm) "km" else "mi"
    val symbol = settings.currencySymbol
    val extra = OfferParser.extraMoney(settings.currencyCode, symbol)
    val currency = settings.currencyCode + " (" + symbol + ")"
    // The key captured with the snapshot wins where there is one: it was
    // computed before the node cap, so it matches what a live scan produces.
    val screen = snap.screen.ifBlank { ParseHints.screenKey(flat) }
    // Reads older than this capture. They carry no node tree, only outcome,
    // decision and a few texts — enough to see that the same card keeps missing.
    val earlier = Diagnostics.history(ctx, pkg).filter { it.at < snap.at }.take(6)

    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Fix this screen with AI",
        "$name · ${flat.size} captured nodes. " + provider.label + " reads this one screen, " +
            "explains why it missed, and points at the payout, distance, duration and " +
            "accept/decline nodes.")
    sheet.add(col)

    val status = TextView(ctx).apply {
        textSize = Ios.T_FOOTNOTE
        setTextColor(p.label2)
        setPadding(0, dp(ctx, 10f), 0, 0)
    }
    col.addView(status)

    val ask = iosButton(ctx, p, "Ask " + provider.label)
    col.addView(ask, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(ctx, 12f) })

    val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(resultBox)

    fun nodeLabel(i: Int?): String {
        if (i == null) return ""
        val n = flat.getOrNull(i) ?: return ""
        val text = if (n.text.isBlank()) {
            if (n.clickable) "(unnamed button)" else "(unnamed)"
        } else n.text.take(60)
        return "$text  ·  node $i"
    }

    fun hintOf(fix: AiSetup.ScreenFix): ParseHints.Hint? {
        if (!fix.canSave) return null
        return ParseHints.Hint(
            pkg = pkg,
            screen = screen,
            payout = fix.payout?.let { ParseHints.anchor(flat, it) },
            distance = fix.distance?.let { ParseHints.anchor(flat, it) },
            duration = fix.duration?.let { ParseHints.anchor(flat, it) },
            accept = fix.accept?.let { ParseHints.anchor(flat, it) },
            decline = fix.decline?.let { ParseHints.anchor(flat, it) },
        )
    }

    fun test(h: ParseHints.Hint): Pair<Boolean, String> {
        val offer = OfferParser.parse(
            pkg, flat, extra, symbol, settings.currencyCode, null, h,
            settings.distanceUnit,
        ) ?: return false to
            "Those nodes still don't make an offer on this capture. Nothing is saved — " +
            "try \"Teach this screen\" instead."
        val parts = ArrayList<String>(5)
        parts.add(Currencies.format(offer.payoutCents, offer.currencySymbol, offer.currencyCode))
        offer.distanceKm?.let {
            parts.add("%.1f %s".format(if (isKm) it else it * 0.621371, unit))
        }
        offer.durationMin?.let { parts.add("%.0f min".format(it)) }
        if (offer.acceptNode != null) parts.add("accept ✓")
        if (offer.declineNode != null) parts.add("decline ✓")
        return true to "Tested on this capture: reads as " + parts.joinToString(" · ")
    }

    fun showResult(seen: AiSetup.ScreenFix) {
        resultBox.removeAllViews()
        val fix = AiSetup.sanitize(seen, snap.nodes, extra, symbol)

        fun line(body: String, size: Float, color: Int, top: Float, bold: Boolean) {
            resultBox.addView(TextView(ctx).apply {
                this.text = body
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, top), 0, 0)
            })
        }

        seen.note?.let {
            line("What it found", Ios.T_FOOTNOTE, p.label3, 14f, bold = true)
            line(it, Ios.T_FOOTNOTE, p.label2, 4f, bold = false)
        }

        val dropped = ArrayList<String>(3)
        if (seen.payout != null && fix.payout == null) dropped.add("payout")
        if (seen.distance != null && fix.distance == null) dropped.add("distance")
        if (seen.duration != null && fix.duration == null) dropped.add("duration")
        if (dropped.isNotEmpty()) {
            line(
                "Ignored " + dropped.joinToString(", ") +
                    ": that node doesn't hold the kind of value the role needs.",
                Ios.T_CAPTION, p.orange, 6f, bold = false,
            )
        }

        val roles = listOf(
            "Payout" to fix.payout,
            "Distance" to fix.distance,
            "Duration" to fix.duration,
            "Accept" to fix.accept,
            "Decline" to fix.decline,
        )
        roles.forEach { (role, i) ->
            if (i != null) resultBox.addView(ruleRow(ctx, p, role, nodeLabel(i)))
        }

        if (!fix.canSave) {
            line(
                "It didn't point at a payout or an accept/decline button, so there is " +
                    "nothing to save for this screen.",
                Ios.T_FOOTNOTE, p.orange, 10f, bold = false,
            )
            return
        }
        val h = hintOf(fix) ?: return
        val (ok, msg) = test(h)
        line(msg, Ios.T_FOOTNOTE, if (ok) p.green else p.orange, 10f, bold = false)
        if (!ok) return
        line(
            "Saved like a taught mapping: it applies only on this screen, and only when " +
                "the normal parser finds nothing.",
            Ios.T_CAPTION, p.label3, 6f, bold = false,
        )
        val apply = iosButton(ctx, p, "Save this mapping", p.green)
        resultBox.addView(apply, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 10f) })
        apply.setOnClickListener {
            ParseHints.save(ctx, h)
            sheet.dismiss()
            Toast.makeText(
                ctx, "Mapping saved — it applies the next time this screen is read",
                Toast.LENGTH_SHORT,
            ).show()
            onChanged()
        }
    }

    ask.setOnClickListener {
        resultBox.removeAllViews()
        status.setTextColor(p.label2)
        val sent = if (earlier.isEmpty()) {
            "this screen (${flat.size} nodes)"
        } else {
            "this screen (${flat.size} nodes) and ${earlier.size} earlier reads"
        }
        status.text = "Sending $sent to " + provider.label +
            ". Only this app's diagnostics leave the phone."
        ask.isEnabled = false
        ask.text = "Asking " + provider.label + "…"
        Thread {
            val r = AiSetup.askScreenFix(
                provider = provider,
                apiKey = settings.aiKey,
                model = settings.aiModel,
                baseUrl = settings.aiBaseUrl,
                appName = name,
                pkg = pkg,
                outcome = snap.outcome,
                screen = snap.screen,
                nodes = snap.nodes,
                decision = snap.decision,
                currency = currency,
                unit = unit,
                history = earlier,
            )
            status.post {
                ask.isEnabled = true
                ask.text = "Ask " + provider.label
                when (r) {
                    is AiSetup.FixResult.Err -> {
                        status.setTextColor(p.orange)
                        status.text = r.message
                    }
                    is AiSetup.FixResult.Ok -> {
                        status.text = ""
                        showResult(r.fix)
                    }
                }
            }
        }.start()
    }

    sheet.show()
}
