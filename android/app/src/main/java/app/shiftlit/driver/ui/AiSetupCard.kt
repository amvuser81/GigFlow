package app.shiftlit.driver.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
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

        val testStatus = TextView(ctx).apply {
            textSize = Ios.T_CAPTION
            setTextColor(p.label3)
            setPadding(dp(ctx, 16f), dp(ctx, 2f), dp(ctx, 16f), 0)
        }
        col.addView(Sections.row(ctx, p,
            title = "Test connection",
            subtitle = "Checks the key, model and endpoint now instead of at a missed offer",
            value = "Test",
            iconGlyph = "bolt", iconTint = p.tint,
            chevron = true,
        ) {
            if (settings.aiKey.isBlank()) {
                Toast.makeText(ctx, "Add your API key first", Toast.LENGTH_SHORT).show()
            } else {
                testStatus.setTextColor(p.label2)
                testStatus.text = "Testing " + provider.label + "…"
                Thread {
                    val err = AiSetup.testConnection(
                        provider, settings.aiKey, settings.aiModel, settings.aiBaseUrl,
                    )
                    testStatus.post {
                        if (err == null) {
                            testStatus.setTextColor(p.green)
                            testStatus.text = provider.label +
                                " answered — key, model and endpoint work."
                        } else {
                            testStatus.setTextColor(p.orange)
                            testStatus.text = err
                        }
                    }
                }.start()
            }
        })
        col.addView(testStatus)
        col.addView(Sections.separator(ctx, p))

        col.addView(Sections.row(ctx, p,
            title = "Model",
            subtitle = settings.aiModel.ifBlank {
                provider.defaultModel.ifBlank { "Set a model name" }
            },
            iconGlyph = "slider", iconTint = p.gray,
            chevron = true,
        ) { modelSheet(ctx, p, settings) { render() } })
        col.addView(Sections.separator(ctx, p))
        col.addView(Sections.row(ctx, p,
            title = "Saved mappings",
            subtitle = "AI-fixed and taught screens, with what each one can automate",
            value = ParseHints.all(ctx).size.toString(),
            iconGlyph = "doc", iconTint = p.gray,
            chevron = true,
        ) { savedMappingsSheet(ctx, p) { render() } })
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
 * The parser's own reading of a capture, in the same card the AI sheet uses.
 * Shared with the manual teach sheet so both ways of making a mapping show
 * the driver the same result.
 */
fun offerPreviewCard(
    ctx: Context,
    p: Palette,
    offer: DetectedOffer,
    isKm: Boolean,
): View {
    val preview = AiSetup.previewOf(offer, isKm)
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        background = Ios.rounded(Ios.R_BUTTON, p.fill, ctx)
        addView(ruleRow(ctx, p, "Payout", preview.payout))
        addView(ruleRow(
            ctx, p, "Distance", preview.distance ?: "not read",
            if (preview.distance == null) p.orange else p.label,
        ))
        addView(ruleRow(
            ctx, p, "Duration", preview.duration ?: "not read",
            if (preview.duration == null) p.orange else p.label,
        ))
    }
}

/** One relative-time phrase, shared by the mapping manager. */
private fun lastSeen(at: Long): String {
    if (at <= 0) return "never"
    val s = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/**
 * Every saved mapping, with the one fact that matters for each: is this an AI
 * mapping whose controls were never confirmed, and has it produced an offer on
 * a live screen? Forget is here so a bad AI guess can always be undone.
 */
private fun savedMappingsSheet(ctx: Context, p: Palette, onChanged: () -> Unit) {
    val sheet = IosSheet(ctx, p)
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    sheet.add(col)
    fun render() {
        col.removeAllViews()
        col.addView(sheetColumn(ctx, p, "Saved mappings",
            "AI-fixed and manually taught screens. A mapping only applies to the " +
                "layout it was made on; forgetting one re-enables the normal parser there."))
        val hints = ParseHints.all(ctx)
        if (hints.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "No saved mappings yet."
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.label2)
                setPadding(dp(ctx, 20f), dp(ctx, 6f), dp(ctx, 20f), dp(ctx, 16f))
            })
        }
        hints.forEach { h ->
            val rows = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 20f), dp(ctx, 12f), dp(ctx, 20f), dp(ctx, 8f))
            }
            rows.addView(TextView(ctx).apply {
                text = if (h.isAi) "AI mapping" else "Taught mapping"
                textSize = Ios.T_SUBHEAD
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.label)
            })
            rows.addView(TextView(ctx).apply {
                text = h.pkg
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.label3)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            h.model?.let { model ->
                rows.addView(TextView(ctx).apply {
                    text = model
                    textSize = Ios.T_FOOTNOTE
                    setTextColor(p.label3)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            rows.addView(TextView(ctx).apply {
                text = "screen " + h.screen
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.label3)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val status = when {
                h.isAi && !h.buttonsConfirmed ->
                    "Buttons unconfirmed — no auto-accept or auto-decline"
                h.pendingCheck -> "Not seen working on a live screen yet"
                h.lastWorkedAt > 0 -> "Last read worked ${lastSeen(h.lastWorkedAt)}"
                else -> "No live read since it was saved"
            }
            rows.addView(TextView(ctx).apply {
                text = status
                textSize = Ios.T_FOOTNOTE
                setTextColor(
                    if (h.isAi && !h.buttonsConfirmed) p.orange
                    else if (h.pendingCheck) p.orange else p.green,
                )
                setPadding(0, dp(ctx, 2f), 0, 0)
            })
            rows.addView(TextView(ctx).apply {
                text = "Forget this mapping"
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.red)
                setPadding(0, dp(ctx, 6f), 0, 0)
                setOnClickListener {
                    ParseHints.remove(ctx, h)
                    Toast.makeText(ctx, "Mapping forgotten", Toast.LENGTH_SHORT).show()
                    onChanged()
                    render()
                }
            })
            col.addView(rows)
            col.addView(Sections.separator(ctx, p))
        }
        col.addView(TextView(ctx).apply {
            text = "Close"
            textSize = Ios.T_BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.tint)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10f), 0, 0)
            setOnClickListener { sheet.dismiss() }
        })
    }
    render()
    sheet.show()
}

