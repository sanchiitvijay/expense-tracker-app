package com.sanchit.expenses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Lightweight canvas charts — no chart library needed. */
abstract class ChartView(ctx: Context, private val heightDp: Int) : View(ctx) {
    protected val d = resources.displayMetrics.density
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; color = C.MUTED; textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (heightDp * d).toInt())

    protected fun gridLine(c: Canvas, y: Float) {
        p.style = Paint.Style.FILL
        p.color = 0x12FFFFFF
        c.drawRect(0f, y, width.toFloat(), y + d, p)
    }
}

/**
 * Grouped vertical bars. `series[i][j]` is series i at label j.
 * [highlight] draws that column brighter; blank labels are skipped.
 */
class BarChart(
    ctx: Context,
    private val labels: List<String>,
    private val series: List<List<Double>>,
    private val colors: List<Int>,
    private val highlight: Int = -1,
    private val showValues: Boolean = true,
    heightDp: Int = 150,
) : ChartView(ctx, heightDp) {
    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n == 0) return
        val labelH = 16 * d
        val valueH = if (showValues) 14 * d else 4 * d
        val base = height - labelH
        val chartH = base - valueH
        val top = series.flatten().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = width / n.toFloat()
        val groupW = min(slot * 0.72f, 22 * d * series.size)
        val bw = groupW / series.size
        val r = min(bw / 2.5f, 5 * d)
        gridLine(c, base)

        for (j in 0 until n) {
            val dim = highlight >= 0 && j != highlight
            series.forEachIndexed { i, s ->
                val v = s[j]
                if (v <= 0) return@forEachIndexed
                val x0 = slot * j + (slot - groupW) / 2 + bw * i
                val bh = max((v / top * chartH).toFloat(), 2 * d)
                p.color = if (dim) withAlpha(colors[i], 0x66) else colors[i]
                c.drawRoundRect(x0 + d * 0.75f, base - bh, x0 + bw - d * 0.75f, base, r, r, p)
                if (showValues && series.size == 1) {
                    tp.color = if (dim) C.DIM else C.TEXT
                    c.drawText(compact(v), x0 + bw / 2, base - bh - 4 * d, tp)
                }
            }
            if (labels[j].isNotEmpty()) {
                tp.color = if (j == highlight) C.TEXT else C.MUTED
                c.drawText(labels[j], slot * j + slot / 2, height - 3 * d, tp)
            }
        }
    }
}

/** Ring chart with a centre caption. */
class Donut(
    ctx: Context,
    private val slices: List<Pair<Double, Int>>,
    private val title: String,
    private val sub: String,
    private val sizeDp: Int = 132,
) : ChartView(ctx, sizeDp) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((sizeDp * d).toInt(), (sizeDp * d).toInt())

    override fun onDraw(c: Canvas) {
        val stroke = 16 * d
        val rect = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.color = C.CARD2
        c.drawArc(rect, 0f, 360f, false, p)
        val total = slices.sumOf { it.first }
        if (total > 0) {
            var start = -90f
            val gap = if (slices.size > 1) 2f else 0f
            for ((v, color) in slices) {
                val sweep = (v / total * 360).toFloat()
                p.color = color
                c.drawArc(rect, start + gap / 2, max(sweep - gap, 0.5f), false, p)
                start += sweep
            }
        }
        p.style = Paint.Style.FILL
        tp.textSize = 15 * d
        tp.color = C.TEXT
        tp.isFakeBoldText = true
        c.drawText(title, width / 2f, height / 2f + 2 * d, tp)
        tp.isFakeBoldText = false
        tp.textSize = 10 * d
        tp.color = C.MUTED
        c.drawText(sub, width / 2f, height / 2f + 16 * d, tp)
    }
}

/** Cumulative lines over the days of a month. Shorter series stop early (e.g. the current month). */
class LineChart(
    ctx: Context,
    private val series: List<List<Double>>,
    private val colors: List<Int>,
    private val days: Int,
    heightDp: Int = 150,
) : ChartView(ctx, heightDp) {
    override fun onDraw(c: Canvas) {
        val labelH = 16 * d
        val topPad = 8 * d
        val base = height - labelH
        val chartH = base - topPad
        val top = series.flatten().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val step = width / max(days - 1, 1).toFloat()
        for (k in 0..2) gridLine(c, base - chartH * k / 2)
        tp.textAlign = Paint.Align.LEFT
        tp.color = C.DIM
        c.drawText(compact(top), 0f, topPad + 2 * d, tp)

        series.forEachIndexed { i, s ->
            if (s.isEmpty()) return@forEachIndexed
            val path = Path()
            s.forEachIndexed { k, v ->
                val x = min(k * step, width - 2 * d)
                val y = base - (v / top * chartH).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.strokeJoin = Paint.Join.ROUND
            p.color = colors[i]
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
            c.drawCircle(min((s.size - 1) * step, width - 4 * d), base - (s.last() / top * chartH).toFloat(), 4 * d, p)
        }

        tp.color = C.MUTED
        tp.textAlign = Paint.Align.LEFT
        c.drawText("1", 0f, height - 3 * d, tp)
        tp.textAlign = Paint.Align.CENTER
        c.drawText("15", 14 * step, height - 3 * d, tp)
        tp.textAlign = Paint.Align.RIGHT
        c.drawText("$days", width.toFloat(), height - 3 * d, tp)
        tp.textAlign = Paint.Align.CENTER
    }
}
