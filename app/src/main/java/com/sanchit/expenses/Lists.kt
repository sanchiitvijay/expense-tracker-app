package com.sanchit.expenses

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** A named list the user created: an expense list, a checklist or a note. */
data class Book(val id: String, val name: String)

fun newId(): String =
    java.lang.Long.toHexString(System.currentTimeMillis()) + (1..8).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

/**
 * The named lists of one section (expenses, checklists or notes), in creation order. There is always at
 * least one. The first list ([MAIN]) keeps the section's original file, so data from before lists existed
 * stays where it was. Also remembers which list is open in the app and which one each widget shows.
 *
 * [migrate] runs once, while the section has no list file yet: it returns lists built from older data
 * (empty if there's none); [afterMigrate] runs once those are saved, to remove the old files.
 */
class Books(
    private val kind: String,
    private val defaultName: String,
    private val migrate: ((Context) -> List<Book>)? = null,
    private val afterMigrate: ((Context) -> Unit)? = null,
) {
    private var items: MutableList<Book>? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "lists_$kind.json"))
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun data(ctx: Context): MutableList<Book> {
        items?.let { return it }
        val f = file(ctx)
        if (!f.baseFile.exists() && migrate != null) {
            val old = migrate.invoke(ctx)
            if (old.isNotEmpty()) {
                items = old.toMutableList()
                save(ctx)
                afterMigrate?.invoke(ctx)
                return items!!
            }
        }
        val list = if (f.baseFile.exists()) runCatching {
            fromArray(JSONObject(String(f.readFully(), Charsets.UTF_8)).optJSONArray("lists") ?: JSONArray())
        }.getOrDefault(emptyList()) else emptyList()
        return list.ifEmpty { listOf(Book(MAIN, defaultName)) }.toMutableList().also { items = it }
    }

    private fun save(ctx: Context) {
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(JSONObject().put("lists", toArray(data(ctx))).toString(2).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    fun toArray(list: List<Book>) = JSONArray().apply { for (b in list) put(JSONObject().put("id", b.id).put("name", b.name)) }

    fun fromArray(arr: JSONArray): List<Book> = (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { Book(it.optString("id"), it.optString("name").trim()) }?.takeIf { validId(it.id) && it.name.isNotBlank() }
    }

    @Synchronized
    fun all(ctx: Context): List<Book> = data(ctx).toList()

    /** The list with [id], or the first one if it no longer exists. */
    @Synchronized
    fun get(ctx: Context, id: String?): Book = data(ctx).firstOrNull { it.id == id } ?: data(ctx).first()

    fun has(ctx: Context, id: String) = all(ctx).any { it.id == id }

    /** File for a list's data: the original file for the first list, its own file for the others. */
    fun fileName(base: String, id: String) = if (id == MAIN) "$base.json" else "${base}_$id.json"

    @Synchronized
    fun create(ctx: Context, name: String, id: String = newId()): Book {
        val b = Book(id, name.trim().capFirst())
        data(ctx).add(b)
        save(ctx)
        return b
    }

    /** Adds the list from a backup unless one with its id is already here. */
    fun ensure(ctx: Context, b: Book): Book = if (has(ctx, b.id)) get(ctx, b.id) else create(ctx, b.name, b.id)

    @Synchronized
    fun rename(ctx: Context, id: String, name: String) {
        val list = data(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(name = name.trim().capFirst())
        save(ctx)
    }

    /** Removes the list from the registry; the caller deletes its data. Keeps at least one list. */
    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = data(ctx)
        if (list.size > 1 && list.removeAll { it.id == id }) save(ctx)
    }

    // ── Which list is open in the app ──
    fun current(ctx: Context): Book = get(ctx, prefs(ctx).getString("list_$kind", null))

    fun select(ctx: Context, id: String) = prefs(ctx).edit().putString("list_$kind", id).apply()

    // ── Which list each home-screen widget shows ──
    fun forWidget(ctx: Context, widgetId: Int): Book = get(ctx, prefs(ctx).getString("widget_$widgetId", null))

    fun setWidget(ctx: Context, widgetId: Int, id: String) = prefs(ctx).edit().putString("widget_$widgetId", id).apply()

    /** A widget header: [single] while there's only one list, the list's name (plus [suffix]) once there are more to tell apart. */
    fun label(ctx: Context, b: Book, single: String, suffix: String = "") = if (all(ctx).size > 1) b.name + suffix else single

    companion object {
        const val MAIN = "main"

        /** Ids end up in file names, so only plain ones are accepted (also from backups). */
        fun validId(id: String) = id.isNotEmpty() && id.length <= 40 && id.all { it in 'a'..'z' || it in '0'..'9' }

        fun forgetWidget(ctx: Context, widgetId: Int) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("widget_$widgetId").apply()
    }
}
