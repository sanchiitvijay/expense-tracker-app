package com.sanchit.expenses

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Read-only access to the phone's contacts, used only to suggest names. Nothing is sent anywhere. */
object Contacts {
    class Contact(val name: String, val phone: String) {
        override fun toString() = name // what AutoCompleteTextView filters on and fills in
    }

    private const val REQ = 41
    private var pending: ((Boolean) -> Unit)? = null
    private var cache: List<Contact>? = null

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun request(act: Activity, then: (granted: Boolean) -> Unit) {
        pending = then
        act.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ)
    }

    /** Forwarded from each activity's onRequestPermissionsResult. */
    fun onResult(code: Int, results: IntArray) {
        if (code != REQ) return
        val p = pending
        pending = null
        p?.invoke(results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    /** One entry per name (first phone number found). Empty without permission. */
    fun load(ctx: Context): List<Contact> {
        if (!granted(ctx)) return emptyList()
        cache?.let { return it }
        val out = LinkedHashMap<String, Contact>()
        runCatching {
            ctx.contentResolver.query(
                Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER), null, null, Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    if (name.isNotEmpty()) out.getOrPut(personKey(name)) { Contact(name, c.getString(1)?.trim().orEmpty()) }
                }
            }
        }
        return out.values.toList().also { cache = it }
    }

    /** People already in the ledger first, then contacts not yet in it. */
    fun suggestions(ctx: Context): List<Contact> {
        val known = Ledger.people(ctx).map { Contact(it.name, it.phone) }
        val keys = known.mapTo(HashSet()) { personKey(it.name) }
        return known + load(ctx).filter { personKey(it.name) !in keys }
    }
}
