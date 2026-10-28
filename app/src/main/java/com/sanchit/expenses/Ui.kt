package com.sanchit.expenses

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object C {
    const val BG = 0xFF0A0A0A.toInt()
    const val SURFACE = 0xFF101010.toInt()
    const val CARD = 0xFF171717.toInt()
    const val CARD2 = 0xFF262626.toInt()
    const val LINE = 0x14FFFFFF
    const val TEXT = 0xFFF5F5F5.toInt()
    const val MUTED = 0xFF8A8A8A.toInt()
    const val DIM = 0xFF5C5C5C.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val GREEN_D = 0xFF10B981.toInt()
    const val RED = 0xFFFB7185.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
}

private val CATEGORY_COLORS = mapOf(
    "Food" to 0xFFFB923C, "Transport" to 0xFF38BDF8, "Shopping" to 0xFFA78BFA,
    "Bills" to 0xFFFB7185, "Entertainment" to 0xFFF472B6, "Health" to 0xFF2DD4BF,
).mapValues { it.value.toInt() }

fun catColor(c: String) = CATEGORY_COLORS[c] ?: 0xFFA3A3A3.toInt()
fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

// ── Formatting ──
private val IN = Locale.forLanguageTag("en-IN")
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", IN)
private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM", IN)
private val DAY_Y = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", IN)
private val PRETTY = DateTimeFormatter.ofPattern("d MMM yyyy", IN)

fun money(v: Double, decimals: Boolean = true): String {
    val f = NumberFormat.getNumberInstance(IN).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = if (decimals) 2 else 0
    }
    return "₹" + f.format(v)
}

fun compact(v: Double): String = when {
    v >= 1e5 -> String.format(IN, "%.1fL", v / 1e5).replace(".0L", "L")
    v >= 1e3 -> String.format(IN, "%.1fk", v / 1e3).replace(".0k", "k")
    else -> v.toLong().toString()
}

/** "banana split" → "Banana split" */
fun String.capFirst(): String = replaceFirstChar { it.titlecase(Locale.getDefault()) }

fun today(): LocalDate = LocalDate.now()
fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
fun monthLabel(ym: String): String = runCatching { YearMonth.parse(ym).format(MONTH) }.getOrDefault("No date")
fun prettyDate(d: LocalDate): String = d.format(PRETTY)

fun dayLabel(s: String): String {
    val d = parseDate(s) ?: return "No date"
    val t = today()
    return when (d) {
        t -> "Today"
        t.minusDays(1) -> "Yesterday"
        else -> d.format(if (d.year == t.year) DAY else DAY_Y)
    }
}

// ── Tiny view DSL ──
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(start, top, end, bottom) }

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

/** weight: 0 regular, 1 medium, 2 bold */
fun Context.text(s: CharSequence, sp: Float = 14f, color: Int = C.TEXT, weight: Int = 0) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = when (weight) {
        1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        2 -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
}

fun bg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun pressable(content: Drawable? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), content, content ?: ColorDrawable(-1))

fun Context.styleChip(v: TextView, active: Boolean, activeBg: Int = C.TEXT, activeFg: Int = C.BG) {
    v.setTextColor(if (active) activeFg else C.MUTED)
    v.background = if (active) bg(activeBg, dp(10f)) else bg(C.CARD, dp(10f), dp(1), C.LINE)
}

fun Context.chip(s: String) = text(s, 13f, C.MUTED, 1).apply {
    gravity = Gravity.CENTER
    setPadding(dp(13), dp(8), dp(13), dp(8))
}

fun Context.pickDate(initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(this, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
