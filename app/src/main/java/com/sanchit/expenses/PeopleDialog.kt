package com.sanchit.expenses

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate
import kotlin.math.round

/** Bottom sheets for the People ledger: add/edit one entry, and split a bill. Used by the app and the People widget popup. */
object PeopleDialog {

    // ── Add / edit one entry ──
    fun show(
        act: Activity,
        existing: Debt?,
        initialType: String = "gave",
        person: String? = null,
        onDismiss: (() -> Unit)? = null,
        onSaved: (message: String) -> Unit,
    ) = with(act) {
        val (dialog, root) = sheet(this, if (existing == null) "New entry" else "Edit entry")
        var type = existing?.type ?: initialType
        var date = existing?.date?.let(::parseDate) ?: today()
        var phone = existing?.phone ?: ""
        var refresh: () -> Unit = {}

        // You gave / You got
        val seg = hbox().apply { background = bg(C.CARD, dp(12f), dp(1), C.LINE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val gaveBtn = chip("↑  You gave")
        val gotBtn = chip("↓  You got")
        seg.addView(gaveBtn, lp(0, WRAP, 1f))
        seg.addView(gotBtn, lp(0, WRAP, 1f))
        root.addView(seg, lp(bottom = dp(6)))
        val hint = text("", 11.5f, C.DIM).apply { gravity = Gravity.CENTER }
        root.addView(hint, lp(bottom = dp(12)))

        // Person
        val name = personField(this, existing?.person ?: person ?: "", "Person's name") { phone = it.phone }
        root.addView(name.box, lp(bottom = dp(12)))

        val (amountBox, cur, amount) = amountField(this, existing?.amount)
        root.addView(amountBox, lp(bottom = dp(12)))

        val note = input(this, "Note (optional)", existing?.note ?: "")
        root.addView(note, lp(bottom = dp(12)))

        val (dateRow, refreshDate) = dateRow(this, { date }) { date = it; refresh() }
        root.addView(dateRow, lp(bottom = dp(12)))

        val err = text("", 12.5f, C.RED).apply { visibility = View.GONE }
        root.addView(err, lp(bottom = dp(8)))

        val actions = hbox()
        if (existing != null) actions.addView(deleteButton(this) {
            AlertDialog.Builder(this@with).setTitle("Delete this entry?")
                .setMessage("${existing.person} · ${money(existing.amount, false)} · ${dayLabel(existing.date)}")
                .setPositiveButton("Delete") { _, _ -> Ledger.delete(this@with, existing.id); onSaved("Entry deleted"); dialog.dismiss() }
                .setNegativeButton("Cancel", null).show()
        }, lp(WRAP, WRAP, end = dp(10)))
        val save = text("", 15f, C.BG, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) }
        actions.addView(save, lp(0, WRAP, 1f))
        root.addView(actions)

        fun submit() {
            val who = name.field.text.toString().trim().capFirst()
            val a = amount.text.toString().replace(",", "").toDoubleOrNull()
            val problem = when {
                who.isEmpty() -> "Enter a name"
                a == null || a <= 0 -> "Enter a valid amount"
                else -> null
            }
            if (problem != null) {
                err.text = problem
                err.visibility = View.VISIBLE
                (if (who.isEmpty()) name.field else amount).requestFocus()
                return
            }
            // Keep a known person's number when the name was typed rather than picked
            if (phone.isBlank()) phone = Ledger.people(this).firstOrNull { it.key == personKey(who) }?.phone ?: ""
            val n = note.text.toString().trim().capFirst()
            val d = Debt(existing?.id ?: "", who, phone, a!!, type, n, date.toString(), existing?.createdAt ?: "")
            if (existing == null) Ledger.add(this, listOf(d)) else Ledger.update(this, d)
            onSaved(if (existing != null) "Entry updated" else if (type == "gave") "You gave $who ${money(a, false)}" else "You got ${money(a, false)} from $who")
            dialog.dismiss()
        }

        refresh = {
            val gave = type == "gave"
            gaveBtn.setTextColor(if (gave) C.TEXT else C.MUTED)
            gotBtn.setTextColor(if (!gave) C.GREEN else C.MUTED)
            gaveBtn.background = if (gave) bg(C.CARD2, dp(9f)) else null
            gotBtn.background = if (!gave) bg(withAlpha(C.GREEN, 0x33), dp(9f)) else null
            hint.text = if (gave) "Lent them money, or paid them back" else "Borrowed from them, or they paid you back"
            cur.setTextColor(if (gave) C.MUTED else C.GREEN)
            refreshDate()
            save.text = if (existing != null) "Save changes" else if (gave) "Save · you gave" else "Save · you got"
            save.setTextColor(if (gave) C.BG else Color.WHITE)
            save.background = bg(if (gave) C.TEXT else C.GREEN_D, dp(14f))
        }
        gaveBtn.setOnClickListener { type = "gave"; refresh() }
        gotBtn.setOnClickListener { type = "got"; refresh() }
        save.setOnClickListener { submit() }
        note.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        refresh()

        present(dialog, root, onDismiss, keyboard = existing == null)
        if (existing == null) (if (name.field.text.isEmpty()) name.field else amount).requestFocus()
    }

    // ── Split a bill: equally, by percentage, or by amount ──
    private const val ME = "\u0000me" // participant key for you; can't clash with a person key

    fun split(act: Activity, onDismiss: (() -> Unit)? = null, onSaved: (message: String) -> Unit) = with(act) {
        val (dialog, root) = sheet(this, "Split a bill")
        val people = LinkedHashMap<String, Contacts.Contact>() // key → person
        var paidBy: String? = null // null = you, else a person key
        var includeMe = true
        var mode = 0 // 0 equally, 1 by %, 2 by amount
        val custom = listOf(HashMap<String, String>(), HashMap<String, String>()) // typed % / ₹ per participant key
        var date = today()
        var rebuild: () -> Unit = {}
        var update: () -> Unit = {}
        val watcher = { onChange: (String) -> Unit ->
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
            }
        }
        fun card() = vbox().apply { background = bg(C.CARD, dp(16f), dp(1), C.LINE); clipToOutline = true }
        fun divider() = View(this).apply { setBackgroundColor(C.LINE) }

        // 1. Bill: amount + what it was for
        val (amountBox, _, amount) = amountField(this, null)
        root.addView(amountBox, lp(bottom = dp(10)))
        val note = input(this, "What was it for?", "")
        root.addView(note, lp(bottom = dp(18)))

        // 2. How to split
        root.addView(label(this, "How to split"), lp(bottom = dp(8)))
        val modeSeg = hbox().apply { background = bg(C.CARD, dp(12f), dp(1), C.LINE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val modeBtns = listOf("=  Equally", "%  Percent", "₹  Amount").mapIndexed { i, l ->
            chip(l).also { v -> v.setOnClickListener { mode = i; rebuild() }; modeSeg.addView(v, lp(0, WRAP, 1f)) }
        }
        root.addView(modeSeg, lp(bottom = dp(18)))

        // 3. Who's in it: one row per person with their share
        val peopleHead = hbox()
        peopleHead.addView(label(this, "Who's in"), lp(0, WRAP, 1f))
        val countText = text("", 11.5f, C.DIM)
        peopleHead.addView(countText)
        root.addView(peopleHead, lp(bottom = dp(8)))
        val list = card()
        root.addView(list, lp(bottom = dp(8)))
        lateinit var who: PersonField
        fun addPerson(c: Contacts.Contact) {
            val n = c.name.trim().capFirst()
            if (n.isEmpty()) return
            people[personKey(n)] = Contacts.Contact(n, c.phone)
            who.field.setText("")
            rebuild()
        }
        who = personField(this, "", "+  Add a person", ime = EditorInfo.IME_ACTION_DONE) { addPerson(it) }
        who.field.setOnEditorActionListener { _, _, _ -> addPerson(Contacts.Contact(who.field.text.toString(), "")); true }
        root.addView(who.box, lp(bottom = dp(18)))

        // 4. Paid by + date
        val details = card()
        val paidLine = hbox().apply { setPadding(dp(14), dp(8), dp(8), dp(8)) }
        paidLine.addView(text("Paid by", 13.5f, C.MUTED, 1), lp(dp(72), WRAP))
        val paidRow = hbox()
        paidLine.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(paidRow) }, lp(0, WRAP, 1f))
        details.addView(paidLine)
        details.addView(divider(), lp(MATCH, dp(1)))
        val dateLine = hbox().apply { setPadding(dp(14), dp(8), dp(8), dp(8)) }
        dateLine.addView(text("Date", 13.5f, C.MUTED, 1), lp(dp(72), WRAP))
        val dateBtn = chip("")
        dateLine.addView(dateBtn, lp(WRAP, WRAP))
        details.addView(dateLine)
        root.addView(details, lp(bottom = dp(14)))
        dateBtn.setOnClickListener { pickDate(date) { date = it; rebuild() } }

        // 5. Result: who owes whom
        val result = vbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        root.addView(result, lp(bottom = dp(12)))
        val err = text("", 12.5f, C.RED).apply { visibility = View.GONE }
        root.addView(err, lp(bottom = dp(8)))
        val save = text("Save split", 15f, C.BG, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) }
        root.addView(save)

