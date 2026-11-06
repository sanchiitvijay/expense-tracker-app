package com.sanchit.expenses

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
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
        fun ids(cls: Class<*>) = m.getAppWidgetIds(ComponentName(ctx, cls))
        AddWidget.update(ctx, m, ids(AddWidget::class.java))
        ChartWidget.update(ctx, m, ids(ChartWidget::class.java))
        PeopleWidget.update(ctx, m, ids(PeopleWidget::class.java))
        ChecklistWidget.update(ctx, m, ids(ChecklistWidget::class.java))
        NoteWidget.update(ctx, m, ids(NoteWidget::class.java))
    }

    /** Opens the app; from a list widget, on that widget's tab and list. */
    fun openApp(ctx: Context, widgetId: Int = 0, tab: Tab? = null, list: String? = null): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).apply {
            if (tab != null) { putExtra("tab", tab.name).putExtra("list", list); tag(widgetId, "open") }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Makes an intent unique per widget and purpose, so two widgets' buttons and lists never share a PendingIntent or adapter. */
    fun Intent.tag(widgetId: Int, what: String): Intent = setData(Uri.parse("expenses-widget://$widgetId/$what"))

    /** A popup activity over the home screen. [mutable] for list templates, which rows fill in. */
    fun popup(ctx: Context, cls: Class<*>, widgetId: Int, what: String, mutable: Boolean = false, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, cls).apply(extras).tag(widgetId, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Adapter intent for one widget's scrolling list. */
    fun listIntent(ctx: Context, cls: Class<*>, widgetId: Int): Intent =
        Intent(ctx, cls).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).tag(widgetId, "list")

    /** The lists a widget can be pointed at, by the widget's kind; null for widgets without lists (People). */
    fun booksFor(ctx: Context, widgetId: Int): Books? = when (AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widgetId)?.provider?.className) {
        AddWidget::class.java.name, ChartWidget::class.java.name -> Store.books
        ChecklistWidget::class.java.name -> Checklists.books
        NoteWidget::class.java.name -> Notes.books
        else -> null
    }
}

/**
 * Asks which list a newly placed widget should show (and again when it's reconfigured from the home screen).
 * With only one list there's nothing to ask, so it's picked straight away.
 */
class WidgetListActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val books = if (id == AppWidgetManager.INVALID_APPWIDGET_ID) null else Widgets.booksFor(this, id)
        if (books == null) { finish(); return }
        val lists = books.all(this)
        fun pick(b: Book) {
            books.setWidget(this, id, b.id)
            Widgets.refresh(this)
            setResult(RESULT_OK, result)
        }
        if (lists.size == 1) { pick(lists[0]); finish(); return }
        AlertDialog.Builder(this).setTitle("Which list should this widget show?")
            .setSingleChoiceItems(lists.map { it.name }.toTypedArray(), lists.indexOf(books.forWidget(this, id))) { d, i -> pick(lists[i]); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .show()
    }
}

/**
 * Popup over the home screen (the app itself never opens). Started by the Quick add widget:
 * "+ Spent" / "+ Got" add a new entry to the widget's list (extra "book"); tapping a listed entry (extra "id") edits it.
 */
class QuickAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val book = Store.books.get(this, intent.getStringExtra("book")).id
        val existing = intent.getStringExtra("id")?.let { id -> Store.all(this, book).firstOrNull { it.id == id } }
        EntryDialog.show(this, book, existing, intent.getStringExtra("type") ?: "spent", onDismiss = { finish() }) { msg ->
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

fun netText(v: Double) = (if (v < 0) "−" else "") + money(abs(v), false)

/** Quick add, for one expense list: last 7 days' entries and net + "+ Spent" / "+ Received" buttons. */
class AddWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Books.forgetWidget(ctx, it) }

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            for (id in ids) {
                val book = Store.books.forWidget(ctx, id)
                val v = RemoteViews(ctx.packageName, R.layout.widget_add)
                v.setTextViewText(R.id.label, Store.books.label(ctx, book, "Last 7 days", " · 7 days"))
                // Net = spent − received (negative means more came in than went out)
                val weekNet = recent(ctx, book.id).totals().net
                v.setTextViewText(R.id.week, "Net ${netText(weekNet)}")
                v.setTextColor(R.id.week, if (weekNet < 0) C.GREEN else C.TEXT)
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.list, Widgets.listIntent(ctx, RecentService::class.java, id))
                v.setEmptyView(R.id.list, R.id.empty)
                // Each row fills in its entry id, so a tap opens that entry's edit popup
                v.setPendingIntentTemplate(R.id.list, Widgets.popup(ctx, QuickAddActivity::class.java, id, "edit", mutable = true) { putExtra("book", book.id) })
                v.setOnClickPendingIntent(R.id.add_spent, Widgets.popup(ctx, QuickAddActivity::class.java, id, "spent") { putExtra("book", book.id).putExtra("type", "spent") })
                v.setOnClickPendingIntent(R.id.add_received, Widgets.popup(ctx, QuickAddActivity::class.java, id, "received") { putExtra("book", book.id).putExtra("type", "received") })
                v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx, id, Tab.EXPENSES, book.id))
                m.updateAppWidget(id, v)
            }
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        /** Entries of [book] dated within the last 7 days (today included), newest first. */
        fun recent(ctx: Context, book: String): List<Expense> {
            val since = today().minusDays(6).toString()
            return Store.all(ctx, book).filter { it.date >= since }
        }
    }
}

/** Feeds a Quick add widget's scrollable list. */
class RecentService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        private var items = emptyList<Expense>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() { items = AddWidget.recent(applicationContext, Store.books.forWidget(applicationContext, widgetId).id) }
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

/** Bar graph of net spending (spent − received) of one expense list for the last 6 months. */
class ChartWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Books.forgetWidget(ctx, it) }

    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, options: Bundle) =
        update(ctx, m, intArrayOf(id))

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val now = YearMonth.now()
            val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
            val labels = months.map { it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

            for (id in ids) {
                val book = Store.books.forWidget(ctx, id)
                val byMonth = Store.all(ctx, book.id).groupBy { it.month }
                val values = months.map { byMonth[it.toString()].orEmpty().totals().net }
                val o = m.getAppWidgetOptions(id)
                val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 120
                val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
                v.setTextViewText(R.id.caption, "${Store.books.label(ctx, book, "Net")} · ${labels.last()}")
                v.setTextViewText(R.id.total, netText(values.last()))
                v.setTextColor(R.id.total, if (values.last() < 0) C.GREEN else C.TEXT)
                v.setImageViewBitmap(R.id.chart, draw(ctx, labels, values, wDp - 28, max(hDp - 58, 40)))
                v.setOnClickPendingIntent(R.id.root, Widgets.openApp(ctx, id, Tab.REPORTS, book.id))
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

/** Popup over the home screen for a Checklist widget's "+ Add" (extra "list"). */
class ChecklistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = Checklists.books.get(this, intent.getStringExtra("list")).id
        Checklists.showAdd(this, list, onDismiss = { finish() }) { msg -> Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}

/** One checklist: its items, still-to-do first; tap an item to tick it off (or back on), "+ Add" to add. */
class ChecklistWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Books.forgetWidget(ctx, it) }

    /** A tapped row ticks its item straight away, without opening anything. */
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == TOGGLE) {
            val list = intent.getStringExtra("list")
            val id = intent.getStringExtra("id")
            if (list != null && id != null && Checklists.books.has(ctx, list)) Checklists.toggle(ctx, list, id)
        } else super.onReceive(ctx, intent)
    }

    companion object {
        private const val TOGGLE = "com.sanchit.expenses.TOGGLE"

        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            for (id in ids) {
                val book = Checklists.books.forWidget(ctx, id)
                val items = Checklists.all(ctx, book.id)
                val done = items.count { it.done }
                val v = RemoteViews(ctx.packageName, R.layout.widget_checklist)
                v.setTextViewText(R.id.label, book.name)
                v.setTextViewText(R.id.count, when {
                    items.isEmpty() -> ""
                    done == items.size -> "All done"
                    else -> "${items.size - done} to do" + if (done > 0) " · $done done" else ""
                })
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.list, Widgets.listIntent(ctx, ChecklistService::class.java, id))
                v.setEmptyView(R.id.list, R.id.empty)
                v.setPendingIntentTemplate(
                    R.id.list,
                    PendingIntent.getBroadcast(
                        ctx, 0,
                        with(Widgets) { Intent(ctx, ChecklistWidget::class.java).setAction(TOGGLE).putExtra("list", book.id).tag(id, "toggle") },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                v.setOnClickPendingIntent(R.id.add_item, Widgets.popup(ctx, ChecklistActivity::class.java, id, "add") { putExtra("list", book.id) })
                v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx, id, Tab.CHECKLISTS, book.id))
                m.updateAppWidget(id, v)
            }
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}

