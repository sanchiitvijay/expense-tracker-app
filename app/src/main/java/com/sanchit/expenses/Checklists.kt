package com.sanchit.expenses

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * An item on a checklist. Ticking it off ([done]) keeps it on the list, struck through, until it's cleared.
 * [list] is only set on items read from a full backup: the id of the checklist they came from.
 */
data class CheckItem(
    val id: String,
    val name: String,
    val important: Boolean,
    val createdAt: String,
    val done: Boolean = false,
    val list: String = "",
)

/** Still to do first, important first, then in the order they were added. */
val CHECK_ORDER = compareBy<CheckItem> { it.done }.thenByDescending { it.important }.thenBy { it.createdAt }

/**
 * Checklists: one JSON file per checklist in app-private storage (`checklist_<id>.json`), separate from
 * expenses and people. They replace the old Wishlist and Ideas tabs, whose lists become checklists
 * the first time this runs.
 */
object Checklists {
    private const val BASE = "checklist"
    private val items = HashMap<String, MutableList<CheckItem>>()

    /** Old sections that become checklists: id prefix, list kind, file base name, name of their first list. */
    private val OLD = listOf(
        listOf("w", "wishlist", "wishlist", "Wishlist"),
        listOf("i", "notes", "ideas", "Ideas"),
    )

    val books = Books("checklists", "Checklist", migrate = ::migrate, afterMigrate = ::removeOld)

    /** Every wishlist and Ideas list that was used becomes a checklist; ids get a prefix so the two can't clash. */
    private fun migrate(ctx: Context): List<Book> {
        val out = ArrayList<Book>()
        for ((prefix, kind, base, name) in OLD) {
            val hadLists = File(ctx.filesDir, "lists_$kind.json").exists()
            val old = Books(kind, name)
            for (b in old.all(ctx)) {
                val src = File(ctx.filesDir, old.fileName(base, b.id))
                if (!src.exists() && !hadLists) continue // never used
                val id = oldId(prefix, b.id)
                if (src.exists()) src.copyTo(File(ctx.filesDir, books.fileName(BASE, id)), overwrite = true)
                out += Book(id, b.name)
            }
        }
        return out
    }

    private fun removeOld(ctx: Context) {
        for ((_, kind, base, name) in OLD) {
            val old = Books(kind, name)
            for (b in old.all(ctx)) File(ctx.filesDir, old.fileName(base, b.id)).delete()
            File(ctx.filesDir, "lists_$kind.json").delete()
        }
    }

    /** Id of a checklist made from an old wishlist ("w") or Ideas ("i") list — also used for old backups. */
    fun oldId(prefix: String, id: String) = prefix + id.ifBlank { Books.MAIN }

    private fun file(ctx: Context, list: String) = AtomicFile(File(ctx.filesDir, books.fileName(BASE, list)))

