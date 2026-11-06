package com.sanchit.expenses

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** A note: one block of text; [bold] holds the bold stretches as [start, end) character ranges. */
data class NoteText(val text: String, val bold: List<IntRange> = emptyList()) {
    val isBlank get() = text.isBlank()

    fun toJson(): JSONObject = JSONObject().put("text", text)
        .put("bold", JSONArray().apply { for (r in bold) put(JSONArray().put(r.first).put(r.last + 1)) })

    companion object {
        fun fromJson(o: JSONObject): NoteText {
            val text = o.optString("text")
            val arr = o.optJSONArray("bold") ?: JSONArray()
            val bold = (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONArray(i) ?: return@mapNotNull null
                val s = r.optInt(0, -1).coerceIn(0, text.length)
                val e = r.optInt(1, -1).coerceIn(0, text.length)
                if (e > s) s until e else null
            }
            return NoteText(text, bold)
        }
    }
}

/**
 * Notes: free text with bold, one JSON file per note in app-private storage (`note_<id>.json`).
 * Each note is a "list" in [books], so it gets the same switch / rename / delete / widget handling.
 */
object Notes {
    private const val BASE = "note"
    private val notes = HashMap<String, NoteText>()

    val books = Books("textnotes", "Note")

    private fun file(ctx: Context, id: String) = AtomicFile(File(ctx.filesDir, books.fileName(BASE, id)))

    @Synchronized
    fun get(ctx: Context, id: String): NoteText {
        notes[id]?.let { return it }
        val f = file(ctx, id)
        val n = if (f.baseFile.exists()) runCatching { NoteText.fromJson(JSONObject(String(f.readFully(), Charsets.UTF_8))) }.getOrNull() else null
        return (n ?: NoteText("")).also { notes[id] = it }
    }

