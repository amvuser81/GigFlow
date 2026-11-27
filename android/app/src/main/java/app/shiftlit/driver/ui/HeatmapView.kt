package app.shiftlit.driver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import app.shiftlit.driver.LocalDb
import app.shiftlit.driver.ui.Ios.Palette
import app.shiftlit.driver.ui.Ios.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Offline density map of the GPS trail kept with tracked shifts.
 *
 * There are no map tiles and no network, so this cannot pretend to be a street
 * map: it is an equirectangular projection of the recorded points with a scale
 * bar for orientation. At city scale the local projection is accurate enough,
 * and the shape is what the trail actually knows.
 */
class HeatmapView(ctx: Context, private val p: Palette) : View(ctx) {

    private var points: List<LocalDb.TrackPoint> = emptyList()
    private var heat: Bitmap? = null
    private var barKm = 0.0
    private var barPx = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.label2
        strokeWidth = dp(ctx, 1.2f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.label3
        textSize = dp(ctx, 10f).toFloat()
    }
    private val heatPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(p.tint, PorterDuff.Mode.SRC_IN)
    }

    fun setPoints(pts: List<LocalDb.TrackPoint>) {
        points = pts
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        heat?.recycle()
        heat = null
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        heat?.recycle()
        heat = null
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || points.size < 2) return

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (pt in points) {
            if (pt.lat < minLat) minLat = pt.lat
            if (pt.lat > maxLat) maxLat = pt.lat
            if (pt.lng < minLng) minLng = pt.lng
            if (pt.lng > maxLng) maxLng = pt.lng
        }
        if (minLat > maxLat || minLng > maxLng) return

        val pad = min(dp(context, 16f).toFloat(), min(w, h) / 6f)
        val availW = max((w - 2 * pad).toDouble(), 1.0)
        val availH = max((h - 2 * pad - dp(context, 16f)).toDouble(), 1.0)
        val centerLat = (minLat + maxLat) / 2
        val centerLng = (minLng + maxLng) / 2
        // Longitude shrinks with latitude; at city scale one cosine is enough.
        val kx = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)
        // A handful of points within a few metres would otherwise zoom the map
        // into a blur with a "0 m" scale bar. Floor the view at ~200 m.
        val spanX = max((maxLng - minLng) * kx, 0.002)
        val spanY = max(maxLat - minLat, 0.002)
        val s = min(availW / spanX, availH / spanY)
        if (!s.isFinite() || s <= 0.0) return

        val cx = w / 2.0
        val cy = pad + availH / 2.0
        fun sx(lng: Double) = (cx + (lng - centerLng) * kx * s).toFloat()
        fun sy(lat: Double) = (cy - (lat - centerLat) * s).toFloat()

        // One degree of latitude is ~111.32 km everywhere; the longitudes are
        // already cosine-corrected, so the bar is honest in both axes.
        val kmPerPx = 111.32 / s
        if (kmPerPx > 0.0 && kmPerPx.isFinite()) {
            val target = kmPerPx * (w * 0.22)
            val pow = 10.0.pow(floor(log10(target)))
            val nice = listOf(1.0, 2.0, 5.0, 10.0).firstOrNull { it * pow >= target } ?: 10.0
            barKm = nice * pow
            barPx = (barKm / kmPerPx).toFloat()
        }

        // Draw at reduced resolution: a few thousand soft blobs still read as
        // a heatmap when scaled up, and this keeps the one-off cost small.
        val bw = max((w * 0.7f).toInt(), 1)
        val bh = max((h * 0.7f).toInt(), 1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = max(min(bw, bh) * 0.17f, dp(context, 5f).toFloat())
        val brush = Bitmap.createBitmap(
            max((radius * 2).toInt(), 2), max((radius * 2).toInt(), 2),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(brush).drawCircle(
            radius, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    radius, radius, radius,
                    0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
            },
        )
        val add = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        val k = bw / w.toFloat()
        val step = max(points.size / 6000, 1)
        var i = 0
        while (i < points.size) {
            val pt = points[i]
            canvas.drawBitmap(brush, sx(pt.lng) * k - radius, sy(pt.lat) * k - radius, add)
            i += step
        }
        brush.recycle()
        heat = bmp
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val bmp = heat ?: return
        c.drawBitmap(
            bmp,
            Rect(0, 0, bmp.width, bmp.height),
            Rect(0, 0, width, height),
            heatPaint,
        )
        if (barPx > 0f) {
            val y = height - dp(context, 14f).toFloat()
            val x0 = dp(context, 14f).toFloat()
            val tick = dp(context, 3f).toFloat()
            c.drawLine(x0, y, x0 + barPx, y, barPaint)
            c.drawLine(x0, y - tick, x0, y + tick, barPaint)
            c.drawLine(x0 + barPx, y - tick, x0 + barPx, y + tick, barPaint)
            c.drawText(scaleLabel(), x0, y - dp(context, 6f), textPaint)
            c.drawText("N", width - dp(context, 14f).toFloat(),
                dp(context, 14f).toFloat(), textPaint)
        }
    }

    private fun scaleLabel(): String {
        val km = barKm
        return when {
            km >= 1.0 && abs(km - Math.round(km).toDouble()) < 0.001 ->
                Math.round(km).toString() + " km"
            km >= 1.0 -> "%.1f km".format(km)
            else -> Math.round(km * 1000).toString() + " m"
        }
    }
}
