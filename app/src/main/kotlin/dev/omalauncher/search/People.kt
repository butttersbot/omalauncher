package dev.omalauncher.search

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * People in search (Boss's banked search list, 2026-10-20: contacts, to call or text): found by Android's own
 * contacts search, which matches names, nicknames and initials as the Contacts app does, so nothing is kept here.
 * Needs Contacts access; until it is given, search offers to ask for it.
 */
object People {
    data class Person(
        val id: Long,
        val name: String,
        /** Their first number, as written; null for none. */
        val number: String?,
        val photo: String?,
    ) {
        val uri: Uri get() = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
    }

    fun allowed(context: Context) = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Those whose name matches [query], favorites first, [limit] at most; empty without access. */
    fun find(
        context: Context,
        query: String,
        limit: Int = 4,
    ): List<Person> {
        val q = query.trim()
        if (q.length < 2 || !allowed(context)) return emptyList()
        val cols = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
        val found = ArrayList<Triple<Long, String, Pair<Boolean, String?>>>()
        runCatching {
            context.contentResolver.query(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q)), cols, null, null, "${ContactsContract.Contacts.STARRED} DESC")?.use { c ->
                while (c.moveToNext() && found.size < limit) found += Triple(c.getLong(0), c.getString(1) ?: continue, (c.getInt(2) > 0) to c.getString(3))
            }
        }
        return found.map { (id, name, rest) -> Person(id, name, if (rest.first) number(context, id) else null, rest.second) }
    }

    private fun number(
        context: Context,
        id: Long,
    ): String? =
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /** Their card in the Contacts app. */
    fun open(person: Person) = Intent(Intent.ACTION_VIEW, person.uri)

    /** The dialer with their number in it, to call with one more tap (no permission to call outright is asked). */
    fun call(number: String) = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))

    fun text(number: String) = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
}
