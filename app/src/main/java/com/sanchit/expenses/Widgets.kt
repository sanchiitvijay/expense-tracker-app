package com.sanchit.expenses

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object Widgets {
    fun refresh(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx) ?: return
        AddWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, AddWidget::class.java)))
        ChartWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, ChartWidget::class.java)))
    }

    fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}

/** Popup over the home screen (the app itself never opens). Started by the Quick add widget. */
class QuickAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EntryDialog.show(this, null, intent.getStringExtra("type") ?: "spent", onDismiss = { finish() }) { msg ->
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Quick add: today's spending + "+ Spent" / "+ Received" buttons. */
class AddWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val list = Store.all(ctx)
            val day = today().toString()
            val ym = day.take(7)
            val v = RemoteViews(ctx.packageName, R.layout.widget_add)
            v.setTextViewText(R.id.today, money(list.filter { it.date == day }.totals().spent, false))
            v.setTextViewText(R.id.month, "${YearMonth.now().month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} · ${money(list.filter { it.month == ym }.totals().spent, false)}")
            v.setOnClickPendingIntent(R.id.add_spent, quickAdd(ctx, "spent", 1))
            v.setOnClickPendingIntent(R.id.add_received, quickAdd(ctx, "received", 2))
            v.setOnClickPendingIntent(R.id.summary, Widgets.openApp(ctx))
            m.updateAppWidget(ids, v)
        }

        private fun quickAdd(ctx: Context, type: String, req: Int) = PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, QuickAddActivity::class.java).putExtra("type", type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Bar graph of total spending for the last 6 months. */
class ChartWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, options: Bundle) =
        update(ctx, m, intArrayOf(id))

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val now = YearMonth.now()
            val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
            val spent = Store.all(ctx).filter { !it.received }.groupBy { it.month }.mapValues { it.value.sumOf { e -> e.amount } }
            val values = months.map { spent[it.toString()] ?: 0.0 }
            val labels = months.map { it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

            for (id in ids) {
                val o = m.getAppWidgetOptions(id)
                val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 120
                val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
                v.setTextViewText(R.id.caption, "Spent · ${labels.last()}")
                v.setTextViewText(R.id.total, money(values.last(), false))
                v.setImageViewBitmap(R.id.chart, draw(ctx, labels, values, wDp - 28, max(hDp - 58, 40)))
                v.setOnClickPendingIntent(R.id.root, Widgets.openApp(ctx))
                m.updateAppWidget(id, v)
            }
        }

        private fun draw(ctx: Context, labels: List<String>, values: List<Double>, wDp: Int, hDp: Int): Bitmap {
            // Cap the bitmap size so it stays well under the widget IPC limit
            val density = ctx.resources.displayMetrics.density
            val d = density * min(1f, min(900f / (wDp * density), 500f / (hDp * density)))
            val w = max(1, (wDp * d).toInt())
            val h = max(1, (hDp * d).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10 * d }

            val labelH = 15 * d
            val valueH = 14 * d
            val chartH = h - labelH - valueH
            val slot = w / values.size.toFloat()
            val bw = min(slot * 0.58f, 34 * d)
            val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

            values.forEachIndexed { i, v ->
                val cx = slot * i + slot / 2
                val last = i == values.lastIndex
                val bh = max((v / top * chartH).toFloat(), 3 * d)
                val y = valueH + chartH - bh
                p.color = if (last) C.TEXT else 0xFF3A3A3A.toInt()
                c.drawRoundRect(cx - bw / 2, y, cx + bw / 2, valueH + chartH, 5 * d, 5 * d, p)
                if (v > 0) {
                    p.color = if (last) C.TEXT else C.MUTED
                    c.drawText(compact(v), cx, y - 4 * d, p)
                }
                p.color = if (last) C.TEXT else C.MUTED
                c.drawText(labels[i], cx, h - 3 * d, p)
            }
            return bmp
        }
    }
}
