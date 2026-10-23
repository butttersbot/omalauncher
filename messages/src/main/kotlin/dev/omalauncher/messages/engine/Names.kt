package dev.omalauncher.messages.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * People's names from the phone's own contacts, by phone number or email. The Mac's service sends handles only
 * (it isn't given the Mac's Contacts), so a 1:1 chat would otherwise be titled "+1 555…". Nothing without the owner's
 * OK to read contacts; a handle with no contact stays a handle.
 */
object Names {
    private val known = ConcurrentHashMap<String, String>()

    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Forgets what was looked up, once the owner has allowed contacts. */
    fun clear() = known.clear()

    fun of(
        context: Context,
        handle: String,
    ): String? {
        if (!allowed(context)) return null
        known[handle]?.let { return it.ifEmpty { null } }
        val name = runCatching { lookup(context, handle) }.getOrNull()
        known[handle] = name.orEmpty()
        return name
    }

    private fun lookup(
        context: Context,
        handle: String,
    ): String? {
        val uri =
            if ('@' in handle) {
                Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(handle))
            } else {
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(handle))
            }
        val column = if ('@' in handle) ContactsContract.Data.DISPLAY_NAME else ContactsContract.PhoneLookup.DISPLAY_NAME
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
