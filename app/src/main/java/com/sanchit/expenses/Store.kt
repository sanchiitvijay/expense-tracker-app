package com.sanchit.expenses

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Same schema as the web app: id, amount, category, note, date (YYYY-MM-DD), type (spent|received), createdAt (ISO).
 * [list] is only set on records read from a full backup: the id of the expense list they came from.
 */
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String,
    val date: String,
    val type: String,
    val createdAt: String,
    val list: String = "",
) {
    val received get() = type == "received"
    val month get() = date.take(7)
}

class Totals(val spent: Double, val received: Double) {
    val net get() = spent - received
}

fun List<Expense>.totals(): Totals {
    var s = 0.0
    var r = 0.0
    for (e in this) if (e.received) r += e.amount else s += e.amount
    return Totals(s, r)
}

/** Newest expense date first; same-day entries by creation time, newest first. */
val EXPENSE_ORDER = compareByDescending<Expense> { it.date }.thenByDescending { it.createdAt }

/**
 * Local storage: one JSON file per expense list in app-private storage, in the same format as the export.
 * The first list keeps the original `expenses.json`.
 */
object Store {
    private val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Entertainment", "Health", "Other")
    private val items = HashMap<String, MutableList<Expense>>()

    val books = Books("expenses", "Main")

    private fun file(ctx: Context, book: String) = AtomicFile(File(ctx.filesDir, books.fileName("expenses", book)))

    private fun data(ctx: Context, book: String): MutableList<Expense> {
        items[book]?.let { return it }
        val f = file(ctx, book)
        val list = if (f.baseFile.exists()) {
            runCatching { Backup.parse(String(f.readFully(), Charsets.UTF_8)).valid.map { complete(it).copy(list = "") } }.getOrDefault(emptyList())
        } else emptyList()
        return list.toMutableList().also { items[book] = it }
    }

