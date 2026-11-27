package app.shiftlit.driver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Point-at-the-card teach mode: a full-screen accessibility overlay laid over
 * the driver app. Every tap is mapped against the tree that is live at that
 * moment, so what gets taught is exactly what the card shows — never a snapshot
 * taken minutes earlier, and never a 200-row text list where an unlabeled "×"
 * is unidentifiable.
 *
 * The overlay swallows touches, so the card behind it cannot be scrolled while
 * teaching; that is deliberate — the point is to tap the control, and nothing
 * underneath moves.
 */
class TeachOverlay(
    private val service: AccessibilityService,
    private val session: TeachSession,
    private val onSave: (ParseHints.Hint) -> String,
    private val onCancel: () -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var markers: FrameLayout? = null
    private var status: TextView? = null
    private var saveButton: TextView? = null
    private val chips = LinkedHashMap<String, TextView>()

    /** Role the next tap will teach. */
    private var active: String = session.roles.firstOrNull { !session.isPicked(it) } ?: "Payout"

    private fun Int.dp(): Int = (this * density).toInt()

    fun show() {
        if (root != null) return

        val rootView = FrameLayout(service).apply {
            setBackgroundColor(Color.parseColor("#33000000"))
            setOnTouchListener { _, ev ->
                // rawX/rawY are screen coordinates, the same space the node
                // bounds use, so a tap maps 1:1 onto the captured tree.
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    tap(ev.rawX.toInt(), ev.rawY.toInt())
                }
                true
            }
        }

        val markerLayer = FrameLayout(service)
        rootView.addView(
            markerLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F214191F"))
            setPadding(18.dp(), 14.dp(), 18.dp(), 12.dp())
            isClickable = true
            // Swallow taps on the header's own background; its buttons still
            // receive theirs first.
            setOnTouchListener { _, _ -> true }
        }

        val titleRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(service).apply {
                text = "Teach this card"
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(service).apply {
                text = "Cancel"
                setTextColor(Color.parseColor("#9AA3B2"))
                textSize = 14f
                setPadding(12.dp(), 6.dp(), 0, 6.dp())
                setOnClickListener { cancel() }
            })
        }
        header.addView(titleRow)

        header.addView(TextView(service).apply {
            text = "Tap the payout or a button on the card — Shiftlit maps it to " +
                "the control you touched, even an icon with no name."
            setTextColor(Color.parseColor("#C7CBD4"))
            textSize = 12.5f
            setPadding(0, 6.dp(), 0, 0)
        })

        val chipRows = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dp(), 0, 0)
        }
        session.roles.chunked(3).forEach { chunk ->
            val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { role ->
                val chip = TextView(service).apply {
                    text = role
                    textSize = 13f
                    setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp())
                    setOnClickListener {
                        active = role
                        render()
                    }
                }
                chips[role] = chip
                row.addView(chip)
            }
            chipRows.addView(row)
        }
        header.addView(chipRows)

        header.addView(TextView(service).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A6"))
            setPadding(0, 8.dp(), 0, 0)
            status = this
        })

        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 0)
        }
        actions.addView(TextView(service).apply {
            text = "Undo"
            textSize = 14f
            setTextColor(Color.parseColor("#9AA3B2"))
            setPadding(0, 8.dp(), 18.dp(), 8.dp())
            setOnClickListener {
                session.undo()
                active = session.roles.firstOrNull { !session.isPicked(it) } ?: active
                render()
            }
        })
        actions.addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(TextView(service).apply {
            text = "Save"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(18.dp(), 8.dp(), 0, 8.dp())
            setOnClickListener { save() }
            saveButton = this
        })
        header.addView(actions)

        rootView.addView(
            header,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        try {
            wm.addView(rootView, params)
            root = rootView
            markers = markerLayer
            render()
        } catch (_: Exception) {
            // Service not fully attached — nothing to teach against.
            root = null
            markers = null
        }
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        markers = null
        status = null
        saveButton = null
        chips.clear()
    }

    private fun tap(x: Int, y: Int) {
        val node = session.pick(active, x, y)
        if (node == null) {
            toast("No control there — tap on the card.")
            return
        }
        active = session.roles.firstOrNull { !session.isPicked(it) } ?: active
        render()
    }

    private fun save() {
        val hint = session.buildHint()
        if (hint == null) {
            toast("Tap the payout, or an accept/decline button.")
            return
        }
        toast(onSave(hint))
        hide()
    }

    private fun cancel() {
        hide()
        onCancel()
    }

    private fun render() {
        session.roles.forEach { role ->
            val chip = chips[role] ?: return@forEach
            val picked = session.isPicked(role)
            chip.text = if (picked) "$role ✓" else role
            chip.setTextColor(
                when {
                    role == active -> Color.parseColor("#5AC8FA")
                    picked -> Color.WHITE
                    else -> Color.parseColor("#9AA3B2")
                },
            )
            chip.background = GradientDrawable().apply {
                cornerRadius = 8.dp().toFloat()
                setColor(
                    if (role == active) Color.parseColor("#22344A5E") else Color.TRANSPARENT,
                )
            }
        }

        saveButton?.setTextColor(
            if (session.isSavable()) Color.parseColor("#5AC8FA") else Color.parseColor("#5A6270"),
        )
        status?.text = "Now: $active · tap it on the card"

        markers?.removeAllViews()
        session.pickedRoles().forEach { role ->
            val node = session.nodeFor(role) ?: return@forEach
            markers?.addView(marker(role, node))
        }
    }

    private fun marker(role: String, node: OfferParser.FlatNode): View {
        val label = TextView(service).apply {
            text = role
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0B8A80"))
                cornerRadius = 6.dp().toFloat()
                setStroke(2.dp(), Color.parseColor("#5AC8FA"))
            }
        }
        val b = node.bounds
        label.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = b.left
            topMargin = b.top
        }
        return label
    }

    private fun toast(text: String) {
        Toast.makeText(service, text, Toast.LENGTH_LONG).show()
    }
}