        fun participants() = (if (includeMe) listOf(ME) else emptyList()) + people.keys
        fun nameOf(k: String) = if (k == ME) "You" else people[k]!!.name
        fun total() = amount.text.toString().replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
        fun r2(v: Double) = round(v * 100) / 100
        fun num(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        /** Each participant's share in ₹, or the reason it can't be worked out yet. */
        fun shares(): Pair<Map<String, Double>?, String> {
            val total = total() ?: return null to "Enter the bill amount"
            val keys = participants()
            if (people.isEmpty()) return null to "Add the people you're splitting with"
            return when (mode) {
                0 -> keys.associateWith { r2(total / keys.size) } to ""
                1 -> {
                    val pct = keys.associateWith { custom[0][it]?.toDoubleOrNull() ?: 0.0 }
                    val sum = pct.values.sum()
                    if (kotlin.math.abs(sum - 100) > 0.01) null to (if (sum < 100) "${num(r2(100 - sum))}% left to assign" else "${num(r2(sum - 100))}% too much — total must be 100%")
                    else pct.mapValues { r2(total * it.value / 100) } to ""
                }
                else -> {
                    val amt = keys.associateWith { custom[1][it]?.toDoubleOrNull() ?: 0.0 }
                    val left = total - amt.values.sum()
                    when {
                        left > 0.01 -> null to "${money(left, false)} of ${money(total, false)} left to assign"
                        left < -0.01 -> null to "${money(-left, false)} over the bill of ${money(total, false)}"
                        else -> amt.mapValues { r2(it.value) } to ""
                    }
                }
            }
        }

        val subs = HashMap<String, android.widget.TextView>()   // line under each name
        val values = HashMap<String, android.widget.TextView>() // share shown on the right in Equally mode

        fun line(label: String, value: String, color: Int, bold: Boolean = false) = hbox().apply {
            addView(text(label, 13.5f, if (bold) C.TEXT else C.MUTED, if (bold) 2 else 0), lp(0, WRAP, 1f))
            addView(text(value, 14f, color, 2))
        }

        update = {
            val (sh, problem) = shares()
            val total = total()
            for ((k, v) in subs) {
                val bits = mutableListOf<String>()
                if (paidBy == k || (paidBy == null && k == ME)) bits += "Paid the bill"
                if (k == ME && !includeMe) bits += "Not in this split"
                else when (mode) {
                    1 -> total?.let { t -> custom[0][k]?.toDoubleOrNull()?.let { bits += money(r2(t * it / 100), false) } }
                    2 -> total?.let { t -> custom[1][k]?.toDoubleOrNull()?.let { bits += "${num(r2(it / t * 100))}% of bill" } }
                }
                v.text = bits.joinToString(" · ")
                v.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
            }
            for ((k, v) in values) v.text = sh?.get(k)?.let { money(it, false) } ?: "—"
            countText.text = "${participants().size} ${if (participants().size == 1) "person" else "people"}"

            result.removeAllViews()
            if (sh == null) {
                result.background = bg(withAlpha(C.AMBER, 0x14), dp(14f), dp(1), withAlpha(C.AMBER, 0x33))
                result.addView(text(problem, 13f, C.AMBER, 1))
            } else {
                result.background = bg(C.SURFACE, dp(14f), dp(1), C.LINE)
                if (paidBy == null) {
                    val owing = people.keys.filter { (sh[it] ?: 0.0) > 0 }
                    for (k in owing) result.addView(line("${nameOf(k)} owes you", money(sh[k]!!, false), C.GREEN), lp(bottom = dp(6)))
                    if (owing.size > 1) {
                        result.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(MATCH, dp(1), top = dp(2), bottom = dp(8)))
                        result.addView(line("You'll get back", money(owing.sumOf { sh[it]!! }, false), C.GREEN, bold = true))
                    }
                    if (owing.isEmpty()) result.addView(text("No one else has a share, so there's nothing to record", 13f, C.MUTED))
                } else if ((sh[ME] ?: 0.0) > 0) {
                    result.addView(line("You owe ${people[paidBy]!!.name}", money(sh[ME]!!, false), C.RED, bold = true))
                    result.addView(text("Only your share is recorded — the rest is between them", 11.5f, C.DIM), lp(top = dp(4)))
                } else result.addView(text("You have no share, so there's nothing to record", 13f, C.MUTED))
            }
            val ok = sh != null
            save.text = total?.let { "Save split · ${money(it, false)}" } ?: "Save split"
            save.setTextColor(if (ok) C.BG else C.MUTED)
            save.background = bg(if (ok) C.TEXT else C.CARD2, dp(14f))
        }

