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
import android.widget.RemoteViewsService
import android.widget.RemoteViewsService.RemoteViewsFactory
import android.widget.Toast
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Widgets {
    fun refresh(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx) ?: return
        AddWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, AddWidget::class.java)))
        ChartWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, ChartWidget::class.java)))
        PeopleWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, PeopleWidget::class.java)))
        WishWidget.update(ctx, m, m.getAppWidgetIds(ComponentName(ctx, WishWidget::class.java)))
    }

    fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Popup over the home screen (the app itself never opens). Started by the Quick add widget:
 * "+ Spent" / "+ Got" add a new entry; tapping a listed entry (extra "id") edits it.
 */
class QuickAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra("id")?.let { id -> Store.all(this).firstOrNull { it.id == id } }
        EntryDialog.show(this, existing, intent.getStringExtra("type") ?: "spent", onDismiss = { finish() }) { msg ->
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

fun netText(v: Double) = (if (v < 0) "−" else "") + money(abs(v), false)

/** Quick add: today's net + "+ Spent" / "+ Received" buttons. */
class AddWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_add)
            // Net = spent − received (negative means more came in than went out)
            val weekNet = recent(ctx).totals().net
            v.setTextViewText(R.id.week, "Net ${netText(weekNet)}")
            v.setTextColor(R.id.week, if (weekNet < 0) C.GREEN else C.TEXT)
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, Intent(ctx, RecentService::class.java))
            v.setEmptyView(R.id.list, R.id.empty)
            // Each row fills in its entry id, so a tap opens that entry's edit popup
            v.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 3,
                    Intent(ctx, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(R.id.add_spent, quickAdd(ctx, "spent", 1))
            v.setOnClickPendingIntent(R.id.add_received, quickAdd(ctx, "received", 2))
            v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx))
            m.updateAppWidget(ids, v)
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        /** Entries dated within the last 7 days (today included), newest first. */
        fun recent(ctx: Context): List<Expense> {
            val since = today().minusDays(6).toString()
            return Store.all(ctx).filter { it.date >= since }
        }

        private fun quickAdd(ctx: Context, type: String, req: Int) = PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, QuickAddActivity::class.java).putExtra("type", type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Feeds the Quick add widget's scrollable list. */
class RecentService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items = emptyList<Expense>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() { items = AddWidget.recent(applicationContext) }
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_row)
            val e = items.getOrNull(position) ?: return v
            v.setTextViewText(R.id.title, e.note.ifBlank { e.category }.capFirst())
            v.setTextViewText(R.id.sub, "${if (e.received) "Received" else e.category} · ${dayLabel(e.date)}")
            v.setTextViewText(R.id.amt, (if (e.received) "+" else "") + money(e.amount, false))
            v.setTextColor(R.id.amt, if (e.received) C.GREEN else C.TEXT)
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("id", e.id))
            return v
        }
    }
}

/** Bar graph of net spending (spent − received) for the last 6 months. */
class ChartWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, options: Bundle) =
        update(ctx, m, intArrayOf(id))

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val now = YearMonth.now()
            val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
            val byMonth = Store.all(ctx).groupBy { it.month }
            val values = months.map { byMonth[it.toString()].orEmpty().totals().net }
            val labels = months.map { it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

            for (id in ids) {
                val o = m.getAppWidgetOptions(id)
                val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 120
                val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
                v.setTextViewText(R.id.caption, "Net · ${labels.last()}")
                v.setTextViewText(R.id.total, netText(values.last()))
                v.setTextColor(R.id.total, if (values.last() < 0) C.GREEN else C.TEXT)
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
            val top = values.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0

            values.forEachIndexed { i, v ->
                val cx = slot * i + slot / 2
                val last = i == values.lastIndex
                val neg = v < 0
                val bh = max((abs(v) / top * chartH).toFloat(), 3 * d)
                val y = valueH + chartH - bh
                p.color = when {
                    neg -> if (last) C.GREEN else withAlpha(C.GREEN, 0x66)
                    last -> C.TEXT
                    else -> 0xFF3A3A3A.toInt()
                }
                c.drawRoundRect(cx - bw / 2, y, cx + bw / 2, valueH + chartH, 5 * d, 5 * d, p)
                if (v != 0.0) {
                    p.color = if (neg) C.GREEN else if (last) C.TEXT else C.MUTED
                    c.drawText((if (neg) "−" else "") + compact(abs(v)), cx, y - 4 * d, p)
                }
                p.color = if (last) C.TEXT else C.MUTED
                c.drawText(labels[i], cx, h - 3 * d, p)
            }
            return bmp
        }
    }
}