    private fun save(ctx: Context, book: String) {
        val f = file(ctx, book)
        val out = f.startWrite()
        try {
            out.write(Backup.toJson(data(ctx, book)).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    private fun complete(e: Expense) = e.copy(
        id = e.id.ifBlank { newId() },
        createdAt = e.createdAt.ifBlank { Instant.now().toString() },
    )

    @Synchronized
    fun all(ctx: Context, book: String): List<Expense> = data(ctx, book).sortedWith(EXPENSE_ORDER)

    /** Every entry in every list (for totals and erase-everything). */
    fun everything(ctx: Context): List<Expense> = books.all(ctx).flatMap { all(ctx, it.id) }

    @Synchronized
    fun categories(ctx: Context, book: String): List<String> {
        val used = data(ctx, book).map { it.category }
        return (DEFAULT_CATEGORIES.dropLast(1) + used + "Other").distinct()
    }

    @Synchronized
    fun add(ctx: Context, book: String, amount: Double, category: String, note: String, date: String, type: String): Expense {
        val e = complete(Expense("", amount, category, note, date, type, ""))
        data(ctx, book).add(e)
        save(ctx, book)
        return e
    }

    @Synchronized
    fun update(ctx: Context, book: String, e: Expense) {
        val list = data(ctx, book)
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e
        save(ctx, book)
    }

    @Synchronized
    fun delete(ctx: Context, book: String, id: String) {
        data(ctx, book).removeAll { it.id == id }
        save(ctx, book)
    }

    /** Remove every entry of [book] matching [predicate]. Returns how many were removed. */
    @Synchronized
    fun purge(ctx: Context, book: String, predicate: (Expense) -> Boolean): Int {
        val list = data(ctx, book)
        val before = list.size
        list.removeAll(predicate)
        val removed = before - list.size
        if (removed > 0) save(ctx, book)
        return removed
    }

    /** Deletes a whole list and its file. */
    @Synchronized
    fun deleteList(ctx: Context, book: String) {
        books.remove(ctx, book)
        items.remove(book)
        file(ctx, book).delete()
        Widgets.refresh(ctx)
    }

    /** Adds entries to [book] whose id isn't already stored there. Returns how many were added. */
    @Synchronized
    fun import(ctx: Context, book: String, records: List<Expense>): Int {
        val list = data(ctx, book)
        val ids = list.mapTo(HashSet()) { it.id }
        var added = 0
        for (e in records) {
            if (e.id.isNotBlank() && e.id in ids) continue
            val c = complete(e).copy(list = "")
            list.add(c)
            ids.add(c.id)
            added++
        }
        if (added > 0) save(ctx, book)
        return added
    }
}

/** Import / export — identical format to the web app's JSON and CSV. */
object Backup {
    private val COLUMNS = listOf("id", "date", "type", "category", "amount", "note", "createdAt")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * [people], [checklists], [notes] and the [lists] (names of the expense lists, checklists and notes, by id)
     * are only present in this app's full JSON backup.
     */
    class Parsed(
        val valid: List<Expense>, val invalid: Int, val people: List<Debt> = emptyList(),
        val checklists: List<CheckItem> = emptyList(), val notes: List<NoteEntry> = emptyList(),
        val lists: Lists = Lists(),
    )

    class Lists(val expenses: List<Book> = emptyList(), val checklists: List<Book> = emptyList(), val notes: List<Book> = emptyList())

    /** A note from a backup: the id of the note ([list]) and its text. */
    class NoteEntry(val list: String, val note: NoteText)

    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /**
     * Export file: every expense list (same format as before, each entry tagged with its "list" id), plus the
     * People ledger, every checklist item (tagged the same way), every note, and the names of all lists.
     */
    fun toFullJson(
        expenses: Map<Book, List<Expense>>, people: List<Debt>,
        checklists: Map<Book, List<CheckItem>>, notes: Map<Book, NoteText>,
    ): String {
        val arr = JSONArray()
        for ((b, list) in expenses) for (e in list.sortedWith(EXPENSE_ORDER)) arr.put(record(e).put("list", b.id))
        val items = JSONArray()
        for ((b, list) in checklists) { val a = Checklists.toArray(list, b.id); for (i in 0 until a.length()) items.put(a.get(i)) }
        val texts = JSONArray()
        for ((b, n) in notes) texts.put(n.toJson().put("list", b.id))
        return header(expenses.values.sumOf { it.size }).put("expenses", arr)
            .put("people", Ledger.toArray(people))
            .put("checklists", items)
            .put("textNotes", texts)
            .put("lists", JSONObject()
                .put("expenses", Store.books.toArray(expenses.keys.toList()))
                .put("checklists", Checklists.books.toArray(checklists.keys.toList()))
                .put("textNotes", Notes.books.toArray(notes.keys.toList())))
            .toString(2)
    }

    /** One expense list, as stored on the phone and in the CSV-era backups. */
    fun toJson(list: List<Expense>): String {
        val arr = JSONArray()
        for (e in list.sortedWith(EXPENSE_ORDER)) arr.put(record(e))
        return header(list.size).put("expenses", arr).toString(2)
    }

    private fun header(count: Int) = JSONObject()
        .put("format", "expenses-tracker")
        .put("version", 1)
        .put("exportedAt", Instant.now().toString())
        .put("count", count)

    private fun record(e: Expense) =
        JSONObject().put("id", e.id).put("date", e.date).put("type", e.type).put("category", e.category)
            .put("amount", e.amount).put("note", e.note).put("createdAt", e.createdAt)

    private fun cell(s: String) =
        if (s.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun toCsv(list: List<Expense>): String {
        val rows = list.sortedWith(EXPENSE_ORDER).map { e ->
            listOf(e.id, e.date, e.type, e.category, num(e.amount), e.note, e.createdAt).joinToString(",") { cell(it) }
        }
        // BOM so Excel opens ₹ and other symbols correctly
        return "\uFEFF" + (listOf(COLUMNS.joinToString(",")) + rows).joinToString("\r\n")
    }

    /** Accepts this app's JSON export, a plain JSON array, or CSV (detected by content). */
    fun parse(raw: String): Parsed {
        val text = raw.removePrefix("\uFEFF").trim()
        var people = emptyList<Debt>()
        var checklists = emptyList<CheckItem>()
        var notes = emptyList<NoteEntry>()
        var lists = Lists()
        val records = try {
            when {
                text.startsWith("[") -> jsonRecords(JSONArray(text))
                text.startsWith("{") -> {
                    val o = JSONObject(text)
                    people = o.optJSONArray("people")?.let(Ledger::fromArray).orEmpty()
                    val l = o.optJSONObject("lists") ?: JSONObject()
                    fun books(key: String, b: Books) = l.optJSONArray(key)?.let(b::fromArray).orEmpty()
                    val checkBooks = books("checklists", Checklists.books).toMutableList()
                    checklists = o.optJSONArray("checklists")?.let(Checklists::fromArray).orEmpty()
                    // Older backups: their wishlists and Ideas lists (saved as "ideas", later as "notes") become
                    // checklists, with the same ids they get on a phone that was updated
                    for ((prefix, key, listKey, name) in listOf(listOf("w", "wishlist", "wishlist", "Wishlist"), listOf("i", "ideas", "notes", "Ideas"))) {
                        val arr = o.optJSONArray(key) ?: o.optJSONArray(listKey).takeIf { key == "ideas" } ?: continue
                        val old = Checklists.fromArray(arr).map { it.copy(list = Checklists.oldId(prefix, it.list)) }
                        checklists = checklists + old
                        checkBooks += books(listKey, Checklists.books).map { Book(Checklists.oldId(prefix, it.id), it.name) }
                        for (id in old.map { it.list }.distinct()) if (checkBooks.none { it.id == id }) checkBooks += Book(id, name)
                    }
                    notes = o.optJSONArray("textNotes")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { NoteEntry(it.optString("list"), NoteText.fromJson(it)) } }
                    }.orEmpty()
                    lists = Lists(books("expenses", Store.books), checkBooks, books("textNotes", Notes.books))
                    val ex = o.optJSONArray("expenses")
                    if (ex == null && people.isEmpty() && checklists.isEmpty() && notes.isEmpty()) throw IllegalArgumentException("No expenses found in this file.")
                    jsonRecords(ex ?: JSONArray())
                }
                else -> csvRecords(text)
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException("This file is not valid JSON or CSV.")
        }
        val valid = ArrayList<Expense>()
        var invalid = 0
        for (r in records) normalise(r)?.let { valid.add(it) } ?: invalid++
        return Parsed(valid, invalid, people, checklists, notes, lists)
    }

    private fun jsonRecords(arr: JSONArray): List<Map<String, Any?>> = (0 until arr.length()).map { i ->
        val o = arr.optJSONObject(i) ?: JSONObject()
        o.keys().asSequence().associateWith { k -> o.opt(k).takeIf { it != JSONObject.NULL } }
    }

    private fun csvRecords(text: String): List<Map<String, Any?>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"' && text.getOrNull(i + 1) == '"') { cell.append('"'); i++ }
                else if (ch == '"') quoted = false
                else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\n', '\r' -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return emptyList()
        val header = nonEmpty[0].map { it.trim() }
        return nonEmpty.drop(1).map { r -> header.indices.associate { header[it] to r.getOrNull(it) } }
    }

    private fun normalise(r: Map<String, Any?>): Expense? {
        fun str(vararg keys: String) = keys.firstNotNullOfOrNull { k -> r[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() } } ?: ""
        // Tolerate spreadsheet-formatted values like "₹1,200.50"
        val amount = str("amount").replace(Regex("[^0-9.-]"), "").toDoubleOrNull() ?: return null
        val date = str("date").take(10)
        if (amount <= 0 || !DATE.matches(date)) return null
        return Expense(
            id = str("id", "\$id"),
            amount = amount,
            category = str("category").ifEmpty { "Other" },
            note = r["note"]?.toString() ?: "",
            date = date,
            type = if (str("type").lowercase() == "received") "received" else "spent",
            createdAt = str("createdAt", "\$createdAt"),
            list = str("list"),
        )
    }
}
