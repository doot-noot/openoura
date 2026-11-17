package dev.openoura.droid

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Palette + small view helpers. Plain Views, no extra libraries. */
object Theme {
    const val BG = 0xFF0E1116.toInt()
    const val CARD = 0xFF171B22.toInt()
    const val LINE = 0xFF232935.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val MUTED = 0xFF8B93A1.toInt()
    const val ACCENT = 0xFF5EEAD4.toInt()
    const val WARN = 0xFFFFA94D.toInt()
    const val DEEP = 0xFF3B5BDB.toInt()
    const val LIGHT = 0xFF74A9FF.toInt()
    const val REM = 0xFFB197FC.toInt()
    const val WAKE = 0xFFFFA94D.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.text(s: CharSequence, size: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

fun Context.vbox(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; stroke?.let { setStroke(2, it) }
}

/** A card with an optional caption; add content to the returned box. */
fun Context.card(parent: LinearLayout, caption: String? = null): LinearLayout {
    val c = vbox(16).apply { background = rounded(Theme.CARD, dp(16).toFloat()) }
    caption?.let { c.addView(text(it.uppercase(), 11f, Theme.MUTED, bold = true)) }
    parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    return c
}

/** Primary pill button. */
fun Context.pill(label: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = 15f; gravity = Gravity.CENTER
    setTextColor(if (filled) Theme.BG else Theme.TEXT)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    background = rounded(if (filled) Theme.ACCENT else Theme.CARD, dp(24).toFloat(), if (filled) null else Theme.LINE)
    setPadding(dp(20), dp(12), dp(20), dp(12))
    setOnClickListener { onClick() }
}

/** Big number + unit + sub-caption tile, laid out in a row by the caller. */
fun Context.metric(label: String, value: String, unit: String = "", sub: String? = null, subColor: Int = Theme.MUTED) =
    vbox().apply {
        addView(text(label, 12f, Theme.MUTED))
        addView(hbox().apply {
            gravity = Gravity.BOTTOM
            addView(text(value, 26f, Theme.TEXT, bold = true))
            if (unit.isNotEmpty()) addView(text(" $unit", 13f, Theme.MUTED))
        })
        sub?.let { addView(text(it, 12f, subColor)) }
    }

fun Context.row(vararg views: View) = hbox().apply {
    views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
}

/** Circular score gauge (0–100). */
class ScoreRing(ctx: Context, private val score: Int?, private val caption: String) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.LINE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(track).apply { color = Theme.ACCENT }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val small = Paint(big).apply { color = Theme.MUTED; typeface = Typeface.DEFAULT }

    override fun onMeasure(w: Int, h: Int) { val s = ctx().dp(132); setMeasuredDimension(s, s) }
    private fun ctx() = context

    override fun onDraw(c: Canvas) {
        val sw = width * 0.08f
        track.strokeWidth = sw; arc.strokeWidth = sw
        val r = RectF(sw, sw, width - sw, height - sw)
        c.drawArc(r, 135f, 270f, false, track)
        score?.let {
            arc.color = when { it >= 85 -> Theme.ACCENT; it >= 70 -> Theme.LIGHT; else -> Theme.WARN }
            c.drawArc(r, 135f, 270f * it.coerceIn(0, 100) / 100f, false, arc)
        }
        big.textSize = width * 0.28f; small.textSize = width * 0.1f
        c.drawText(score?.toString() ?: "–", width / 2f, height / 2f + big.textSize * 0.3f, big)
        c.drawText(caption, width / 2f, height / 2f + big.textSize * 0.3f + small.textSize * 1.6f, small)
    }
}

/** Line chart with a dashed baseline. */
class Sparkline(ctx: Context, private val values: List<Double>, private val color: Int = Theme.ACCENT,
                private val baseline: Double? = null, private val heightDp: Int = 72) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; this.color = color; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; this.color = Theme.MUTED; pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(heightDp))

    override fun onDraw(c: Canvas) {
        if (values.size < 2) return
        val lo = (values + listOfNotNull(baseline)).min(); val hi = (values + listOfNotNull(baseline)).max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 8f
        fun x(i: Int) = pad + (width - 2 * pad) * i / (values.size - 1)
        fun y(v: Double) = (height - pad - (height - 2 * pad) * ((v - lo) / span)).toFloat()
        val p = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path(p).apply { lineTo(x(values.lastIndex), height.toFloat()); lineTo(x(0), height.toFloat()); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        baseline?.let { c.drawLine(pad, y(it), width - pad, y(it), base) }
        c.drawPath(p, line)
        c.drawCircle(x(values.lastIndex), y(values.last()), 8f, Paint(line).apply { style = Paint.Style.FILL })
    }
}

/** Simple bar chart. */
class Bars(ctx: Context, private val values: List<Double>, private val color: Int = Theme.ACCENT, private val heightDp: Int = 80) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(heightDp))
    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val hi = values.max().takeIf { it > 0 } ?: 1.0
        val slot = width.toFloat() / values.size
        values.forEachIndexed { i, v ->
            val h = (height * (v / hi)).toFloat()
            c.drawRoundRect(RectF(i * slot + slot * 0.18f, height - h, (i + 1) * slot - slot * 0.18f, height.toFloat()), 6f, 6f, paint)
        }
    }
}

/** Hypnogram: stage codes 1=deep 2=light 3=rem 4=wake, drawn as stepped lanes. */
class Hypnogram(ctx: Context, private val stages: List<Int>) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(64))
    override fun onDraw(c: Canvas) {
        if (stages.isEmpty()) return
        val lane = height / 4f
        val w = width.toFloat() / stages.size
        stages.forEachIndexed { i, s ->
            val (row, color) = when (s) { 4 -> 0 to Theme.WAKE; 3 -> 1 to Theme.REM; 2 -> 2 to Theme.LIGHT; 1 -> 3 to Theme.DEEP; else -> return@forEachIndexed }
            p.color = color
            c.drawRect(i * w, row * lane + 2, (i + 1) * w + 0.5f, (row + 1) * lane - 2, p)
        }
    }
}