/**
 * Popup over the home screen for the People widget: "+ Gave" / "+ Got" / "Split" open their sheet;
 * tapping a listed person (extra "person") opens a new entry for them.
 */
class PeopleAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val done: (String) -> Unit = { msg -> Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
        if (intent.getBooleanExtra("split", false)) PeopleDialog.split(this, onDismiss = { finish() }, onSaved = done)
        else PeopleDialog.show(this, null, intent.getStringExtra("type") ?: "gave", intent.getStringExtra("person"), onDismiss = { finish() }, onSaved = done)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        Contacts.onResult(requestCode, grantResults)
}

/** People: overall balance, everyone with an open balance, and quick add buttons. */
class PeopleWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val people = Ledger.people(ctx)
            val owed = people.filter { it.balance > 0 }.sumOf { it.balance }
            val owe = people.filter { it.balance < 0 }.sumOf { -it.balance }
            val v = RemoteViews(ctx.packageName, R.layout.widget_people)
            v.setTextViewText(R.id.get, "Get ${money(owed, false)}")
            v.setTextViewText(R.id.owe, "Owe ${money(owe, false)}")
            v.setTextColor(R.id.owe, if (owe > 0) C.RED else C.MUTED)
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, Intent(ctx, PeopleService::class.java))
            v.setEmptyView(R.id.list, R.id.empty)
            v.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 13,
                    Intent(ctx, PeopleAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(R.id.add_gave, popup(ctx, 10) { putExtra("type", "gave") })
            v.setOnClickPendingIntent(R.id.add_got, popup(ctx, 11) { putExtra("type", "got") })
            v.setOnClickPendingIntent(R.id.split, popup(ctx, 12) { putExtra("split", true) })
            v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx))
            m.updateAppWidget(ids, v)
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        private fun popup(ctx: Context, req: Int, extras: Intent.() -> Unit) = PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, PeopleAddActivity::class.java).apply(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Feeds the People widget's list: everyone with an open balance. */
class PeopleService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items = emptyList<Person>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() { items = Ledger.people(applicationContext).filter { !it.settled } }
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_row)
            val p = items.getOrNull(position) ?: return v
            v.setTextViewText(R.id.title, p.name)
            v.setTextViewText(R.id.sub, "${if (p.balance > 0) "Owes you" else "You owe"} · ${dayLabel(p.lastDate)}")
            v.setTextViewText(R.id.amt, money(abs(p.balance), false))
            v.setTextColor(R.id.amt, if (p.balance > 0) C.GREEN else C.RED)
            // They owe you → most likely they're paying back; you owe → you're paying back
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("person", p.name).putExtra("type", if (p.balance > 0) "got" else "gave"))
            return v
        }
    }
}

/**
 * Popup over the home screen for the Wishlist widget: "+ Add item" adds; tapping a listed item
 * (extra "id") asks whether it was ordered and removes it.
 */
class WishActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val done: (String) -> Unit = { msg -> Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
        val item = intent.getStringExtra("id")?.let { id -> Wishlist.all(this).firstOrNull { it.id == id } }
        if (item != null) Wishlist.confirmOrdered(this, item, onDismiss = { finish() }, onDone = done)
        else Wishlist.showAdd(this, onDismiss = { finish() }, onSaved = done)
    }
}

/** Wishlist: items to order (important first), tap one once ordered, "+ Add item" to add. */
class WishWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val items = Wishlist.all(ctx)
            val important = items.count { it.important }
            val v = RemoteViews(ctx.packageName, R.layout.widget_wish)
            v.setTextViewText(R.id.count, "${items.size} to order" + if (important > 0) " · $important ★" else "")
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, Intent(ctx, WishService::class.java))
            v.setEmptyView(R.id.list, R.id.empty)
            v.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 21,
                    Intent(ctx, WishActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(
                R.id.add_item,
                PendingIntent.getActivity(
                    ctx, 20,
                    Intent(ctx, WishActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx))
            m.updateAppWidget(ids, v)
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}

/** Feeds the Wishlist widget's list. */
class WishService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items = emptyList<WishItem>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() { items = Wishlist.all(applicationContext) }
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_row)
            val w = items.getOrNull(position) ?: return v
            v.setTextViewText(R.id.title, w.name)
            v.setTextViewText(R.id.sub, (if (w.important) "Important · " else "") + "Added ${dayLabel(w.added)}")
            v.setTextViewText(R.id.amt, if (w.important) "★" else "")
            v.setTextColor(R.id.amt, C.AMBER)
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("id", w.id))
            return v
        }
    }
}