        fun participantRow(k: String): View {
            val me = k == ME
            val active = !me || includeMe
            val r = hbox().apply { setPadding(dp(12), dp(10), dp(10), dp(10)) }
            // Left: avatar, or the include-me toggle for you
            r.addView(text(if (me) (if (includeMe) "✓" else "") else nameOf(k).take(1).uppercase(), 14f, if (me) C.BG else C.TEXT, 2).apply {
                gravity = Gravity.CENTER
                background = if (me) (if (includeMe) bg(C.GREEN, dp(16f)) else bg(0, dp(16f), dp(2), C.DIM)) else bg(C.CARD2, dp(16f))
                if (me) setOnClickListener { includeMe = !includeMe; rebuild() }
            }, lp(dp(32), dp(32), end = dp(12)))
            val mid = vbox()
            mid.addView(text(nameOf(k), 14.5f, if (active) C.TEXT else C.DIM, 1).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val sub = text("", 11.5f, C.DIM)
            subs[k] = sub
            mid.addView(sub, lp(WRAP, WRAP, top = dp(1)))
            r.addView(mid, lp(0, WRAP, 1f))
            if (me) mid.setOnClickListener { includeMe = !includeMe; rebuild() }

            if (active) {
                if (mode == 0) {
                    val v = text("", 14.5f, C.TEXT, 2)
                    values[k] = v
                    r.addView(v, lp(WRAP, WRAP, start = dp(8)))
                } else {
                    // Compact input pill: ₹ [ 300 ]  or  [ 30 ] %
                    val pill = hbox().apply { background = bg(C.CARD2, dp(10f)); setPadding(dp(10), 0, dp(10), 0) }
                    if (mode == 2) pill.addView(text("₹", 14f, C.MUTED))
                    pill.addView(EditText(this).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        imeOptions = EditorInfo.IME_ACTION_NEXT
                        textSize = 15f
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setTextColor(C.TEXT)
                        setHintTextColor(C.DIM)
                        background = null
                        minWidth = dp(56)
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        val n = participants().size
                        hint = if (mode == 1) num(r2(100.0 / n)) else total()?.let { num(r2(it / n)) } ?: "0"
                        setText(custom[mode - 1][k] ?: "")
                        addTextChangedListener(watcher { custom[mode - 1][k] = it; update() })
                    }, lp(WRAP, WRAP))
                    if (mode == 1) pill.addView(text("%", 14f, C.MUTED))
                    r.addView(pill, lp(WRAP, WRAP, start = dp(8)))
                }
            }
            // ✕ to remove; an invisible one on your row keeps the share column aligned
            r.addView(text("✕", 13f, C.DIM).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                if (me) visibility = View.INVISIBLE else setOnClickListener { people.remove(k); rebuild() }
            })
            return r
        }

        rebuild = {
            if (paidBy != null && paidBy !in people) paidBy = null
            modeBtns.forEachIndexed { i, v ->
                v.setTextColor(if (i == mode) C.TEXT else C.MUTED)
                v.background = if (i == mode) bg(C.CARD2, dp(9f)) else null
            }
            list.removeAllViews()
            subs.clear()
            values.clear()
            list.addView(participantRow(ME))
            for (k in people.keys) {
                list.addView(divider(), lp(MATCH, dp(1)))
                list.addView(participantRow(k))
            }
            if (people.isEmpty()) {
                list.addView(divider(), lp(MATCH, dp(1)))
                list.addView(text("Add the people you're splitting with below", 12.5f, C.DIM).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) })
            }
            paidRow.removeAllViews()
            (listOf<Pair<String?, String>>(null to "You") + people.map { it.key to it.value.name }).forEach { (k, n) ->
                paidRow.addView(chip(n).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    styleChip(this, paidBy == k)
                    setOnClickListener { paidBy = k; rebuild() }
                }, lp(WRAP, WRAP, end = dp(6)))
            }
            dateBtn.text = "${dayLabel(date.toString())}  ▾"
            dateBtn.setPadding(dp(12), dp(6), dp(12), dp(6))
            styleChip(dateBtn, false)
            dateBtn.setTextColor(C.TEXT)
            update()
        }

        amount.addTextChangedListener(watcher { if (mode == 2) rebuild() else update() })
        save.setOnClickListener {
            // A name typed but not yet added still counts
            who.field.text.toString().takeIf { it.isNotBlank() }?.let { addPerson(Contacts.Contact(it, "")) }
            val (sh, problem) = shares()
            val error = when {
                sh == null -> problem
                paidBy == null && people.keys.none { (sh[it] ?: 0.0) > 0 } -> "No one else has a share — nothing to record"
                paidBy != null && (sh[ME] ?: 0.0) <= 0 -> "You have no share — nothing to record"
                else -> null
            }
            if (error != null) { err.text = error; err.visibility = View.VISIBLE; return@setOnClickListener }
            val what = note.text.toString().trim().capFirst()
            val n = "Split" + if (what.isNotEmpty()) " · $what" else ""
            val entries = if (paidBy == null) people.filter { (sh!![it.key] ?: 0.0) > 0 }.map { (k, c) -> Debt("", c.name, c.phone, sh!![k]!!, "gave", n, date.toString(), "") }
            else people[paidBy]!!.let { listOf(Debt("", it.name, it.phone, sh!![ME]!!, "got", n, date.toString(), "")) }
            Ledger.add(this, entries)
            onSaved(if (paidBy == null) "Split saved · ${entries.size} owe you ${money(entries.sumOf { it.amount }, false)}" else "Split saved · you owe ${entries[0].person} ${money(entries[0].amount, false)}")
            dialog.dismiss()
        }
        rebuild()
        present(dialog, root, onDismiss, keyboard = true)
        amount.requestFocus()
    }

    // ── Shared building blocks ──
    class PersonField(val box: View, val field: AutoCompleteTextView)

    /** Name input with suggestions from people already in the ledger and (once allowed) the phone's contacts. */
    private fun personField(act: Activity, initial: String, hintText: String, ime: Int = EditorInfo.IME_ACTION_NEXT, onPick: (Contacts.Contact) -> Unit) = with(act) {
        val box = vbox()
        val field = AutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            imeOptions = ime
            hint = hintText
            textSize = 15f
            threshold = 1
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = bg(C.CARD, dp(14f), dp(1), C.LINE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setDropDownBackgroundDrawable(bg(C.CARD, dp(12f), dp(1), C.LINE))
            setText(initial)
            setAdapter(SuggestAdapter(context, Contacts.suggestions(context)))
            setOnItemClickListener { parent, _, pos, _ -> onPick(parent.getItemAtPosition(pos) as Contacts.Contact) }
        }
        box.addView(field)
        if (!Contacts.granted(this)) {
            val link = text("Suggest names from contacts", 12f, C.GREEN, 1).apply { setPadding(dp(4), dp(8), dp(4), dp(2)) }
            link.setOnClickListener {
                Contacts.request(act) { ok ->
                    if (ok) {
                        link.visibility = View.GONE
                        field.setAdapter(SuggestAdapter(this, Contacts.suggestions(this)))
                        field.requestFocus()
                        if (field.text.isNotEmpty()) field.showDropDown()
                    } else Toast.makeText(this, "Contacts not allowed — you can enable it in app settings", Toast.LENGTH_SHORT).show()
                }
            }
            box.addView(link, lp(WRAP, WRAP))
        }
        PersonField(box, field)
    }

    private class SuggestAdapter(ctx: Context, items: List<Contacts.Contact>) : ArrayAdapter<Contacts.Contact>(ctx, 0, items.toMutableList()) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = with(context) {
            val c = getItem(position)!!
            vbox().apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(text(c.name, 14.5f, C.TEXT, 1))
                if (c.phone.isNotBlank()) addView(text(c.phone, 11.5f, C.MUTED))
            }
        }
    }

    private fun sheet(ctx: Context, title: String): Pair<Dialog, LinearLayout> = with(ctx) {
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val r = dp(24f)
        val root = vbox().apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(0xFF121212.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(this).apply { background = bg(C.CARD2, dp(2f)) }, lp(dp(36), dp(4), bottom = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = hbox()
        head.addView(text(title, 17f, C.TEXT, 2), lp(0, WRAP, 1f))
        head.addView(text("✕", 16f, C.MUTED).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, lp(bottom = dp(14)))
        dialog to root
    }

    private fun present(dialog: Dialog, root: View, onDismiss: (() -> Unit)?, keyboard: Boolean) {
        dialog.setContentView(android.widget.ScrollView(root.context).apply { isVerticalScrollBarEnabled = false; addView(root) })
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.65f)
            setSoftInputMode(
                (if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
                    or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }

    private fun amountField(ctx: Context, initial: Double?) = with(ctx) {
        val box = hbox().apply { background = bg(C.CARD, dp(14f), dp(1), C.LINE); setPadding(dp(16), 0, dp(12), 0) }
        val cur = text("₹", 24f, C.MUTED)
        val amount = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
            textSize = 28f
            hint = "0"
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = null
            setPadding(dp(8), dp(12), 0, dp(12))
            initial?.let { setText(if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) }
            setSelection(text.length)
        }
        box.addView(cur)
        box.addView(amount, lp(0, WRAP, 1f))
        Triple(box, cur, amount)
    }

    private fun input(ctx: Context, hintText: String, value: String) = with(ctx) {
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = hintText
            textSize = 15f
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = bg(C.CARD, dp(14f), dp(1), C.LINE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setText(value)
        }
    }

    /** Date picker + Today / Yesterday chips. Returns the row and a function that restyles it for the current date. */
    private fun dateRow(ctx: Context, current: () -> LocalDate, onPick: (LocalDate) -> Unit): Pair<View, () -> Unit> = with(ctx) {
        val row = hbox()
        val dateBtn = chip("").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val todayBtn = chip("Today")
        val yestBtn = chip("Yesterday")
        row.addView(dateBtn, lp(0, WRAP, 1f, end = dp(8)))
        row.addView(todayBtn, lp(WRAP, WRAP, end = dp(8)))
        row.addView(yestBtn, lp(WRAP, WRAP))
        dateBtn.setOnClickListener { pickDate(current()) { onPick(it) } }
        todayBtn.setOnClickListener { onPick(today()) }
        yestBtn.setOnClickListener { onPick(today().minusDays(1)) }
        row to {
            val d = current()
            dateBtn.text = "Date   ${dayLabel(d.toString())}"
            styleChip(dateBtn, false)
            dateBtn.setTextColor(C.TEXT)
            styleChip(todayBtn, d == today(), C.CARD2, C.TEXT)
            styleChip(yestBtn, d == today().minusDays(1), C.CARD2, C.TEXT)
        }
    }

    private fun label(ctx: Context, s: String) = ctx.text(s.uppercase(), 10.5f, C.MUTED, 1).apply { letterSpacing = 0.08f }

    private fun deleteButton(ctx: Context, onClick: () -> Unit) = with(ctx) {
        text("Delete", 15f, C.RED, 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = bg(withAlpha(C.RED, 0x1F), dp(14f))
            setOnClickListener { onClick() }
        }
    }
}
