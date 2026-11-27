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
import app.shiftlit.driver.DetectedOffer
import app.shiftlit.driver.Diagnostics
import app.shiftlit.driver.OfferParser
import app.shiftlit.driver.ParseHints
import app.shiftlit.driver.SettingsRepository
import app.shiftlit.driver.ui.Ios.Palette
import app.shiftlit.driver.ui.Ios.dp


/**
 * AI setup, where it is used: from the diagnostics sheet.
 *
 * Optional and bring-your-own-key. The one request it enables is a captured
 * screen the driver explicitly asks about, sent from this phone straight to
 * the provider. There is no Shiftlit server in that path.
 */
fun aiSettingsSheet(
    ctx: Context,
    p: Palette,
    settings: SettingsRepository,
    onChanged: () -> Unit,
) {
    val sheet = IosSheet(ctx, p)
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    sheet.add(col)

    fun render() {
        col.removeAllViews()
        val provider = settings.aiProviderOrDefault()
        col.addView(sheetColumn(ctx, p, "AI setup",
            "Optional, used only to fix a screen the parser missed. Your key stays in this " +
                "app's private storage and goes only to " + provider.label + "."))
        col.addView(Sections.row(ctx, p,
            title = "Provider",
            subtitle = if (provider == AiProvider.CUSTOM)
                settings.aiBaseUrl.ifBlank { "No endpoint yet" }
            else provider.defaultModel,
            value = provider.label,
            iconGlyph = "bolt", iconTint = p.tint,
            chevron = true,
        ) { providerSheet(ctx, p, settings) { render() } })
        col.addView(Sections.separator(ctx, p))

        if (provider == AiProvider.CUSTOM) {
            col.addView(Sections.row(ctx, p,
                title = "Endpoint",
                subtitle = "HTTPS only — your key goes straight there",
                value = if (settings.aiBaseUrl.isBlank()) "Not set" else "Saved",
                iconGlyph = "link", iconTint = p.gray,
                chevron = true,
            ) { endpointSheet(ctx, p, settings) { render() } })
            col.addView(Sections.separator(ctx, p))
        }

        col.addView(Sections.row(ctx, p,
            title = "API key",
            subtitle = if (settings.aiKey.isBlank())
                "You need a key from " + provider.label
            else "Saved on this phone, sent only to this provider",
            value = if (settings.aiKey.isBlank()) "Not set" else "Saved",
            iconGlyph = "doc",
            iconTint = if (settings.aiKey.isBlank()) p.orange else p.green,
            chevron = true,
        ) { keySheet(ctx, p, settings) { render() } })
        col.addView(Sections.separator(ctx, p))

        col.addView(Sections.row(ctx, p,
            title = "Model",
            subtitle = settings.aiModel.ifBlank {
                provider.defaultModel.ifBlank { "Set a model name" }
            },
            iconGlyph = "slider", iconTint = p.gray,
            chevron = true,
        ) { modelSheet(ctx, p, settings) { render() } })
        col.addView(TextView(ctx).apply {
            text = "Nothing is sent until you tap Ask in Diagnostics. Every other feature " +
                "works without a key."
            textSize = Ios.T_CAPTION
            setTextColor(p.label3)
            setPadding(dp(ctx, 16f), dp(ctx, 8f), dp(ctx, 16f), 0)
        })
        col.addView(TextView(ctx).apply {
            text = "Done"
            textSize = Ios.T_BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.tint)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 4f))
            setOnClickListener {
                sheet.dismiss()
                onChanged()
            }
        })
    }
    render()
    sheet.show()
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
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine()
        maxLines = 1
        minLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0)
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    // A fixed height: a wrapping field used to grow until it filled the sheet.
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48f)))
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
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
        maxLines = 1
        minLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0)
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48f)))
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
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
        maxLines = 1
        minLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0)
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
    }
    col.addView(field, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48f)))
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

private fun ruleRow(
    ctx: Context, p: Palette, label: String, value: String, valueColor: Int = p.label,
): View =
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
            setTextColor(valueColor)
        })
    }

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(ctx, url, Toast.LENGTH_SHORT).show()
    }
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

    fun test(h: ParseHints.Hint): DetectedOffer? = OfferParser.parse(
        pkg, flat, extra, symbol, settings.currencyCode, null, h,
        settings.distanceUnit,
    )

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
        val h = AiSetup.hintFor(fix, pkg, screen, flat) ?: return
        val offer = test(h)
        if (offer == null) {
            line(
                "Those nodes still don't make an offer on this capture. Nothing is saved — " +
                    "try \"Teach this screen\" instead.",
                Ios.T_FOOTNOTE, p.orange, 10f, bold = false,
            )
            return
        }
        // The real parser's reading of that capture, shown before the mapping
        // can be saved: the driver sees the payout, distance and duration the
        // hint would actually produce, not just the nodes it points at.
        val preview = AiSetup.previewOf(offer, isKm)
        line("Parsed from this capture", Ios.T_FOOTNOTE, p.label3, 14f, bold = true)
        val parsed = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
            background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
        }
        parsed.addView(ruleRow(ctx, p, "Payout", preview.payout))
        parsed.addView(ruleRow(
            ctx, p, "Distance", preview.distance ?: "not read",
            if (preview.distance == null) p.orange else p.label,
        ))
        parsed.addView(ruleRow(
            ctx, p, "Duration", preview.duration ?: "not read",
            if (preview.duration == null) p.orange else p.label,
        ))
        resultBox.addView(parsed, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 6f) })
        val controls = listOfNotNull(
            "accept ✓".takeIf { offer.acceptNode != null },
            "decline ✓".takeIf { offer.declineNode != null },
        )
        if (controls.isNotEmpty()) {
            line(controls.joinToString(" · "), Ios.T_CAPTION, p.green, 6f, bold = false)
        }
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
