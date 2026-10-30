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
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var tab = Tab.EXPENSES
    private var settingsOpen = false
    private var openPerson: String? = null // person key shown on the People tab, null = list
    private val openMonths = mutableSetOf(YearMonth.now().toString())
    private val openReports = mutableSetOf(YearMonth.now().toString())
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var seg: LinearLayout
    private lateinit var fab: TextView

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
        header.addView(text("⋮", 22f, C.MUTED, 2).apply {
            setPadding(dp(14), dp(2), dp(4), dp(2))
            setOnClickListener { showMenu(it) }
        })
        column.addView(header, lp(bottom = dp(12)))
        seg = hbox().apply { background = bg(C.CARD, dp(12f), dp(1), C.LINE); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        column.addView(seg, lp(bottom = dp(18)))
        content = vbox()
        column.addView(content)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })

        fab = text("+  Add", 15f, C.BG, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            background = pressable(bg(C.TEXT, dp(28f)))
            elevation = dp(6f)
            setOnClickListener {
                when (tab) {
                    Tab.PEOPLE -> openDebt(null, "gave")
                    Tab.WISHLIST -> Wishlist.showAdd(this@MainActivity) { msg -> toast(msg); render() }
                    Tab.IDEAS -> Ideas.showAdd(this@MainActivity) { msg -> toast(msg); render() }
                    else -> openForm(null)
                }
            }
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

    private fun openDebt(d: Debt?, type: String) =
        PeopleDialog.show(this, d, type, person = openPersonName()) { msg -> toast(msg); render() }

    private fun openPersonName() = openPerson?.let { k -> Ledger.people(this).firstOrNull { it.key == k }?.name }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        Contacts.onResult(requestCode, grantResults)

    private fun render() {
        val visible = Settings.visibleTabs(this)
        if (tab !in visible) { tab = visible.firstOrNull() ?: Tab.EXPENSES; openPerson = null }
        seg.removeAllViews()
        for (t in visible) {
            val active = t == tab && !settingsOpen
            seg.addView(text(t.label, if (visible.size > 4) 12f else 13f, if (active) C.TEXT else C.MUTED, 1).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(7), 0, dp(7))
                background = if (active) bg(C.CARD2, dp(9f)) else null
                setOnClickListener { tab = t; openPerson = null; settingsOpen = false; render() }
            }, lp(0, WRAP, 1f))
        }
        title.text = if (settingsOpen) "Settings" else tab.label
        fab.visibility = if (settingsOpen) View.GONE else View.VISIBLE
        content.removeAllViews()
        if (settingsOpen) renderSettings() else when (tab) {
            Tab.EXPENSES -> renderExpenses(Store.all(this))
            Tab.PEOPLE -> renderPeople()
            Tab.WISHLIST -> renderList(Wishlist)
            Tab.IDEAS -> renderList(Ideas)
            Tab.REPORTS -> renderReports(Store.all(this))
        }
        syncBack()
    }

    // ── Back from Settings, or from a person's page to the People list ──
    private var backCallback: Any? = null

    private fun syncBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = settingsOpen || (tab == Tab.PEOPLE && openPerson != null)
        val cb = backCallback as android.window.OnBackInvokedCallback?
        if (want && cb == null) {
            val c = android.window.OnBackInvokedCallback { goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, c)
            backCallback = c
        } else if (!want && cb != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backCallback = null
        }
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        if (settingsOpen || (tab == Tab.PEOPLE && openPerson != null)) goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun goBack() {
        if (settingsOpen) settingsOpen = false else openPerson = null
        render()
    }

    // ── People tab (separate ledger; never mixed with expenses) ──
    private fun renderPeople() {
        val people = Ledger.people(this)
        openPerson?.let { k ->
            people.firstOrNull { it.key == k }?.let { renderPerson(it); return }
            openPerson = null
        }
        val owed = people.filter { it.balance > 0 }.sumOf { it.balance }
        val owe = people.filter { it.balance < 0 }.sumOf { -it.balance }
        val net = owed - owe

        val hero = vbox().apply { background = bg(C.CARD, dp(22f), dp(1), C.LINE); setPadding(dp(18), dp(16), dp(18), dp(18)) }
        hero.addView(caps("Net balance"))
        hero.addView(text(balanceText(net), 30f, balanceColor(net), 2), lp(top = dp(4)))
        hero.addView(text(when {
            abs(net) < 0.005 -> if (people.isEmpty()) "Track money you lend and borrow" else "All square overall"
            net > 0 -> "Overall, you'll get this back"
            else -> "Overall, you owe this"
        }, 12f, C.DIM))
        content.addView(hero, lp(bottom = dp(10)))

        val stats = hbox()
        stats.addView(stat("You'll get", money(owed), C.GREEN), lp(0, WRAP, 1f, end = dp(5)))
        stats.addView(stat("You owe", money(owe), if (owe > 0) C.RED else C.TEXT), lp(0, WRAP, 1f, start = dp(5)))
        content.addView(stats, lp(bottom = dp(10)))

        content.addView(peopleActions(), lp(bottom = dp(24)))

        if (people.isEmpty()) {
            content.addView(empty("No one yet.\nRecord money you gave or got, or split a bill.\nThis is kept apart from your expenses."))
            return
        }
        val (settled, open) = people.partition { it.settled }
        if (open.isNotEmpty()) {
            content.addView(text("Open balances", 15f, C.TEXT, 2), lp(bottom = dp(10)))
            content.addView(card().apply { open.forEach { addView(personRow(it)) } }, lp(bottom = dp(18)))
        }
        if (settled.isNotEmpty()) {
            content.addView(text("Settled", 15f, C.MUTED, 2), lp(bottom = dp(10)))
            content.addView(card().apply { settled.forEach { addView(personRow(it)) } }, lp(bottom = dp(10)))
        }
    }

    private fun peopleActions() = hbox().apply {
        listOf(
            Triple("+ Gave", C.TEXT) { openDebt(null, "gave") },
            Triple("+ Got", C.GREEN) { openDebt(null, "got") },
            Triple("Split bill", C.TEXT) { PeopleDialog.split(this@MainActivity) { msg -> toast(msg); render() } },
        ).forEachIndexed { i, (label, color, action) ->
            addView(text(label, 13.5f, color, 2).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                background = pressable(bg(if (color == C.GREEN) withAlpha(C.GREEN, 0x24) else C.CARD, dp(14f), dp(1), C.LINE))
                setOnClickListener { action() }
            }, lp(0, WRAP, 1f, start = if (i > 0) dp(4) else 0, end = if (i < 2) dp(4) else 0))
        }
    }

    private fun personRow(p: Person): View {
        val r = hbox().apply {
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = pressable()
            setOnClickListener { openPerson = p.key; render() }
        }
        r.addView(avatar(p.name, if (p.settled) C.MUTED else balanceColor(p.balance)), lp(dp(36), dp(36), end = dp(12)))
        val mid = vbox()
        mid.addView(text(p.name, 14.5f, if (p.settled) C.MUTED else C.TEXT, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(text("${balanceWord(p.balance)} · ${dayLabel(p.lastDate)}", 11f, C.DIM), lp(WRAP, WRAP, top = dp(2)))
        r.addView(mid, lp(0, WRAP, 1f))
        if (!p.settled) r.addView(text(money(abs(p.balance), false), 14.5f, balanceColor(p.balance), 2), lp(WRAP, WRAP, start = dp(8)))
        return r
    }

    private fun renderPerson(p: Person) {
        content.addView(text("‹  All people", 13.5f, C.MUTED, 1).apply {
            setPadding(dp(2), dp(4), dp(12), dp(10))
            setOnClickListener { openPerson = null; render() }
        })

        val hero = vbox().apply { background = bg(C.CARD, dp(22f), dp(1), C.LINE); setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val top = hbox()
        top.addView(avatar(p.name, balanceColor(p.balance)), lp(dp(42), dp(42), end = dp(12)))
        val who = vbox()
        who.addView(text(p.name, 18f, C.TEXT, 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (p.phone.isNotBlank()) who.addView(text(p.phone, 12f, C.MUTED))
        top.addView(who, lp(0, WRAP, 1f))
        hero.addView(top)
        hero.addView(text(if (p.settled) "Settled up" else money(abs(p.balance)), 28f, if (p.settled) C.MUTED else balanceColor(p.balance), 2), lp(top = dp(14)))
        hero.addView(text(when {
            p.settled -> "Nothing owed either way"
            p.balance > 0 -> "${p.name} owes you"
            else -> "You owe ${p.name}"
        }, 12f, C.DIM))
        content.addView(hero, lp(bottom = dp(10)))

        content.addView(hbox().apply {
            val buttons = mutableListOf(
                Triple("+ Gave", C.TEXT) { openDebt(null, "gave") },
                Triple("+ Got", C.GREEN) { openDebt(null, "got") },
            )
            if (!p.settled) buttons += Triple("Settle up", C.TEXT) { settleUp(p) }
            buttons.forEachIndexed { i, (label, color, action) ->
                addView(text(label, 13.5f, color, 2).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(12))
                    background = pressable(bg(if (color == C.GREEN) withAlpha(C.GREEN, 0x24) else C.CARD, dp(14f), dp(1), C.LINE))
                    setOnClickListener { action() }
                }, lp(0, WRAP, 1f, start = if (i > 0) dp(4) else 0, end = if (i < buttons.lastIndex) dp(4) else 0))
            }
        }, lp(bottom = dp(24)))

        val gave = p.entries.filter { it.gave }.sumOf { it.amount }
        val got = p.entries.filter { !it.gave }.sumOf { it.amount }
        content.addView(text("History", 15f, C.TEXT, 2), lp(bottom = dp(4)))
        content.addView(text("You gave ${money(gave, false)} · you got ${money(got, false)} · ${p.entries.size} entries", 12f, C.MUTED), lp(bottom = dp(10)))
        content.addView(card().apply { p.entries.forEach { addView(debtRow(it)) } }, lp(bottom = dp(18)))

        content.addView(text("Delete ${p.name} and all history", 13f, C.RED, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Delete ${p.name}?")
                    .setMessage("Removes all ${p.entries.size} entries with ${p.name}. Your expenses are not affected.")
                    .setPositiveButton("Delete") { _, _ -> Ledger.deletePerson(this@MainActivity, p.key); openPerson = null; toast("${p.name} deleted"); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    private fun debtRow(d: Debt): View {
        val r = hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pressable()
            setOnClickListener { openDebt(d, d.type) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Delete this entry?")
                    .setMessage("${d.person} · ${money(d.amount, false)} · ${dayLabel(d.date)}")
                    .setPositiveButton("Delete") { _, _ -> Ledger.delete(this@MainActivity, d.id); toast("Entry deleted"); render() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        val color = if (d.gave) C.TEXT else C.GREEN
        r.addView(text(if (d.gave) "↑" else "↓", 14f, color, 2).apply {
            gravity = Gravity.CENTER
            background = bg(withAlpha(color, 0x1F), dp(12f))
        }, lp(dp(36), dp(36), end = dp(12)))
        val mid = vbox()
        mid.addView(text(d.note.ifBlank { if (d.gave) "You gave" else "You got" }, 14.5f, C.TEXT, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(text("${if (d.gave) "You gave" else "You got"} · ${dayLabel(d.date)}", 11f, C.DIM), lp(WRAP, WRAP, top = dp(2)))
        r.addView(mid, lp(0, WRAP, 1f))
        r.addView(text((if (d.gave) "" else "+") + money(d.amount, false), 14.5f, color, 2), lp(WRAP, WRAP, start = dp(8)))
        return r
    }

    private fun settleUp(p: Person) {
        val amt = abs(p.balance)
        val theyPay = p.balance > 0
        AlertDialog.Builder(this).setTitle("Settle up with ${p.name}?")
            .setMessage(if (theyPay) "Records that you got ${money(amt)} back from ${p.name}." else "Records that you paid ${p.name} ${money(amt)}.")
            .setPositiveButton("Settle") { _, _ ->
                Ledger.add(this, listOf(Debt("", p.name, p.phone, amt, if (theyPay) "got" else "gave", "Settled up", today().toString(), "")))
                toast("Settled up with ${p.name}")
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun avatar(name: String, color: Int) = text(name.trim().take(1).uppercase(), 15f, color, 2).apply {
        gravity = Gravity.CENTER
        background = bg(withAlpha(color, 0x24), dp(18f))
    }

    private fun card() = vbox().apply { background = bg(C.SURFACE, dp(18f), dp(1), C.LINE); clipToOutline = true; setPadding(0, dp(4), 0, dp(4)) }

    private fun balanceColor(v: Double) = when {
        abs(v) < 0.005 -> C.TEXT
        v > 0 -> C.GREEN
        else -> C.RED
    }

    private fun balanceText(v: Double, decimals: Boolean = true) = when {
        abs(v) < 0.005 -> money(0.0, decimals)
        v > 0 -> "+" + money(v, decimals)
        else -> "−" + money(-v, decimals)
    }

    private fun balanceWord(v: Double) = when {
        abs(v) < 0.005 -> "Settled"
        v > 0 -> "Owes you"
        else -> "You owe"
    }

    // ── Wishlist / Ideas tabs (removed from the list once done) ──
    private fun renderList(store: ItemList) {
        val items = store.all(this)
        val important = items.count { it.important }
        val hero = vbox().apply { background = bg(C.CARD, dp(22f), dp(1), C.LINE); setPadding(dp(18), dp(16), dp(18), dp(18)) }
        hero.addView(caps(store.heading))
        hero.addView(text("${items.size} item${if (items.size == 1) "" else "s"}", 30f, C.TEXT, 2), lp(top = dp(4)))
        hero.addView(text(if (important > 0) "$important marked important" else "Nothing marked important", 12f, if (important > 0) C.AMBER else C.DIM))
        content.addView(hero, lp(bottom = dp(24)))

        if (items.isEmpty()) {
            content.addView(empty(store.emptyText))
            return
        }
        val (imp, rest) = items.partition { it.important }
        for ((label, list) in listOf("Important" to imp, "Others" to rest)) {
            if (list.isEmpty()) continue
            content.addView(text(label, 15f, if (label == "Important") C.AMBER else C.TEXT, 2), lp(bottom = dp(10)))
            content.addView(card().apply { list.forEach { addView(itemRow(store, it)) } }, lp(bottom = dp(18)))
        }
        content.addView(text(store.footer, 11.5f, C.DIM).apply { gravity = Gravity.CENTER })
    }

    private fun itemRow(store: ItemList, w: WishItem): View {
        val r = hbox().apply { setPadding(dp(16), dp(10), dp(12), dp(10)) }
        val color = if (w.important) C.AMBER else C.MUTED
        r.addView(text(if (w.important) "★" else "•", 15f, color, 2).apply {
            gravity = Gravity.CENTER
            background = bg(withAlpha(color, 0x1F), dp(12f))
        }, lp(dp(36), dp(36), end = dp(12)))
        val mid = vbox()
        mid.addView(text(w.name, 14.5f, C.TEXT, 1).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(text("Added ${dayLabel(w.added)}", 11f, C.DIM), lp(WRAP, WRAP, top = dp(2)))
        r.addView(mid, lp(0, WRAP, 1f))
        r.addView(text(store.doneButton, 12.5f, C.GREEN, 2).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pressable(bg(withAlpha(C.GREEN, 0x1F), dp(10f)))
            setOnClickListener { store.confirmOrdered(this@MainActivity, w) { msg -> toast(msg); render() } }
        }, lp(WRAP, WRAP, start = dp(8)))
        return r
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
        // A hidden People tab also hides its section here (the data itself is kept)
        val debts = if (Settings.isOn(this, Tab.PEOPLE)) Ledger.all(this) else emptyList()
        if (list.isEmpty() && debts.isEmpty()) { content.addView(empty("No data yet")); return }
        if (list.isEmpty()) { content.addView(peopleReport(debts)); return }
        val now = YearMonth.now()
        val day = today()
        val byMonth = list.groupBy { it.month }

        // 1. Last 6 months: spent vs received, with net underneath
        val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
        val mt = months.map { byMonth[it.toString()].orEmpty().totals() }
        val trend = section("Last 6 months", "Average spend ${money(mt.map { it.spent }.average().roundToInt().toDouble(), false)} a month")
        trend.addView(legend(listOf("Spent" to C.TEXT, "Received" to C.GREEN)), lp(top = dp(10), bottom = dp(10)))
        trend.addView(BarChart(this, months.map(::short), listOf(mt.map { it.spent }, mt.map { it.received }), listOf(C.TEXT, C.GREEN), highlight = 5, showValues = false))
        trend.addView(caps("Net"), lp(top = dp(12)))
        val netRow = hbox()
        mt.forEach { t -> netRow.addView(text(signedCompact(t.net), 11f, if (t.net < 0) C.GREEN else C.MUTED, 1).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f)) }
        trend.addView(netRow, lp(top = dp(4)))
        content.addView(trend, lp(bottom = dp(10)))

        // 2. Spending pace: this month vs last month (cumulative)
        val last = now.minusMonths(1)
        val thisCum = cumulative(byMonth[now.toString()].orEmpty(), day.dayOfMonth)
        val lastCum = cumulative(byMonth[last.toString()].orEmpty(), last.lengthOfMonth())
        val cur = thisCum.lastOrNull() ?: 0.0
        val then = lastCum.getOrElse(day.dayOfMonth - 1) { lastCum.lastOrNull() ?: 0.0 }
        val diff = cur - then
        val paceSub = when {
            then == 0.0 -> "${money(cur, false)} spent so far"
            abs(diff) < 1 -> "Same as by day ${day.dayOfMonth} last month"
            else -> "${money(abs(diff), false)} ${if (diff > 0) "more" else "less"} than by day ${day.dayOfMonth} last month"
        }
        val pace = section("Spending pace", paceSub)
        pace.addView(legend(listOf(short(now) to C.TEXT, short(last) to 0xFF5A5A5A.toInt())), lp(top = dp(10), bottom = dp(10)))
        pace.addView(LineChart(this, listOf(lastCum, thisCum), listOf(0xFF5A5A5A.toInt(), C.TEXT), maxOf(now.lengthOfMonth(), last.lengthOfMonth())))
        content.addView(pace, lp(bottom = dp(10)))

        // 3. Weekday pattern over the last 90 days
        val since = day.minusDays(89).toString()
        val wk = DoubleArray(7)
        list.filter { !it.received && it.date >= since }.forEach { e -> parseDate(e.date)?.let { wk[it.dayOfWeek.value - 1] += e.amount } }
        val peak = wk.indices.maxByOrNull { wk[it] } ?: 0
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val week = section("By weekday", if (wk.sum() > 0) "Last 90 days · you spend most on ${days[peak]}" else "Last 90 days")
        week.addView(BarChart(this, days, listOf(wk.toList()), listOf(C.TEXT), highlight = if (wk.sum() > 0) peak else -1, heightDp = 130), lp(top = dp(12)))
        content.addView(week, lp(bottom = dp(10)))

        // 4. People ledger (its own data, shown separately)
        if (debts.isNotEmpty()) content.addView(peopleReport(debts), lp(bottom = dp(10)))

        // 5. Month-by-month detail
        content.addView(text("Months", 15f, C.TEXT, 2), lp(top = dp(14), bottom = dp(10)))
        for ((month, items) in byMonth) {
            val card = monthCard(month, items, openReports)
            if (month in openReports) card.addView(monthDetail(month, items))
            content.addView(card, lp(bottom = dp(10)))
        }
    }

    private fun monthDetail(month: String, items: List<Expense>): View {
        val t = items.totals()
        val spentItems = items.filter { !it.received }
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

        // Category donut + legend
        val cats = spentItems.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amount } }.entries.sortedByDescending { it.value }
        if (cats.isNotEmpty()) {
            body.addView(caps("By category"), lp(top = dp(20), bottom = dp(12)))
            val row = hbox()
            row.addView(Donut(this, cats.map { it.value to catColor(it.key) }, compact(t.spent), "spent"))
            val leg = vbox()
            for ((cat, amt) in cats.take(6)) {
                val r = hbox()
                r.addView(View(this).apply { background = bg(catColor(cat), dp(3f)) }, lp(dp(8), dp(8), end = dp(8)))
                r.addView(text(cat, 12.5f, C.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
                r.addView(text("${(amt / t.spent * 100).roundToInt()}%", 11f, C.DIM), lp(WRAP, WRAP, start = dp(6), end = dp(6)))
                r.addView(text(money(amt, false), 12.5f, C.TEXT, 1))
                leg.addView(r, lp(bottom = dp(8)))
            }
            if (cats.size > 6) leg.addView(text("+${cats.size - 6} more", 11f, C.DIM))
            row.addView(leg, lp(0, WRAP, 1f, start = dp(16)))
            body.addView(row)
        }

        // Daily bars + highlights (only for real months)
        val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return body
        val len = ym.lengthOfMonth()
        val daily = DoubleArray(len)
        spentItems.forEach { e -> e.date.takeLast(2).toIntOrNull()?.let { if (it in 1..len) daily[it - 1] += e.amount } }
        val isCurrent = ym == YearMonth.now()
        body.addView(caps("Daily spending"), lp(top = dp(18), bottom = dp(8)))
        body.addView(BarChart(
            this, (1..len).map { if (it == 1 || it % 5 == 0) "$it" else "" }, listOf(daily.toList()), listOf(C.TEXT),
            highlight = if (isCurrent) today().dayOfMonth - 1 else -1, showValues = false, heightDp = 110,
        ))

        val elapsed = if (isCurrent) today().dayOfMonth else len
        val biggest = spentItems.maxByOrNull { it.amount }
        val grid = listOf(
            Triple("Avg / day", money((t.spent / elapsed).roundToInt().toDouble(), false), "over $elapsed days"),
            Triple("Biggest", biggest?.let { money(it.amount, false) } ?: "—", biggest?.let { it.note.ifBlank { it.category }.capFirst() } ?: ""),
            Triple("Top category", cats.firstOrNull()?.key ?: "—", cats.firstOrNull()?.let { "${(it.value / t.spent * 100).roundToInt()}% of spend" } ?: ""),
            Triple("Entries", "${items.size}", "on ${daily.count { it > 0 }} days"),
        )
        for (pair in grid.chunked(2)) {
            val r = hbox()
            pair.forEachIndexed { i, (l, v, s) -> r.addView(tile(l, v, s), lp(0, WRAP, 1f, start = if (i > 0) dp(4) else 0, end = if (i == 0) dp(4) else 0)) }
            body.addView(r, lp(top = dp(8)))
        }
        return body
    }

    /** Lend / borrow metrics from the People ledger. Not counted in any expense figure. */
    private fun peopleReport(debts: List<Debt>): View {
        val people = Ledger.people(this)
        val open = people.filter { !it.settled }
        val owed = open.filter { it.balance > 0 }
        val owe = open.filter { it.balance < 0 }
        val owedSum = owed.sumOf { it.balance }
        val oweSum = owe.sumOf { -it.balance }

        val sec = section("People", if (open.isEmpty()) "Everyone is settled up" else "${open.size} open balance${if (open.size == 1) "" else "s"} · kept apart from expenses")
        val stats = hbox()
        listOf(
            Triple("You'll get", money(owedSum, false), C.GREEN),
            Triple("You owe", money(oweSum, false), if (oweSum > 0) C.RED else C.TEXT),
            Triple("Net", balanceText(owedSum - oweSum, false), balanceColor(owedSum - oweSum)),
        ).forEachIndexed { i, (l, v, c) ->
            stats.addView(stat(l, v, c, small = true), lp(0, WRAP, 1f, start = if (i > 0) dp(3) else 0, end = if (i < 2) dp(3) else 0))
        }
        sec.addView(stats, lp(top = dp(12)))

        // Gave vs got over the last 6 months
        val now = YearMonth.now()
        val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
        val byMonth = debts.groupBy { it.month }
        val gave = months.map { m -> byMonth[m.toString()].orEmpty().filter { it.gave }.sumOf { it.amount } }
        val got = months.map { m -> byMonth[m.toString()].orEmpty().filter { !it.gave }.sumOf { it.amount } }
        sec.addView(legend(listOf("You gave" to C.TEXT, "You got" to C.GREEN)), lp(top = dp(16), bottom = dp(10)))
        sec.addView(BarChart(this, months.map(::short), listOf(gave, got), listOf(C.TEXT, C.GREEN), highlight = 5, showValues = false, heightDp = 120))

        // Who owes the most, both ways
        fun top(title: String, list: List<Person>, color: Int) {
            if (list.isEmpty()) return
            sec.addView(caps(title), lp(top = dp(18), bottom = dp(8)))
            for (p in list.sortedByDescending { abs(it.balance) }.take(3)) {
                val r = hbox().apply { setOnClickListener { tab = Tab.PEOPLE; openPerson = p.key; render() } }
                r.addView(text(p.name, 13f, C.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
                r.addView(text(money(abs(p.balance), false), 13f, color, 1))
                sec.addView(r, lp(bottom = dp(8)))
            }
        }
        top("Owe you the most", owed, C.GREEN)
        top("You owe the most", owe, C.RED)

        val ym = now.toString()
        val thisMonth = debts.filter { it.month == ym }
        val oldest = open.minByOrNull { it.lastDate }
        val idle = oldest?.let { parseDate(it.lastDate) }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today()) }
        val grid = listOf(
            Triple("Gave this month", money(thisMonth.filter { it.gave }.sumOf { it.amount }, false), "${thisMonth.count { it.gave }} entries"),
            Triple("Got this month", money(thisMonth.filter { !it.gave }.sumOf { it.amount }, false), "${thisMonth.count { !it.gave }} entries"),
            Triple("Oldest open", oldest?.name ?: "—", idle?.let { if (it == 0L) "active today" else "no activity for $it days" } ?: ""),
            Triple("Splits", "${debts.filter { it.note.startsWith("Split") }.distinctBy { it.createdAt }.size}", "shared bills recorded"),
        )
        for (pair in grid.chunked(2)) {
            val r = hbox()
            pair.forEachIndexed { i, (l, v, s) -> r.addView(tile(l, v, s), lp(0, WRAP, 1f, start = if (i > 0) dp(4) else 0, end = if (i == 0) dp(4) else 0)) }
            sec.addView(r, lp(top = dp(8)))
        }
        return sec
    }

    private fun cumulative(items: List<Expense>, upTo: Int): List<Double> {
        val daily = DoubleArray(upTo)
        items.filter { !it.received }.forEach { e -> e.date.takeLast(2).toIntOrNull()?.let { if (it in 1..upTo) daily[it - 1] += e.amount } }
        var sum = 0.0
        return daily.map { sum += it; sum }
    }

    private fun short(ym: YearMonth) = ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun signedCompact(v: Double) = (if (v < 0) "−" else "") + compact(abs(v))

    private fun section(title: String, sub: String) = vbox().apply {
        background = bg(C.SURFACE, dp(18f), dp(1), C.LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text(title, 14.5f, C.TEXT, 2))
        addView(text(sub, 12f, C.MUTED), lp(top = dp(2)))
    }

    private fun legend(items: List<Pair<String, Int>>) = hbox().apply {
        for ((label, color) in items) {
            addView(View(context).apply { background = bg(color, dp(3f)) }, lp(dp(8), dp(8), end = dp(6)))
            addView(text(label, 11.5f, C.MUTED), lp(WRAP, WRAP, end = dp(14)))
        }
    }

    private fun tile(label: String, value: String, sub: String) = vbox().apply {
        background = bg(C.CARD, dp(12f), dp(1), C.LINE)
        setPadding(dp(12), dp(10), dp(10), dp(10))
        addView(caps(label))
        addView(text(value, 15f, C.TEXT, 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = dp(3)))
        addView(text(sub, 11f, C.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
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

    // ── Settings: tab visibility and purging ──
    private fun renderSettings() {
        content.addView(text("Tabs", 15f, C.TEXT, 2), lp(bottom = dp(2)))
        content.addView(text("Hidden tabs keep all their data — turn them back on any time.", 12f, C.MUTED), lp(bottom = dp(10)))
        content.addView(card().apply {
            for (t in Tab.entries) {
                val on = Settings.isOn(this@MainActivity, t)
                addView(settingRow(t.label, if (on) summary(t) else "Hidden · ${summary(t)}", if (on) "On" else "Off", if (on) C.GREEN else C.MUTED) { toggleTab(t) })
            }
        }, lp(bottom = dp(24)))

        content.addView(text("Purge data", 15f, C.TEXT, 2), lp(bottom = dp(2)))
        content.addView(text("Permanently deletes data, including from hidden tabs. Export a backup first if you might need it.", 12f, C.MUTED), lp(bottom = dp(10)))
        content.addView(card().apply {
            addView(settingRow("Expenses", summary(Tab.EXPENSES), "Purge…", C.RED) { purgeMenu() })
            addView(settingRow("People", summary(Tab.PEOPLE), "Purge…", C.RED) { wipe("all people entries", Ledger.all(this@MainActivity).size) { Ledger.clear(this@MainActivity) } })
            addView(settingRow("Wishlist", summary(Tab.WISHLIST), "Purge…", C.RED) { wipe("the wishlist", Wishlist.all(this@MainActivity).size) { Wishlist.clear(this@MainActivity) } })
            addView(settingRow("Ideas", summary(Tab.IDEAS), "Purge…", C.RED) { wipe("all ideas", Ideas.all(this@MainActivity).size) { Ideas.clear(this@MainActivity) } })
        }, lp(bottom = dp(14)))

        content.addView(text("Erase everything", 13.5f, C.RED, 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = pressable(bg(withAlpha(C.RED, 0x1F), dp(14f), dp(1), C.LINE))
            setOnClickListener { eraseAll() }
        })
    }

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun summary(t: Tab): String = when (t) {
        Tab.EXPENSES -> plural(Store.all(this).size, "entry", "entries")
        Tab.PEOPLE -> "${plural(Ledger.all(this).size, "entry", "entries")} · ${plural(Ledger.people(this).size, "person", "people")}"
        Tab.WISHLIST -> plural(Wishlist.all(this).size, "item")
        Tab.IDEAS -> plural(Ideas.all(this).size, "idea")
        Tab.REPORTS -> "Charts from your expenses and people"
    }

    private fun settingRow(label: String, sub: String, action: String, color: Int, onClick: () -> Unit): View {
        val r = hbox().apply {
            setPadding(dp(16), dp(11), dp(12), dp(11))
            background = pressable()
            setOnClickListener { onClick() }
        }
        val mid = vbox()
        mid.addView(text(label, 14.5f, C.TEXT, 1))
        mid.addView(text(sub, 11f, C.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(WRAP, WRAP, top = dp(2)))
        r.addView(mid, lp(0, WRAP, 1f))
        r.addView(text(action, 12.5f, color, 2).apply {
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = bg(withAlpha(color, 0x1F), dp(10f))
        }, lp(WRAP, WRAP, start = dp(8)))
        return r
    }

    private fun toggleTab(t: Tab) {
        val on = Settings.isOn(this, t)
        if (on && Settings.visibleTabs(this).size == 1) { toast("Keep at least one tab on"); return }
        Settings.setOn(this, t, !on)
        toast(if (on) "${t.label} hidden — its data is kept" else "${t.label} shown")
        render()
    }

    /** Confirms, then deletes a whole section via [action] (which returns how many were removed). */
    private fun wipe(what: String, n: Int, action: () -> Int) {
        if (n == 0) { toast("Nothing to purge"); return }
        AlertDialog.Builder(this).setTitle("Delete ${what}?")
            .setMessage("This permanently removes $n ${if (n == 1) "entry" else "entries"}.\n\nExport a backup first if you might need them later.")
            .setPositiveButton("Purge") { _, _ -> toast("Purged ${action()} ${if (n == 1) "entry" else "entries"}"); render() }
            .setNeutralButton("Export first") { _, _ -> export(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun eraseAll() {
        val e = Store.all(this).size
        val d = Ledger.all(this).size
        val w = Wishlist.all(this).size
        val i = Ideas.all(this).size
        if (e + d + w + i == 0) { toast("Nothing to purge"); return }
        AlertDialog.Builder(this).setTitle("Erase everything?")
            .setMessage("Permanently deletes ${plural(e, "expense")}, ${plural(d, "people entry", "people entries")}, ${plural(w, "wishlist item")} and ${plural(i, "idea")}.\n\nTab settings are kept. Export a backup first if you might need any of it.")
            .setPositiveButton("Erase") { _, _ ->
                AlertDialog.Builder(this).setTitle("Are you sure?").setMessage("This cannot be undone.")
                    .setPositiveButton("Erase everything") { _, _ ->
                        Store.purge(this) { true }; Ledger.clear(this); Wishlist.clear(this); Ideas.clear(this)
                        openPerson = null
                        toast("All data erased")
                        render()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNeutralButton("Export first") { _, _ -> export(true) }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Menu: export / import / settings ──
    private fun showMenu(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(0, REQ_EXPORT_JSON, 0, "Export JSON backup")
        menu.add(0, REQ_EXPORT_CSV, 1, "Export CSV")
        menu.add(0, REQ_IMPORT, 2, "Import JSON / CSV")
        menu.add(0, 99, 3, "Settings & purge…")
        setOnMenuItemClickListener {
            when (it.itemId) {
                REQ_EXPORT_JSON -> export(true)
                REQ_EXPORT_CSV -> export(false)
                REQ_IMPORT -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")),
                    REQ_IMPORT,
                )
                else -> { settingsOpen = true; openPerson = null; render() }
            }
            true
        }
        show()
    }

    private fun export(json: Boolean) {
        val nothing = Store.all(this).isEmpty() && (!json || (Ledger.all(this).isEmpty() && Wishlist.all(this).isEmpty() && Ideas.all(this).isEmpty()))
        if (nothing) { toast(if (json) "Nothing to export" else "No expenses to export (People, Wishlist and Ideas are in the JSON backup)"); return }
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
                    val json = requestCode == REQ_EXPORT_JSON
                    val people = Ledger.all(this)
                    val wish = Wishlist.all(this)
                    val ideas = Ideas.all(this)
                    val out = if (json) Backup.toFullJson(list, people, wish, ideas) else Backup.toCsv(list)
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(out.toByteArray(Charsets.UTF_8)) }
                    toast(if (json) "Exported ${list.size} expenses, ${people.size} people entries, ${wish.size} wishlist items, ${ideas.size} ideas" else "Exported ${list.size} entries")
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
        val debtIds = Ledger.all(this).mapTo(HashSet()) { it.id }
        val freshPeople = p.people.count { it.id.isBlank() || it.id !in debtIds }
        val wishIds = Wishlist.all(this).mapTo(HashSet()) { it.id }
        val freshWish = p.wishlist.count { it.id.isBlank() || it.id !in wishIds }
        val ideaIds = Ideas.all(this).mapTo(HashSet()) { it.id }
        val freshIdeas = p.ideas.count { it.id.isBlank() || it.id !in ideaIds }
        val total = fresh + freshPeople + freshWish + freshIdeas

        val msg = StringBuilder("Expenses — new: $fresh, already here: ${p.valid.size - fresh}, invalid rows: ${p.invalid}")
        if (p.people.isNotEmpty()) msg.append("\nPeople entries — new: $freshPeople, already here: ${p.people.size - freshPeople}")
        if (p.wishlist.isNotEmpty()) msg.append("\nWishlist items — new: $freshWish, already here: ${p.wishlist.size - freshWish}")
        if (p.ideas.isNotEmpty()) msg.append("\nIdeas — new: $freshIdeas, already here: ${p.ideas.size - freshIdeas}")
        msg.append("\n\nAnything that already exists is skipped.")
        val b = AlertDialog.Builder(this).setTitle("Import backup").setMessage(msg)
        if (total > 0) b.setPositiveButton("Import $total") { _, _ ->
            val e = Store.import(this, p)
            val d = Ledger.import(this, p.people)
            val w = Wishlist.import(this, p.wishlist)
            val i = Ideas.import(this, p.ideas)
            toast(listOfNotNull("$e expenses", "$d people entries".takeIf { p.people.isNotEmpty() }, "$w wishlist items".takeIf { p.wishlist.isNotEmpty() }, "$i ideas".takeIf { p.ideas.isNotEmpty() })
                .joinToString(", ", prefix = "Imported "))
            render()
        }.setNegativeButton("Cancel", null) else b.setPositiveButton("OK", null)
        b.show()
    }

    private fun purgeMenu() {
        val n = Store.all(this).size
        if (n == 0) { toast("Nothing to purge"); return }
        AlertDialog.Builder(this).setTitle("Purge expenses")
            .setItems(arrayOf("Select months…", "Date range…", "All expenses ($n)")) { _, w ->
                when (w) {
                    0 -> purgeMonths()
                    1 -> purgeRange()
                    else -> confirmPurge(n, "all your expenses") { true }
                }
            }
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