    @Synchronized
    fun set(ctx: Context, id: String, note: NoteText) {
        // A late save from an editor whose note was just deleted mustn't bring the file back
        if (!books.has(ctx, id) || get(ctx, id) == note) return
        notes[id] = note
        val f = file(ctx, id)
        val out = f.startWrite()
        try {
            out.write(note.toJson().put("format", "expenses-note").put("version", 1).put("updatedAt", Instant.now().toString())
                .toString(2).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    /** Empties a note (it stays, blank). Returns whether there was anything to remove. */
    fun clear(ctx: Context, id: String): Boolean {
        val had = !get(ctx, id).isBlank
        set(ctx, id, NoteText(""))
        return had
    }

    /** Deletes a whole note and its file. */
    @Synchronized
    fun deleteNote(ctx: Context, id: String) {
        books.remove(ctx, id)
        notes.remove(id)
        file(ctx, id).delete()
        Widgets.refresh(ctx)
    }
}

/** Bold as StyleSpans on editable text <-> plain ranges for storage. */
object RichText {
    private fun isBold(s: Any) = s is StyleSpan && s.style == Typeface.BOLD

    fun mask(t: Spanned): BooleanArray {
        val m = BooleanArray(t.length)
        for (s in t.getSpans(0, t.length, StyleSpan::class.java)) if (isBold(s)) for (i in t.getSpanStart(s) until t.getSpanEnd(s)) m[i] = true
        return m
    }

    fun ranges(m: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < m.size) {
            if (!m[i]) { i++; continue }
            val s = i
            while (i < m.size && m[i]) i++
            out += s until i
        }
        return out
    }

    fun ranges(t: Spanned) = ranges(mask(t))

    /** Replaces the bold spans of [e] with exactly the stretches marked in [m]. */
    private fun apply(e: Editable, m: BooleanArray) {
        for (s in e.getSpans(0, e.length, StyleSpan::class.java)) if (isBold(s)) e.removeSpan(s)
        for (r in ranges(m)) e.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun setBold(e: Editable, start: Int, end: Int, on: Boolean) {
        val m = mask(e)
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(m.size)) m[i] = on
        apply(e, m)
    }

    fun allBold(t: Spanned, start: Int, end: Int): Boolean {
        val m = mask(t)
        return (start until end).all { m.getOrElse(it) { false } }
    }

    fun spanned(n: NoteText): SpannableString = SpannableString(n.text).apply {
        for (r in n.bold) if (r.last < n.text.length) setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** The note editor: a Bold button over a text box. Saves as you type (and on [Editor.flush]). */
object NoteEditor {
    class Editor(val view: View, val input: EditText, val flush: () -> Unit)

    fun build(ctx: Context, id: String, minLines: Int): Editor = with(ctx) {
        var typingBold = false
        var boldBtn: TextView? = null
        var input: EditText? = null

        fun refreshButton() {
            val e = input ?: return
            val s = e.selectionStart
            val en = e.selectionEnd
            val on = if (s >= 0 && en > s) RichText.allBold(e.text, s, en) else typingBold
            boldBtn?.let { styleChip(it, on) }
        }

        val edit = object : EditText(ctx) {
            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                super.onSelectionChanged(selStart, selEnd)
                val t: Editable = text ?: return // also called while the view is still being set up
                // A plain cursor carries on in the style of the character before it
                if (selStart == selEnd) typingBold = selStart > 0 && RichText.mask(t).getOrElse(selStart - 1) { false }
                refreshButton()
            }
        }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            this.minLines = minLines
            gravity = Gravity.TOP or Gravity.START
            hint = "Write anything…"
            textSize = 15.5f
            setLineSpacing(0f, 1.15f)
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = null
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setText(RichText.spanned(Notes.get(ctx, id)))
        }
        input = edit

        val save = Runnable { Notes.set(ctx, id, NoteText(edit.text.toString(), RichText.ranges(edit.text))) }
        fun later() { edit.removeCallbacks(save); edit.postDelayed(save, 500) }

        edit.addTextChangedListener(object : TextWatcher {
            private var start = 0
            private var count = 0
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { this.start = start; this.count = count }
            override fun afterTextChanged(e: Editable) {
                if (count > 0) RichText.setBold(e, start, start + count, typingBold)
                later()
            }
        })

        val bar = hbox().apply { setPadding(dp(10), dp(10), dp(10), 0) }
        val b = chip("B").apply {
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(44)
            setOnClickListener {
                val s = edit.selectionStart
                val e = edit.selectionEnd
                if (s >= 0 && e > s) { RichText.setBold(edit.text, s, e, !RichText.allBold(edit.text, s, e)); later() }
                else typingBold = !typingBold
                refreshButton()
            }
        }
        boldBtn = b
        bar.addView(b)
        bar.addView(text("Select text and tap B, or tap B and type", 11f, C.DIM), lp(0, WRAP, 1f, start = dp(10)))
        refreshButton()

        val box = vbox().apply { background = bg(C.SURFACE, dp(18f), dp(1), C.LINE) }
        box.addView(bar)
        box.addView(edit, lp())
        Editor(box, edit) { edit.removeCallbacks(save); save.run() }
    }

    /** Bottom sheet editing note [id] over the home screen; used by the Note widget. */
    fun showSheet(act: Activity, id: String, onDismiss: () -> Unit) = with(act) {
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val r = dp(24f)
        val root = vbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(0xFF121212.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(this).apply { background = bg(C.CARD2, dp(2f)) }, lp(dp(36), dp(4), bottom = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = hbox()
        head.addView(text(Notes.books.get(this, id).name, 17f, C.TEXT, 2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f, start = dp(4)))
        head.addView(text("Done", 14f, C.GREEN, 2).apply { setPadding(dp(12), dp(6), dp(6), dp(6)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, lp(bottom = dp(12)))

        val editor = build(this, id, minLines = 6)
        // Long notes scroll inside the sheet instead of pushing it off screen
        editor.input.maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        editor.input.isVerticalScrollBarEnabled = true
        root.addView(editor.view, LinearLayout.LayoutParams(MATCH, WRAP))

        dialog.setContentView(root)
        dialog.setOnDismissListener { editor.flush(); onDismiss() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.65f)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        editor.input.requestFocus()
        editor.input.setSelection(editor.input.text.length)
    }
}