/** Feeds a Checklist widget's list. */
class ChecklistService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        private var items = emptyList<CheckItem>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() { items = Checklists.all(applicationContext, Checklists.books.forWidget(applicationContext, widgetId).id) }
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_check_row)
            val w = items.getOrNull(position) ?: return v
            v.setTextViewText(R.id.check, if (w.done) "✓" else "○")
            v.setTextColor(R.id.check, if (w.done) C.GREEN else C.MUTED)
            v.setTextViewText(R.id.title, if (w.done) struck(w.name) else w.name)
            v.setTextColor(R.id.title, if (w.done) C.DIM else C.TEXT)
            v.setTextViewText(R.id.star, if (w.important && !w.done) "★" else "")
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("id", w.id))
            return v
        }
    }
}

fun struck(s: String) = android.text.SpannableString(s).apply {
    setSpan(android.text.style.StrikethroughSpan(), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Popup over the home screen editing a Note widget's note (extra "list"). */
class NoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoteEditor.showSheet(this, Notes.books.get(this, intent.getStringExtra("list")).id) { finish() }
    }
}

/** One note's text (with its bold) on the home screen; tap it to edit in a popup. */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Books.forgetWidget(ctx, it) }

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            for (id in ids) {
                val book = Notes.books.forWidget(ctx, id)
                val v = RemoteViews(ctx.packageName, R.layout.widget_note)
                v.setTextViewText(R.id.label, book.name)
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.list, Widgets.listIntent(ctx, NoteService::class.java, id))
                v.setEmptyView(R.id.list, R.id.empty)
                val edit = Widgets.popup(ctx, NoteActivity::class.java, id, "edit") { putExtra("list", book.id) }
                v.setPendingIntentTemplate(R.id.list, Widgets.popup(ctx, NoteActivity::class.java, id, "edit-line", mutable = true) { putExtra("list", book.id) })
                v.setOnClickPendingIntent(R.id.empty, edit)
                v.setOnClickPendingIntent(R.id.edit, edit)
                v.setOnClickPendingIntent(R.id.header, Widgets.openApp(ctx, id, Tab.NOTES, book.id))
                m.updateAppWidget(id, v)
            }
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}

/** Feeds a Note widget: one row per line, so long notes scroll. */
class NoteService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        private var lines = emptyList<CharSequence>()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() {
            val note = Notes.get(applicationContext, Notes.books.forWidget(applicationContext, widgetId).id)
            if (note.isBlank) { lines = emptyList(); return }
            val text = RichText.spanned(note)
            val out = ArrayList<CharSequence>()
            var start = 0
            while (start <= text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                out += text.subSequence(start, end)
                start = end + 1
            }
            lines = out.dropLastWhile { it.isBlank() }
        }
        override fun getCount() = lines.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_note_line)
            v.setTextViewText(R.id.line, lines.getOrNull(position)?.takeIf { it.isNotEmpty() } ?: " ")
            v.setOnClickFillInIntent(R.id.line, Intent())
            return v
        }
    }
}
