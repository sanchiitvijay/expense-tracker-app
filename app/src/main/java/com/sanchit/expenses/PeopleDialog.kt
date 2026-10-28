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

    // ── Split a bill equally ──
    fun split(act: Activity, onDismiss: (() -> Unit)? = null, onSaved: (message: String) -> Unit) = with(act) {
        val (dialog, root) = sheet(this, "Split a bill")
        val people = LinkedHashMap<String, Contacts.Contact>() // key → person
        var paidBy: String? = null // null = you, else a person key
        var includeMe = true
        var date = today()
        var refresh: () -> Unit = {}

        val (amountBox, _, amount) = amountField(this, null)
        root.addView(amountBox, lp(bottom = dp(12)))
        val note = input(this, "What was it for?", "")
        root.addView(note, lp(bottom = dp(14)))

        root.addView(label(this, "Split with"), lp(bottom = dp(6)))
        lateinit var who: PersonField
        fun addPerson(c: Contacts.Contact) {
            val n = c.name.trim().capFirst()
            if (n.isEmpty()) return
            people[personKey(n)] = Contacts.Contact(n, c.phone)
            who.field.setText("")
            refresh()
        }
        who = personField(this, "", "Add a person", ime = EditorInfo.IME_ACTION_DONE) { addPerson(it) }
        who.field.setOnEditorActionListener { _, _, _ -> addPerson(Contacts.Contact(who.field.text.toString(), "")); true }
        root.addView(who.box, lp(bottom = dp(8)))
        val peopleRow = hbox()
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(peopleRow) }, lp(bottom = dp(14)))

        root.addView(label(this, "Paid by"), lp(bottom = dp(6)))
        val paidRow = hbox()
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(paidRow) }, lp(bottom = dp(10)))

        val meBtn = chip("")
        root.addView(meBtn, lp(WRAP, WRAP, bottom = dp(12)))

        val (dateRow, refreshDate) = dateRow(this, { date }) { date = it; refresh() }
        root.addView(dateRow, lp(bottom = dp(12)))

        val summary = text("", 12.5f, C.MUTED)
        root.addView(summary, lp(bottom = dp(8)))
        val err = text("", 12.5f, C.RED).apply { visibility = View.GONE }
        root.addView(err, lp(bottom = dp(8)))
        val save = text("Save split", 15f, C.BG, 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = bg(C.TEXT, dp(14f))
        }
        root.addView(save)

        fun share(): Double? {
            val total = amount.text.toString().replace(",", "").toDoubleOrNull() ?: return null
            val n = people.size + if (includeMe) 1 else 0
            return if (total > 0 && n > 0) round(total / n * 100) / 100 else null
        }

        refresh = {
            if (paidBy != null && paidBy !in people) paidBy = null
            peopleRow.removeAllViews()
            for ((k, c) in people) peopleRow.addView(chip("${c.name}  ✕").apply {
                styleChip(this, true, C.CARD2, C.TEXT)
                setOnClickListener { people.remove(k); refresh() }
            }, lp(WRAP, WRAP, end = dp(8)))
            if (people.isEmpty()) peopleRow.addView(text("No one added yet", 12f, C.DIM))
            paidRow.removeAllViews()
            (listOf<Pair<String?, String>>(null to "You") + people.map { it.key to it.value.name }).forEach { (k, n) ->
                paidRow.addView(chip(n).apply { styleChip(this, paidBy == k); setOnClickListener { paidBy = k; refresh() } }, lp(WRAP, WRAP, end = dp(8)))
            }
            meBtn.text = (if (includeMe) "✓  " else "○  ") + "Include me in the split"
            styleChip(meBtn, includeMe, C.CARD2, C.TEXT)
            refreshDate()

            val s = share()
            val names = people.values.map { it.name }
            summary.text = when {
                s == null || people.isEmpty() -> "Add an amount and at least one person — it's split equally"
                paidBy == null -> "${money(s, false)} each · ${names.joinToString(", ")} will owe you ${money(s * names.size, false)} in total"
                !includeMe -> "You're not in this split, so there's nothing to record for you"
                else -> "${money(s, false)} each · you'll owe ${people[paidBy]!!.name} ${money(s, false)}"
            }
        }

        meBtn.setOnClickListener { includeMe = !includeMe; refresh() }
        amount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
        })
        save.setOnClickListener {
            // A name typed but not yet added still counts
            who.field.text.toString().takeIf { it.isNotBlank() }?.let { addPerson(Contacts.Contact(it, "")) }
            val s = share()
            val problem = when {
                s == null -> "Enter a valid amount"
                people.isEmpty() -> "Add at least one person"
                paidBy != null && !includeMe -> "Nothing to record — you're not in this split"
                else -> null
            }
            if (problem != null) { err.text = problem; err.visibility = View.VISIBLE; return@setOnClickListener }
            val what = note.text.toString().trim().capFirst()
            val n = "Split" + if (what.isNotEmpty()) " · $what" else ""
            val entries = if (paidBy == null) people.values.map { Debt("", it.name, it.phone, s!!, "gave", n, date.toString(), "") }
            else people[paidBy]!!.let { listOf(Debt("", it.name, it.phone, s!!, "got", n, date.toString(), "")) }
            Ledger.add(this, entries)
            onSaved(if (paidBy == null) "Split saved · ${entries.size} owe you ${money(s!!, false)} each" else "Split saved · you owe ${entries[0].person} ${money(s!!, false)}")
            dialog.dismiss()
        }
        refresh()
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
        dialog.setContentView(root)
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
