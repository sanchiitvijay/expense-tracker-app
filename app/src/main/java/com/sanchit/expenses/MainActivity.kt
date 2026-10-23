package com.sanchit.expenses

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var tab = 0
    private val openMonths = mutableSetOf(YearMonth.now().toString())
    private val openReports = mutableSetOf(YearMonth.now().toString())
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var tabViews: List<TextView>

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val column = vbox()

        val header = hbox()
        title = text("Expenses", 21f, C.TEXT, 2)
        header.addView(title, lp(0, WRAP, 1f))
        val seg = hbox().apply { background = bg(C.CARD, dp(12f), dp(1), C.LINE); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        tabViews = listOf("Expenses", "Reports").mapIndexed { i, s ->
            text(s, 13f, C.MUTED, 1).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { tab = i; render() }
            }.also { seg.addView(it) }
        }
        header.addView(seg)
        header.addView(text("⋮", 22f, C.MUTED, 2).apply {
            setPadding(dp(14), dp(2), dp(4), dp(2))
            setOnClickListener { showMenu(it) }
        })
        column.addView(header, lp(bottom = dp(18)))
        content = vbox()
        column.addView(content)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })

        val fab = text("+  Add", 15f, C.BG, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            background = pressable(bg(C.TEXT, dp(28f)))
            elevation = dp(6f)
            setOnClickListener { openForm(null) }
        }
        val fabLp = FrameLayout.LayoutParams(WRAP, dp(54), Gravity.BOTTOM or Gravity.END)
        root.addView(fab, fabLp)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.systemWindowInsetTop
            val bottom = insets.systemWindowInsetBottom
            column.setPadding(dp(16), top + dp(14), dp(16), bottom + dp(110))
            fabLp.setMargins(0, 0, dp(20), bottom + dp(22))
            fab.layoutParams = fabLp
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        Widgets.refresh(this) // keeps "today" figures fresh after midnight
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openForm(e: Expense?) = EntryDialog.show(this, e) { msg -> toast(msg); render() }

    private fun render() {
        tabViews.forEachIndexed { i, v ->
            v.setTextColor(if (i == tab) C.TEXT else C.MUTED)
            v.background = if (i == tab) bg(C.CARD2, dp(9f)) else null
        }
        title.text = if (tab == 0) "Expenses" else "Reports"
        content.removeAllViews()
        val list = Store.all(this)
        if (tab == 0) renderExpenses(list) else renderReports(list)
    }

    // ── Expenses tab ──
    private fun renderExpenses(list: List<Expense>) {
        val ym = YearMonth.now().toString()
        val t = list.filter { it.month == ym }.totals()

        val hero = vbox().apply { background = bg(C.CARD, dp(22f), dp(1), C.LINE); setPadding(dp(18), dp(16), dp(18), dp(18)) }
        hero.addView(caps("Net · ${monthLabel(ym)}"))
        hero.addView(text(signed(t.net), 30f, if (t.net < 0) C.GREEN else C.TEXT, 2), lp(top = dp(4)))
        hero.addView(text(if (t.net >= 0) "Net expenses this month" else "You received more than you spent", 12f, C.DIM))
        content.addView(hero, lp(bottom = dp(10)))

        val stats = hbox()
        stats.addView(stat("Spent", money(t.spent), C.TEXT), lp(0, WRAP, 1f, end = dp(5)))
        stats.addView(stat("Received", money(t.received), C.GREEN), lp(0, WRAP, 1f, start = dp(5)))
        content.addView(stats, lp(bottom = dp(24)))

        content.addView(text("All entries", 15f, C.TEXT, 2), lp(bottom = dp(10)))
        if (list.isEmpty()) {
            content.addView(empty("No entries yet.\nTap + Add, or add the Quick add widget to your home screen."))
            return
        }
        for ((month, items) in list.groupBy { it.month }) {
            val card = monthCard(month, items, openMonths)
            if (month in openMonths) for ((day, dayItems) in items.groupBy { it.date }) {
                val dh = hbox().apply { setPadding(dp(16), dp(12), dp(16), dp(2)) }
                dh.addView(text(dayLabel(day), 11.5f, C.MUTED, 1), lp(0, WRAP, 1f))
                val ds = dayItems.totals().spent
                if (ds > 0) dh.addView(text(money(ds, false), 11.5f, C.DIM))
                card.addView(dh)
                dayItems.forEach { card.addView(row(it)) }
            }
            content.addView(card, lp(bottom = dp(10)))
        }
    }

    private fun row(e: Expense): View {
        val r = hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pressable()
            setOnClickListener { openForm(e) }
            setOnLongClickListener { confirmDelete(e); true }
        }
        val color = if (e.received) C.GREEN else catColor(e.category)
        r.addView(text(if (e.received) "↑" else e.category.take(1).uppercase(), 14f, color, 2).apply {
            gravity = Gravity.CENTER
            background = bg(withAlpha(color, 0x24), dp(12f))
        }, lp(dp(36), dp(36), end = dp(12)))
        val mid = vbox()
        mid.addView(text(e.note.ifBlank { e.category }.capFirst(), 14.5f, C.TEXT, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(text(if (e.received) "Received" else e.category, 11f, color, 1), lp(WRAP, WRAP, top = dp(2)))
        r.addView(mid, lp(0, WRAP, 1f))
        r.addView(text((if (e.received) "+" else "") + money(e.amount, false), 14.5f, if (e.received) C.GREEN else C.TEXT, 2), lp(WRAP, WRAP, start = dp(8)))
        return r
    }

    private fun confirmDelete(e: Expense) {
        AlertDialog.Builder(this).setTitle("Delete this entry?")
            .setMessage("${e.note.ifBlank { e.category }.capFirst()} · ${money(e.amount, false)} · ${dayLabel(e.date)}")
            .setPositiveButton("Delete") { _, _ -> Store.delete(this, e.id); toast("Entry deleted"); render() }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Reports tab ──
    private fun renderReports(list: List<Expense>) {
        content.addView(text("Monthly breakdown — tap a month for details", 12f, C.DIM), lp(bottom = dp(12)))
        if (list.isEmpty()) { content.addView(empty("No data yet")); return }
        for ((month, items) in list.groupBy { it.month }) {
            val card = monthCard(month, items, openReports)
            if (month in openReports) {
                val t = items.totals()
                val body = vbox().apply { setPadding(dp(14), dp(14), dp(14), dp(16)) }
                val stats = hbox()
                listOf(
                    Triple("Spent", money(t.spent), C.TEXT),
                    Triple("Received", money(t.received), C.GREEN),
                    Triple("Net", signed(t.net), if (t.net < 0) C.GREEN else C.TEXT),
                ).forEachIndexed { i, (l, v, c) ->
                    stats.addView(stat(l, v, c, small = true), lp(0, WRAP, 1f, start = if (i > 0) dp(3) else 0, end = if (i < 2) dp(3) else 0))
                }
                body.addView(stats)

                val cats = items.filter { !it.received }.groupBy { it.category }
                    .mapValues { it.value.sumOf { e -> e.amount } }.entries.sortedByDescending { it.value }
                if (cats.isNotEmpty()) {
                    body.addView(caps("By category"), lp(top = dp(18)))
                    val max = cats.first().value
                    for ((cat, amt) in cats) {
                        val line = hbox()
                        line.addView(text(cat, 12.5f, C.TEXT), lp(0, WRAP, 1f))
                        line.addView(text("${(amt / t.spent * 100).roundToInt()}%", 11f, C.DIM), lp(WRAP, WRAP, end = dp(8)))
                        line.addView(text(money(amt), 12.5f, C.TEXT, 1))
                        body.addView(line, lp(top = dp(10)))
                        val frac = (amt / max).toFloat().coerceIn(0.02f, 1f)
                        val track = hbox().apply { background = bg(C.CARD2, dp(3f)) }
                        track.addView(View(this).apply { background = bg(catColor(cat), dp(3f)) }, lp(0, dp(6), frac))
                        track.addView(View(this), lp(0, dp(6), 1f - frac))
                        body.addView(track, lp(top = dp(5)))
                    }
                }
                card.addView(body)
            }
            content.addView(card, lp(bottom = dp(10)))
        }
    }

    // ── Shared bits ──
    private fun signed(v: Double) = (if (v < 0) "−" else "") + money(abs(v))

    private fun caps(s: String) = text(s.uppercase(), 10.5f, C.MUTED, 1).apply { letterSpacing = 0.08f }

    private fun stat(label: String, value: String, color: Int, small: Boolean = false) = vbox().apply {
        background = bg(C.CARD, dp(if (small) 12f else 18f), dp(1), C.LINE)
        setPadding(dp(if (small) 10 else 16), dp(12), dp(8), dp(12))
        addView(caps(label))
        addView(text(value, if (small) 13f else 19f, color, 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = dp(3)))
    }

    private fun empty(msg: String) = text(msg, 13f, C.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(40), dp(20), dp(40))
        background = bg(C.SURFACE, dp(18f), dp(1), C.LINE)
    }

    private fun monthCard(month: String, items: List<Expense>, open: MutableSet<String>): LinearLayout {
        val card = vbox().apply { background = bg(C.SURFACE, dp(18f), dp(1), C.LINE); clipToOutline = true }
        val h = hbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = pressable(android.graphics.drawable.ColorDrawable(C.CARD))
            setOnClickListener { if (!open.remove(month)) open.add(month); render() }
        }
        h.addView(text(if (month in open) "▾" else "▸", 13f, C.MUTED), lp(WRAP, WRAP, end = dp(10)))
        h.addView(text(monthLabel(month), 14.5f, C.TEXT, 2))
        h.addView(text("${items.size}", 11f, C.MUTED).apply { setPadding(dp(6), dp(1), dp(6), dp(1)); background = bg(C.CARD2, dp(6f)) }, lp(WRAP, WRAP, start = dp(8)))
        h.addView(View(this), lp(0, 1, 1f))
        h.addView(text(money(items.totals().spent), 14f, C.TEXT, 2))
        card.addView(h)
        return card
    }

    // ── Menu: export / import / purge ──
    private fun showMenu(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(0, REQ_EXPORT_JSON, 0, "Export JSON backup")
        menu.add(0, REQ_EXPORT_CSV, 1, "Export CSV")
        menu.add(0, REQ_IMPORT, 2, "Import JSON / CSV")
        menu.add(0, 99, 3, "Purge data…")
        setOnMenuItemClickListener {
            when (it.itemId) {
                REQ_EXPORT_JSON -> export(true)
                REQ_EXPORT_CSV -> export(false)
                REQ_IMPORT -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")),
                    REQ_IMPORT,
                )
                else -> purgeMenu()
            }
            true
        }
        show()
    }

    private fun export(json: Boolean) {
        if (Store.all(this).isEmpty()) { toast("Nothing to export"); return }
        val ext = if (json) "json" else "csv"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (json) "application/json" else "text/csv")
                .putExtra(Intent.EXTRA_TITLE, "expenses-${today()}.$ext"),
            if (json) REQ_EXPORT_JSON else REQ_EXPORT_CSV,
        )
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_EXPORT_JSON, REQ_EXPORT_CSV -> {
                    val list = Store.all(this)
                    val out = if (requestCode == REQ_EXPORT_JSON) Backup.toJson(list) else Backup.toCsv(list)
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(out.toByteArray(Charsets.UTF_8)) }
                    toast("Exported ${list.size} entries")
                }
                REQ_IMPORT -> {
                    val txt = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    previewImport(Backup.parse(txt))
                }
            }
        } catch (e: Exception) {
            toast(e.message ?: "Something went wrong")
        }
    }

    private fun previewImport(p: Backup.Parsed) {
        val ids = Store.all(this).mapTo(HashSet()) { it.id }
        val fresh = p.valid.count { it.id.isBlank() || it.id !in ids }
        val b = AlertDialog.Builder(this).setTitle("Import entries")
            .setMessage("New: $fresh\nAlready here: ${p.valid.size - fresh}\nInvalid rows: ${p.invalid}\n\nEntries that already exist are skipped.")
        if (fresh > 0) b.setPositiveButton("Import $fresh") { _, _ ->
            toast("Imported ${Store.import(this, p)} entries")
            render()
        }.setNegativeButton("Cancel", null) else b.setPositiveButton("OK", null)
        b.show()
    }

    private fun purgeMenu() {
        if (Store.all(this).isEmpty()) { toast("Nothing to purge"); return }
        AlertDialog.Builder(this).setTitle("Purge data")
            .setItems(arrayOf("Select months…", "Date range…")) { _, w -> if (w == 0) purgeMonths() else purgeRange() }
            .show()
    }

    private fun purgeMonths() {
        val groups = Store.all(this).groupBy { it.month }.entries.toList()
        val labels = groups.map { (m, l) -> "${monthLabel(m)}  ·  ${l.size} entries  ·  ${money(l.totals().spent, false)}" }.toTypedArray()
        val checked = BooleanArray(groups.size)
        AlertDialog.Builder(this).setTitle("Purge months")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Next") { _, _ ->
                val months = groups.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
                val n = groups.filter { it.key in months }.sumOf { it.value.size }
                if (n > 0) confirmPurge(n, "${months.size} month(s)") { it.month in months }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun purgeRange() {
        val list = Store.all(this)
        var from = parseDate(list.minOf { it.date }) ?: today()
        var to = YearMonth.now().minusMonths(1).atEndOfMonth() // default: everything before this month
        if (to < from) to = today()
        fun count() = list.count { it.date >= from.toString() && it.date <= to.toString() }

        val box = vbox().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val fromBtn = chip("").apply { gravity = Gravity.START; styleChip(this, false); setTextColor(C.TEXT) }
        val toBtn = chip("").apply { gravity = Gravity.START; styleChip(this, false); setTextColor(C.TEXT) }
        val info = text("", 13f, C.MUTED)
        box.addView(fromBtn, lp(bottom = dp(8)))
        box.addView(toBtn, lp(bottom = dp(12)))
        box.addView(info)

        var dialog: AlertDialog? = null
        fun refresh() {
            fromBtn.text = "From    ${prettyDate(from)}"
            toBtn.text = "To        ${prettyDate(to)}"
            val n = count()
            info.text = if (from > to) "Start date is after the end date" else "$n entries will be deleted"
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = n > 0 && from <= to
        }
        fromBtn.setOnClickListener { pickDate(from) { from = it; refresh() } }
        toBtn.setOnClickListener { pickDate(to) { to = it; refresh() } }
        dialog = AlertDialog.Builder(this).setTitle("Purge date range").setView(box)
            .setPositiveButton("Purge") { _, _ ->
                val f = from.toString()
                val t = to.toString()
                confirmPurge(count(), "${prettyDate(from)} – ${prettyDate(to)}") { it.date in f..t }
            }
            .setNegativeButton("Cancel", null).show()
        refresh()
    }

    private fun confirmPurge(n: Int, what: String, predicate: (Expense) -> Boolean) {
        AlertDialog.Builder(this).setTitle("Delete $n entries?")
            .setMessage("This permanently removes every entry in $what.\n\nExport a backup first if you might need them later.")
            .setPositiveButton("Purge") { _, _ -> toast("Purged ${Store.purge(this, predicate)} entries"); render() }
            .setNeutralButton("Export first") { _, _ -> export(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private companion object {
        const val REQ_EXPORT_JSON = 1
        const val REQ_EXPORT_CSV = 2
        const val REQ_IMPORT = 3
    }
}
