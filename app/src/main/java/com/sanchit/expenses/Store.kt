package com.sanchit.expenses

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.random.Random

/** Same schema as the web app: id, amount, category, note, date (YYYY-MM-DD), type (spent|received), createdAt (ISO). */
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String,
    val date: String,
    val type: String,
    val createdAt: String,
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

/** Local storage: one JSON file in app-private storage, in the same format as the export. */
object Store {
    private val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Entertainment", "Health", "Other")
    private var items: MutableList<Expense>? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "expenses.json"))

    private fun data(ctx: Context): MutableList<Expense> {
        items?.let { return it }
        val f = file(ctx)
        val list = if (f.baseFile.exists()) {
            runCatching { Backup.parse(String(f.readFully(), Charsets.UTF_8)).valid.map(::complete) }.getOrDefault(emptyList())
        } else emptyList()
        return list.toMutableList().also { items = it }
    }

    private fun save(ctx: Context) {
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(Backup.toJson(data(ctx)).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    private fun newId(): String =
        java.lang.Long.toHexString(System.currentTimeMillis()) + (1..8).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    private fun complete(e: Expense) = e.copy(
        id = e.id.ifBlank { newId() },
        createdAt = e.createdAt.ifBlank { Instant.now().toString() },
    )

    @Synchronized
    fun all(ctx: Context): List<Expense> = data(ctx).sortedWith(EXPENSE_ORDER)

    @Synchronized
    fun categories(ctx: Context): List<String> {
        val used = data(ctx).map { it.category }
        return (DEFAULT_CATEGORIES.dropLast(1) + used + "Other").distinct()
    }

    @Synchronized
    fun add(ctx: Context, amount: Double, category: String, note: String, date: String, type: String): Expense {
        val e = complete(Expense("", amount, category, note, date, type, ""))
        data(ctx).add(e)
        save(ctx)
        return e
    }

    @Synchronized
    fun update(ctx: Context, e: Expense) {
        val list = data(ctx)
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e
        save(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        data(ctx).removeAll { it.id == id }
        save(ctx)
    }

    /** Remove every entry matching [predicate]. Returns how many were removed. */
    @Synchronized
    fun purge(ctx: Context, predicate: (Expense) -> Boolean): Int {
        val list = data(ctx)
        val before = list.size
        list.removeAll(predicate)
        val removed = before - list.size
        if (removed > 0) save(ctx)
        return removed
    }

    /** Adds entries whose id isn't already stored. Returns how many were added. */
    @Synchronized
    fun import(ctx: Context, parsed: Backup.Parsed): Int {
        val list = data(ctx)
        val ids = list.mapTo(HashSet()) { it.id }
        var added = 0
        for (e in parsed.valid) {
            if (e.id.isNotBlank() && e.id in ids) continue
            val c = complete(e)
            list.add(c)
            ids.add(c.id)
            added++
        }
        if (added > 0) save(ctx)
        return added
    }
}

/** Import / export — identical format to the web app's JSON and CSV. */
object Backup {
    private val COLUMNS = listOf("id", "date", "type", "category", "amount", "note", "createdAt")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    class Parsed(val valid: List<Expense>, val invalid: Int)

    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    fun toJson(list: List<Expense>): String {
        val arr = JSONArray()
        for (e in list.sortedWith(EXPENSE_ORDER)) {
            arr.put(
                JSONObject().put("id", e.id).put("date", e.date).put("type", e.type).put("category", e.category)
                    .put("amount", e.amount).put("note", e.note).put("createdAt", e.createdAt)
            )
        }
        return JSONObject()
            .put("format", "expenses-tracker")
            .put("version", 1)
            .put("exportedAt", Instant.now().toString())
            .put("count", list.size)
            .put("expenses", arr)
            .toString(2)
    }

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
        val records = try {
            when {
                text.startsWith("[") -> jsonRecords(JSONArray(text))
                text.startsWith("{") -> jsonRecords(
                    JSONObject(text).optJSONArray("expenses") ?: throw IllegalArgumentException("No expenses found in this file.")
                )
                else -> csvRecords(text)
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException("This file is not valid JSON or CSV.")
        }
        val valid = ArrayList<Expense>()
        var invalid = 0
        for (r in records) normalise(r)?.let { valid.add(it) } ?: invalid++
        return Parsed(valid, invalid)
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
        )
    }
}
