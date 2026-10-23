package com.sanchit.expenses

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView

/** Bottom-sheet form to add a new entry, or edit/delete [existing]. Used by the app and the widget popup. */
object EntryDialog {
    fun show(
        act: Activity,
        existing: Expense?,
        initialType: String = "spent",
        onDismiss: (() -> Unit)? = null,
        onSaved: (message: String) -> Unit,
    ) = with(act) {
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        var type = existing?.type ?: initialType
        val cats = Store.categories(this)
        var category = existing?.category ?: cats.first()
        var date = existing?.date?.let(::parseDate) ?: today()
        var refresh: () -> Unit = {}

        val r = dp(24f)
        val root = vbox().apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(0xFF121212.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(this).apply { background = bg(C.CARD2, dp(2f)) }, lp(dp(36), dp(4), top = 0, bottom = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Title
        val head = hbox()
        head.addView(text(if (existing == null) "New entry" else "Edit entry", 17f, C.TEXT, 2), lp(0, WRAP, 1f))
        head.addView(text("✕", 16f, C.MUTED).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, lp(bottom = dp(14)))

        // Spent / Received
        val seg = hbox().apply { background = bg(C.CARD, dp(12f), dp(1), C.LINE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val spentBtn = chip("↓  Spent")
        val recvBtn = chip("↑  Received")
        seg.addView(spentBtn, lp(0, WRAP, 1f))
        seg.addView(recvBtn, lp(0, WRAP, 1f))
        root.addView(seg, lp(bottom = dp(14)))

        // Amount
        val amountBox = hbox().apply { background = bg(C.CARD, dp(14f), dp(1), C.LINE); setPadding(dp(16), 0, dp(12), 0) }
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
            existing?.let { setText(if (it.amount % 1.0 == 0.0) it.amount.toLong().toString() else it.amount.toString()) }
            setSelection(text.length)
        }
        amountBox.addView(cur)
        amountBox.addView(amount, lp(0, WRAP, 1f))
        root.addView(amountBox, lp(bottom = dp(14)))

        // Category chips
        val chipRow = hbox()
        val chips = cats.map { c ->
            chip(c).also { v -> v.setOnClickListener { category = c; refresh() }; chipRow.addView(v, lp(WRAP, WRAP, end = dp(8))) } to c
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, lp(bottom = dp(14)))

        // Note
        val note = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Note — where / what"
            textSize = 15f
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = bg(C.CARD, dp(14f), dp(1), C.LINE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setText(existing?.note ?: "")
        }
        root.addView(note, lp(bottom = dp(14)))

        // Date
        val dateRow = hbox()
        val dateBtn = chip("").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val todayBtn = chip("Today")
        val yestBtn = chip("Yesterday")
        dateRow.addView(dateBtn, lp(0, WRAP, 1f, end = dp(8)))
        dateRow.addView(todayBtn, lp(WRAP, WRAP, end = dp(8)))
        dateRow.addView(yestBtn, lp(WRAP, WRAP))
        root.addView(dateRow, lp(bottom = dp(12)))

        val err = text("", 12.5f, C.RED).apply { visibility = View.GONE }
        root.addView(err, lp(bottom = dp(8)))

        // Actions
        val actions = hbox()
        if (existing != null) {
            val del = text("Delete", 15f, C.RED, 1).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = bg(withAlpha(C.RED, 0x1F), dp(14f))
                setOnClickListener {
                    AlertDialog.Builder(this@with).setTitle("Delete this entry?")
                        .setMessage("${existing.note.ifBlank { existing.category }.capFirst()} · ${money(existing.amount, false)}")
                        .setPositiveButton("Delete") { _, _ ->
                            Store.delete(this@with, existing.id)
                            onSaved("Entry deleted")
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            actions.addView(del, lp(WRAP, WRAP, end = dp(10)))
        }
        val save = text("", 15f, C.BG, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) }
        actions.addView(save, lp(0, WRAP, 1f))
        root.addView(actions)

        fun submit() {
            val a = amount.text.toString().replace(",", "").toDoubleOrNull()
            if (a == null || a <= 0) {
                err.text = "Enter a valid amount"
                err.visibility = View.VISIBLE
                amount.requestFocus()
                return
            }
            val n = note.text.toString().trim().capFirst()
            if (existing == null) Store.add(this, a, category, n, date.toString(), type)
            else Store.update(this, existing.copy(amount = a, category = category, note = n, date = date.toString(), type = type))
            onSaved(if (existing == null) "Added ${money(a, false)} · ${if (type == "received") "Received" else category}" else "Entry updated")
            dialog.dismiss()
        }

        refresh = {
            val recv = type == "received"
            styleChip(spentBtn, !recv, C.CARD2, C.TEXT)
            styleChip(recvBtn, recv, withAlpha(C.GREEN, 0x33), C.GREEN)
            spentBtn.background = if (!recv) bg(C.CARD2, dp(9f)) else null
            recvBtn.background = if (recv) bg(withAlpha(C.GREEN, 0x33), dp(9f)) else null
            cur.setTextColor(if (recv) C.GREEN else C.MUTED)
            for ((v, c) in chips) styleChip(v, c == category)
            dateBtn.text = "Date   ${dayLabel(date.toString())}"
            styleChip(dateBtn, false)
            dateBtn.setTextColor(C.TEXT)
            styleChip(todayBtn, date == today(), C.CARD2, C.TEXT)
            styleChip(yestBtn, date == today().minusDays(1), C.CARD2, C.TEXT)
            save.text = if (existing != null) "Save changes" else if (recv) "Save received" else "Save expense"
            save.setTextColor(if (recv) Color.WHITE else C.BG)
            save.background = bg(if (recv) C.GREEN_D else C.TEXT, dp(14f))
        }

        spentBtn.setOnClickListener { type = "spent"; refresh() }
        recvBtn.setOnClickListener { type = "received"; refresh() }
        dateBtn.setOnClickListener { pickDate(date) { date = it; refresh() } }
        todayBtn.setOnClickListener { date = today(); refresh() }
        yestBtn.setOnClickListener { date = today().minusDays(1); refresh() }
        save.setOnClickListener { submit() }
        note.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        refresh()

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.65f)
            setSoftInputMode(
                (if (existing == null) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
                    or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        if (existing == null) amount.requestFocus()
    }
}
