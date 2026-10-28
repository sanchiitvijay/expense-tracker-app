package com.sanchit.expenses

import android.app.Activity
import android.app.AlertDialog
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
import kotlin.random.Random

/** Something you need to order. Removed from the list once ordered. */
data class WishItem(val id: String, val name: String, val important: Boolean, val createdAt: String) {
    /** Local date it was added (createdAt is UTC). */
    val added get() = runCatching { Instant.parse(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault("")
}

/** Important first, then newest first. */
val WISH_ORDER = compareByDescending<WishItem> { it.important }.thenByDescending { it.createdAt }

/** Local storage for the wishlist: one JSON file in app-private storage, separate from expenses and people. */
object Wishlist {
    private var items: MutableList<WishItem>? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "wishlist.json"))

    private fun data(ctx: Context): MutableList<WishItem> {
        items?.let { return it }
        val f = file(ctx)
        val list = if (f.baseFile.exists()) runCatching {
            fromArray(JSONObject(String(f.readFully(), Charsets.UTF_8)).optJSONArray("items") ?: JSONArray())
        }.getOrDefault(emptyList()) else emptyList()
        return list.toMutableList().also { items = it }
    }

    private fun save(ctx: Context) {
        val arr = toArray(data(ctx))
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(JSONObject().put("format", "expenses-wishlist").put("version", 1).put("items", arr).toString(2).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    /** Same shape in the app file and in the JSON backup. */
    fun toArray(list: List<WishItem>) = JSONArray().apply {
        for (w in list.sortedWith(WISH_ORDER)) put(JSONObject().put("id", w.id).put("name", w.name).put("important", w.important).put("createdAt", w.createdAt))
    }

    fun fromArray(arr: JSONArray): List<WishItem> = (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { WishItem(it.optString("id"), it.optString("name").trim(), it.optBoolean("important"), it.optString("createdAt")) }
            ?.takeIf { it.name.isNotBlank() }
    }

    /** Adds items whose id isn't already stored. Returns how many were added. */
    @Synchronized
    fun import(ctx: Context, list: List<WishItem>): Int {
        val ids = data(ctx).mapTo(HashSet()) { it.id }
        val fresh = list.filter { it.id.isBlank() || it.id !in ids }.map {
            it.copy(id = it.id.ifBlank { newId() }, createdAt = it.createdAt.ifBlank { Instant.now().toString() })
        }
        if (fresh.isNotEmpty()) { data(ctx).addAll(fresh); save(ctx) }
        return fresh.size
    }

    private fun newId() = java.lang.Long.toHexString(System.currentTimeMillis()) + (1..8).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    @Synchronized
    fun all(ctx: Context): List<WishItem> = data(ctx).sortedWith(WISH_ORDER)

    @Synchronized
    fun add(ctx: Context, name: String, important: Boolean) {
        data(ctx).add(WishItem(newId(), name.trim().capFirst(), important, Instant.now().toString()))
        save(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        data(ctx).removeAll { it.id == id }
        save(ctx)
    }

    /** Bottom sheet to add an item. Used by the app and the Wishlist widget popup. */
    fun showAdd(act: Activity, onDismiss: (() -> Unit)? = null, onSaved: (message: String) -> Unit) = with(act) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        var important = false
        val r = dp(24f)
        val root = vbox().apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(0xFF121212.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(this).apply { background = bg(C.CARD2, dp(2f)) }, lp(dp(36), dp(4), bottom = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = hbox()
        head.addView(text("Add to wishlist", 17f, C.TEXT, 2), lp(0, WRAP, 1f))
        head.addView(text("✕", 16f, C.MUTED).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, lp(bottom = dp(14)))

        val name = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "What do you need?"
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
            add(this, n, important)
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

    /** "Ordered it?" confirmation — removes the item. */
    fun confirmOrdered(act: Activity, item: WishItem, onDismiss: (() -> Unit)? = null, onDone: (message: String) -> Unit) {
        AlertDialog.Builder(act).setTitle("Ordered ${item.name}?")
            .setMessage("It will be removed from your wishlist.")
            .setPositiveButton("Ordered") { _, _ -> delete(act, item.id); onDone("${item.name} removed") }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }
}