    private fun data(ctx: Context, list: String): MutableList<CheckItem> {
        items[list]?.let { return it }
        val f = file(ctx, list)
        val loaded = if (f.baseFile.exists()) runCatching {
            fromArray(JSONObject(String(f.readFully(), Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()).map { it.copy(list = "") }
        }.getOrDefault(emptyList()) else emptyList()
        return loaded.toMutableList().also { items[list] = it }
    }

    private fun save(ctx: Context, list: String) {
        val arr = toArray(data(ctx, list))
        val f = file(ctx, list)
        val out = f.startWrite()
        try {
            out.write(JSONObject().put("format", "expenses-checklist").put("version", 1).put("items", arr).toString(2).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    /** Same shape in the app file and in the JSON backup (where each item also carries its [list] id). */
    fun toArray(list: List<CheckItem>, listId: String? = null) = JSONArray().apply {
        for (w in list.sortedWith(CHECK_ORDER)) put(
            JSONObject().put("id", w.id).put("name", w.name).put("important", w.important).put("done", w.done).put("createdAt", w.createdAt)
                .apply { if (listId != null) put("list", listId) }
        )
    }

    /** Also reads old wishlist / Ideas items (no "done"). */
    fun fromArray(arr: JSONArray): List<CheckItem> = (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let {
            CheckItem(it.optString("id"), it.optString("name").trim(), it.optBoolean("important"), it.optString("createdAt"), it.optBoolean("done"), it.optString("list"))
        }?.takeIf { it.name.isNotBlank() }
    }

    /** Adds items to [list] whose id isn't already stored there. Returns how many were added. */
    @Synchronized
    fun import(ctx: Context, list: String, records: List<CheckItem>): Int {
        val ids = data(ctx, list).mapTo(HashSet()) { it.id }
        val fresh = records.filter { it.id.isBlank() || it.id !in ids }.map {
            it.copy(id = it.id.ifBlank { newId() }, createdAt = it.createdAt.ifBlank { Instant.now().toString() }, list = "")
        }
        if (fresh.isNotEmpty()) { data(ctx, list).addAll(fresh); save(ctx, list) }
        return fresh.size
    }

    @Synchronized
    fun all(ctx: Context, list: String): List<CheckItem> = data(ctx, list).sortedWith(CHECK_ORDER)

    /** Every item in every checklist (for totals and erase-everything). */
    fun everything(ctx: Context): List<CheckItem> = books.all(ctx).flatMap { all(ctx, it.id) }

    @Synchronized
    fun add(ctx: Context, list: String, name: String, important: Boolean) {
        data(ctx, list).add(CheckItem(newId(), name.trim().capFirst(), important, Instant.now().toString()))
        save(ctx, list)
    }

    private fun change(ctx: Context, list: String, id: String, f: (CheckItem) -> CheckItem) {
        val l = data(ctx, list)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        l[i] = f(l[i])
        save(ctx, list)
    }

    @Synchronized
    fun toggle(ctx: Context, list: String, id: String) = change(ctx, list, id) { it.copy(done = !it.done) }

    @Synchronized
    fun setImportant(ctx: Context, list: String, id: String, on: Boolean) = change(ctx, list, id) { it.copy(important = on) }

    @Synchronized
    fun rename(ctx: Context, list: String, id: String, name: String) = change(ctx, list, id) { it.copy(name = name.trim().capFirst()) }

    @Synchronized
    fun delete(ctx: Context, list: String, id: String) {
        data(ctx, list).removeAll { it.id == id }
        save(ctx, list)
    }

    /** Removes the ticked-off items of [list]. Returns how many were removed. */
    @Synchronized
    fun clearDone(ctx: Context, list: String): Int {
        val l = data(ctx, list)
        val n = l.count { it.done }
        if (n > 0) { l.removeAll { it.done }; save(ctx, list) }
        return n
    }

    /** Removes every item of [list]. Returns how many were removed. */
    @Synchronized
    fun clear(ctx: Context, list: String): Int {
        val n = data(ctx, list).size
        if (n > 0) { data(ctx, list).clear(); save(ctx, list) }
        return n
    }

    /** Deletes a whole checklist and its file. */
    @Synchronized
    fun deleteList(ctx: Context, list: String) {
        books.remove(ctx, list)
        items.remove(list)
        file(ctx, list).delete()
        Widgets.refresh(ctx)
    }

    /** Bottom sheet to add an item to [list]. Used by the app and the Checklist widget popup. */
    fun showAdd(act: Activity, list: String, onDismiss: (() -> Unit)? = null, onSaved: (message: String) -> Unit) = with(act) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        var important = false
        val r = dp(24f)
        val root = vbox().apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(0xFF121212.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(this).apply { background = bg(C.CARD2, dp(2f)) }, lp(dp(36), dp(4), bottom = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = hbox()
        head.addView(text("Add to ${books.get(this, list).name}", 17f, C.TEXT, 2).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f))
        head.addView(text("✕", 16f, C.MUTED).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, lp(bottom = dp(14)))

        val name = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Add an item"
            textSize = 16f
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = bg(C.CARD, dp(14f), dp(1), C.LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(name, lp(bottom = dp(12)))

        val star = chip("")
        root.addView(star, lp(WRAP, WRAP, bottom = dp(12)))
        fun refresh() {
            star.text = if (important) "★  Important" else "☆  Mark as important"
            styleChip(star, important, withAlpha(C.AMBER, 0x33), C.AMBER)
        }
        star.setOnClickListener { important = !important; refresh() }
        refresh()

        val err = text("Enter an item", 12.5f, C.RED).apply { visibility = View.GONE }
        root.addView(err, lp(bottom = dp(8)))
        val save = text("Add item", 15f, C.BG, 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = bg(C.TEXT, dp(14f))
        }
        root.addView(save)

        fun submit() {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { err.visibility = View.VISIBLE; return }
            add(this, list, n, important)
            onSaved("Added ${n.capFirst()}${if (important) " · important" else ""}")
            dialog.dismiss()
        }
        save.setOnClickListener { submit() }
        name.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.65f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        name.requestFocus()
    }
}
