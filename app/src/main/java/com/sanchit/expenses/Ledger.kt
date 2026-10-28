package com.sanchit.expenses

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/**
 * Money lent to / borrowed from a person. Kept completely apart from expenses (own file, own store).
 * type: "gave" (you gave them money — they owe you more) or "got" (they gave you money — you owe them more).
 */
data class Debt(
    val id: String,
    val person: String,
    val phone: String,
    val amount: Double,
    val type: String,
    val note: String,
    val date: String,
    val createdAt: String,
) {
    val gave get() = type == "gave"
    val month get() = date.take(7)
    /** Positive: they owe you; negative: you owe them. */
    val signed get() = if (gave) amount else -amount
    val key get() = personKey(person)
}

fun personKey(name: String) = name.trim().lowercase()

/** Positive balance: they owe you. Negative: you owe them. */
class Person(val key: String, val name: String, val phone: String, val balance: Double, val entries: List<Debt>) {
    val settled get() = abs(balance) < 0.005
    val lastDate get() = entries.firstOrNull()?.date ?: ""
}

val DEBT_ORDER = compareByDescending<Debt> { it.date }.thenByDescending { it.createdAt }

/** Local storage for the People ledger: one JSON file in app-private storage. */
object Ledger {
    private var items: MutableList<Debt>? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "ledger.json"))

    private fun data(ctx: Context): MutableList<Debt> {
        items?.let { return it }
        val f = file(ctx)
        val list = if (f.baseFile.exists()) runCatching { parse(String(f.readFully(), Charsets.UTF_8)) }.getOrDefault(emptyList()) else emptyList()
        return list.toMutableList().also { items = it }
    }

    private fun save(ctx: Context) {
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(toJson(data(ctx)).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        Widgets.refresh(ctx)
    }

    private fun newId(): String =
        java.lang.Long.toHexString(System.currentTimeMillis()) + (1..8).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    @Synchronized
    fun all(ctx: Context): List<Debt> = data(ctx).sortedWith(DEBT_ORDER)

    /** Everyone with history, open balances first (largest first), then settled by most recent. */
    @Synchronized
    fun people(ctx: Context): List<Person> = all(ctx).groupBy { it.key }.map { (key, list) ->
        Person(key, list.first().person, list.firstOrNull { it.phone.isNotBlank() }?.phone ?: "", list.sumOf { it.signed }, list)
    }.sortedWith(compareBy<Person> { it.settled }.thenByDescending { if (it.settled) 0.0 else abs(it.balance) }.thenByDescending { it.lastDate })

    @Synchronized
    fun add(ctx: Context, entries: List<Debt>) {
        val now = Instant.now().toString()
        data(ctx).addAll(entries.map { it.copy(id = newId(), person = it.person.trim(), createdAt = now) })
        save(ctx)
    }

    @Synchronized
    fun update(ctx: Context, d: Debt) {
        val list = data(ctx)
        val i = list.indexOfFirst { it.id == d.id }
        if (i >= 0) list[i] = d.copy(person = d.person.trim())
        save(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        data(ctx).removeAll { it.id == id }
        save(ctx)
    }

    @Synchronized
    fun deletePerson(ctx: Context, key: String) {
        data(ctx).removeAll { it.key == key }
        save(ctx)
    }

    private fun toJson(list: List<Debt>): String {
        val arr = JSONArray()
        for (d in list.sortedWith(DEBT_ORDER)) arr.put(
            JSONObject().put("id", d.id).put("person", d.person).put("phone", d.phone).put("amount", d.amount)
                .put("type", d.type).put("note", d.note).put("date", d.date).put("createdAt", d.createdAt)
        )
        return JSONObject().put("format", "expenses-ledger").put("version", 1).put("entries", arr).toString(2)
    }

    private fun parse(raw: String): List<Debt> {
        val arr = JSONObject(raw).optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Debt(
                o.optString("id"), o.optString("person"), o.optString("phone"), o.optDouble("amount", 0.0),
                if (o.optString("type") == "got") "got" else "gave", o.optString("note"), o.optString("date"), o.optString("createdAt"),
            ).takeIf { it.amount > 0 && it.person.isNotBlank() }
        }
    }
}
