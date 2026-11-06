package com.sanchit.expenses

import android.content.Context

/** Main-screen tabs, in display order. Hiding a tab only hides it; its data is kept. */
enum class Tab(val label: String, val defaultOn: Boolean = true, val key: String? = null) {
    EXPENSES("Expenses"),
    PEOPLE("People"),
    CHECKLISTS("Checklist", key = "WISHLIST"), // replaces the Wishlist tab; keeps its on/off setting
    NOTES("Notes", key = "TEXT_NOTES"),
    REPORTS("Reports"),
}

/** App preferences (which tabs are shown). */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isOn(ctx: Context, t: Tab) = prefs(ctx).getBoolean("tab_${t.key ?: t.name}", t.defaultOn)

    fun setOn(ctx: Context, t: Tab, on: Boolean) = prefs(ctx).edit().putBoolean("tab_${t.key ?: t.name}", on).apply()

    fun visibleTabs(ctx: Context) = Tab.entries.filter { isOn(ctx, it) }
}