/**
 * Ask the provider to read one diagnostics capture and point at the nodes the
 * parser needs: payout, distance, duration, accept, decline.
 *
 * The driver can correct any pick before saving. The mapping is then run
 * through the real [OfferParser] on that capture. An AI-picked accept/decline
 * is never trusted for automation until the driver confirms it on a fresh
 * capture, and an unnamed control needs an explicit tick before Save at all.
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
    val screen = snap.screen.ifBlank { ParseHints.screenKey(flat) }
    val earlier = Diagnostics.history(ctx, pkg).filter { it.at < snap.at }.take(6)
    val modelName = settings.aiModel.ifBlank { provider.defaultModel }

    var fix = AiSetup.ScreenFix()
    var raw = AiSetup.ScreenFix()
    var includeHistory = true
    var confirmAccept = false
    var confirmDecline = false

    val sheet = IosSheet(ctx, p)
    val col = sheetColumn(ctx, p, "Fix this screen with AI",
        "$name · ${flat.size} captured nodes. " + provider.label + " reads this one screen, " +
            "explains why it missed, and points at the payout, distance, duration and " +
            "accept/decline nodes. You can correct any pick before saving.")

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

    val privacy = TextView(ctx).apply {
        textSize = Ios.T_CAPTION
        setTextColor(p.label3)
        setPadding(0, dp(ctx, 8f), 0, 0)
    }
    val historyToggle = TextView(ctx).apply {
        textSize = Ios.T_FOOTNOTE
        setTextColor(p.tint)
        setPadding(0, dp(ctx, 6f), 0, 0)
    }
    fun updatePrivacy() {
        val historyPart = if (includeHistory && earlier.isNotEmpty())
            " and ${earlier.size} earlier reads" else ""
        privacy.text = "Sends this screen (${flat.size} nodes)$historyPart to " +
            provider.label + ". Your API key is never included."
        historyToggle.text = (if (includeHistory) "☑" else "☐") +
            " Include earlier reads (${earlier.size})"
    }
    updatePrivacy()
    col.addView(privacy)
    if (earlier.isNotEmpty()) {
        historyToggle.setOnClickListener {
            includeHistory = !includeHistory
            updatePrivacy()
        }
        col.addView(historyToggle)
    }
    col.addView(TextView(ctx).apply {
        text = "Review what is sent"
        textSize = Ios.T_FOOTNOTE
        setTextColor(p.tint)
        setPadding(0, dp(ctx, 6f), 0, 0)
        setOnClickListener {
            val prompt = AiSetup.screenPrompt(
                appName = name,
                pkg = pkg,
                outcome = snap.outcome,
                screen = snap.screen,
                nodes = snap.nodes,
                decision = snap.decision,
                currency = currency,
                unit = unit,
                history = if (includeHistory) earlier else emptyList(),
            )
            val rs = IosSheet(ctx, p)
            val rc = sheetColumn(ctx, p, "What will be sent",
                "Exactly the capture below goes to " + provider.label +
                    ". Your key and saved settings are not part of it.")
            rc.addView(TextView(ctx).apply {
                text = prompt.text.take(8000)
                textSize = Ios.T_CAPTION
                typeface = Typeface.MONOSPACE
                setTextColor(p.label2)
            })
            rc.addView(TextView(ctx).apply {
                text = "Close"
                textSize = Ios.T_BODY
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.tint)
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 14f), 0, 0)
                setOnClickListener { rs.dismiss() }
            })
            rs.add(rc).show()
        }
    })

    val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(resultBox)

    fun nodeLabel(i: Int?): String {
        if (i == null) return "Not set"
        val n = flat.getOrNull(i) ?: return "Not set"
        val text = if (n.text.isBlank()) {
            val corner = "at ${n.bounds.left},${n.bounds.top}"
            if (n.clickable) "(unnamed button $corner)" else "(unnamed $corner)"
        } else n.text.take(60)
        return "$text  ·  node $i"
    }

    fun test(h: ParseHints.Hint): DetectedOffer? = OfferParser.parse(
        pkg, flat, extra, symbol, settings.currencyCode, null, h,
        settings.distanceUnit,
    )

    fun isUnlabeled(i: Int?): Boolean =
        i != null && flat.getOrNull(i)?.text.isNullOrBlank()

    var rerender: () -> Unit = {}

    fun setRole(role: String, i: Int?) {
        fix = when (role) {
            "Payout" -> fix.copy(payout = i)
            "Distance" -> fix.copy(distance = i)
            "Duration" -> fix.copy(duration = i)
            "Accept" -> fix.copy(accept = i)
            else -> fix.copy(decline = i)
        }
        if (role == "Accept") confirmAccept = false
        if (role == "Decline") confirmDecline = false
        rerender()
    }

    fun pickNode(role: String, current: Int?) {
        val ps = IosSheet(ctx, p)
        val pc = sheetColumn(ctx, p, "Pick $role",
            "Tap the node that holds the $role. Amounts still have to pass the parser's " +
                "own value check before they can be saved.")
        pc.addView(TextView(ctx).apply {
            text = "None"
            textSize = Ios.T_BODY
            setTextColor(p.label2)
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 8f))
            setOnClickListener {
                ps.dismiss()
                setRole(role, null)
            }
        })
        flat.forEachIndexed { i, n ->
            val text = when {
                n.text.isNotBlank() -> n.text.take(90)
                n.clickable -> "(unnamed button at ${n.bounds.left},${n.bounds.top})"
                else -> "(unnamed at ${n.bounds.left},${n.bounds.top})"
            }
            pc.addView(TextView(ctx).apply {
                this.text = "$i · $text" +
                    (n.viewId?.substringAfterLast('/')?.let { "  ·  $it" }.orEmpty())
                textSize = Ios.T_FOOTNOTE
                setTextColor(if (i == current) p.tint else p.label)
                if (i == current) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, 7f), 0, dp(ctx, 7f))
                setOnClickListener {
                    ps.dismiss()
                    setRole(role, i)
                }
            })
        }
        ps.add(pc).show()
    }

    fun line(body: String, size: Float, color: Int, top: Float, bold: Boolean = false) {
        resultBox.addView(TextView(ctx).apply {
            text = body
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, top), 0, 0)
        })
    }

    fun roleEditor(role: String, i: Int?) {
        // Two lines: the role and its action, then the picked node. A long
        // unlabeled-control label used to wrap underneath the Change column.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 4f))
            setOnClickListener { pickNode(role, i) }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = role
                textSize = Ios.T_BODY
                setTextColor(p.label2)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "Change"
                textSize = Ios.T_FOOTNOTE
                setTextColor(p.tint)
            })
        })
        row.addView(TextView(ctx).apply {
            text = nodeLabel(i)
            textSize = Ios.T_FOOTNOTE
            setTextColor(if (i == null) p.orange else p.label)
            setPadding(0, dp(ctx, 1f), 0, 0)
        })
        resultBox.addView(row)
    }

    fun renderResult() {
        resultBox.removeAllViews()
        raw.note?.let {
            line("What it found", Ios.T_FOOTNOTE, p.label3, 14f, bold = true)
            line(it, Ios.T_FOOTNOTE, p.label2, 4f)
        }

        val dropped = ArrayList<String>(3)
        if (raw.payout != null && fix.payout == null) dropped.add("payout")
        if (raw.distance != null && fix.distance == null) dropped.add("distance")
        if (raw.duration != null && fix.duration == null) dropped.add("duration")
        if (dropped.isNotEmpty()) {
            line(
                "Ignored " + dropped.joinToString(", ") +
                    ": that node doesn't hold the kind of value the role needs.",
                Ios.T_CAPTION, p.orange, 6f,
            )
        }

        line("Tap a row to correct it", Ios.T_FOOTNOTE, p.label3, 12f, bold = true)
        roleEditor("Payout", fix.payout)
        roleEditor("Distance", fix.distance)
        roleEditor("Duration", fix.duration)
        roleEditor("Accept", fix.accept)
        roleEditor("Decline", fix.decline)

        val sanity = AiSetup.sanity(fix, snap.nodes, extra, symbol)
        sanity.warnings.forEach { line("Check: $it", Ios.T_FOOTNOTE, p.orange, 6f) }
        sanity.blockers.forEach { line("Can't save: $it", Ios.T_FOOTNOTE, p.orange, 6f) }

        // An unnamed control is a box on a screen, not a label — a model can be
        // exactly wrong about which corner it is. Saving one requires a human
        // tick, separately for accept and decline.
        if (isUnlabeled(fix.accept)) {
            val checked = confirmAccept
            resultBox.addView(TextView(ctx).apply {
                text = (if (checked) "☑" else "☐") +
                    " I checked that this unnamed control is Accept"
                textSize = Ios.T_FOOTNOTE
                setTextColor(if (checked) p.green else p.orange)
                setPadding(0, dp(ctx, 8f), 0, 0)
                setOnClickListener {
                    confirmAccept = !confirmAccept
                    renderResult()
                }
            })
        }
        if (isUnlabeled(fix.decline)) {
            val checked = confirmDecline
            resultBox.addView(TextView(ctx).apply {
                text = (if (checked) "☑" else "☐") +
                    " I checked that this unnamed control is Decline"
                textSize = Ios.T_FOOTNOTE
                setTextColor(if (checked) p.green else p.orange)
                setPadding(0, dp(ctx, 4f), 0, 0)
                setOnClickListener {
                    confirmDecline = !confirmDecline
                    renderResult()
                }
            })
        }

        if (!fix.canSave) {
            line(
                "Pick a payout or an accept/decline button — there is nothing to save yet.",
                Ios.T_FOOTNOTE, p.orange, 10f,
            )
            return
        }
        val h = AiSetup.hintFor(
            fix, pkg, screen, flat,
            source = "ai", model = modelName,
        ) ?: return
        val offer = test(h)
        if (offer == null) {
            line(
                "Those nodes still don't make an offer on this capture. Nothing is saved — " +
                    "try a different node, or \"Teach this screen\" instead.",
                Ios.T_FOOTNOTE, p.orange, 10f,
            )
            return
        }

        line("Parsed from this capture", Ios.T_FOOTNOTE, p.label3, 14f, bold = true)
        resultBox.addView(offerPreviewCard(ctx, p, offer, isKm))
        val controls = listOfNotNull(
            "accept ✓".takeIf { offer.acceptNode != null },
            "decline ✓".takeIf { offer.declineNode != null },
        )
        if (controls.isNotEmpty()) {
            line(controls.joinToString(" · "), Ios.T_CAPTION, p.green, 6f)
        }
        line(
            "This mapping only applies on this screen. Its amounts are used only where " +
                "the normal parser finds nothing; its buttons apply whenever this screen " +
                "is read.",
            Ios.T_CAPTION, p.label3, 6f,
        )
        if (h.accept != null || h.decline != null) {
            line(
                "AI-picked buttons never auto-accept or auto-decline until you confirm them " +
                    "on a fresh capture in Diagnostics.",
                Ios.T_CAPTION, p.orange, 4f,
            )
        }

        val unnamedOk =
            (!isUnlabeled(fix.accept) || confirmAccept) &&
                (!isUnlabeled(fix.decline) || confirmDecline)
        if (!sanity.ok || !unnamedOk) {
            val why = when {
                !sanity.ok -> "Fix the issue above before saving."
                else -> "Tick the unnamed control above before saving."
            }
            line(why, Ios.T_FOOTNOTE, p.orange, 10f)
            return
        }
        val apply = iosButton(ctx, p, "Save this mapping", p.green)
        resultBox.addView(apply, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 10f) })
        apply.setOnClickListener {
            ParseHints.save(ctx, h)
            sheet.dismiss()
            Toast.makeText(
                ctx,
                if (h.accept != null || h.decline != null)
                    "Mapping saved — confirm its buttons on a fresh capture before auto-accept"
                else "Mapping saved — it applies the next time this screen is read",
                Toast.LENGTH_LONG,
            ).show()
            onChanged()
        }
    }

    rerender = { renderResult() }

    ask.setOnClickListener {
        resultBox.removeAllViews()
        fix = AiSetup.ScreenFix()
        raw = AiSetup.ScreenFix()
        confirmAccept = false
        confirmDecline = false
        status.setTextColor(p.label2)
        val sentHistory = if (includeHistory) earlier else emptyList()
        val sent = if (sentHistory.isEmpty()) {
            "this screen (${flat.size} nodes)"
        } else {
            "this screen (${flat.size} nodes) and ${sentHistory.size} earlier reads"
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
                history = sentHistory,
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
                        raw = r.fix
                        fix = AiSetup.sanitize(r.fix, snap.nodes, extra, symbol)
                        confirmAccept = false
                        confirmDecline = false
                        renderResult()
                    }
                }
            }
        }.start()
    }

    sheet.add(col).show()
}
