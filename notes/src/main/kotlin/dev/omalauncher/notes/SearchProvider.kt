package dev.omalauncher.notes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import dev.omalauncher.notes.engine.Notes
import dev.omalauncher.notes.engine.Search

/**
 * Soma's search on home asks here for notes (content://dev.omalauncher.notes.search/notes?q=…&titles=1). Only apps
 * signed with this app's own key are answered: the launcher; anything else gets nothing.
 */
class SearchProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val out = MatrixCursor(COLUMNS)
        val context = context ?: return out
        val caller = Binder.getCallingUid()
        if (context.packageManager.checkSignatures(caller, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH) return out
        val q = uri.getQueryParameter("q").orEmpty()
        val titles = uri.getQueryParameter("titles") == "1"
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        Notes.init(context.applicationContext)
        for (h in Search.find(Notes.all.value, Notes::text, q, titles, limit)) out.addRow(arrayOf<Any>(h.id, h.title, h.project ?: "", h.snippet, h.modified))
        return out
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val COLUMNS = arrayOf("id", "title", "project", "snippet", "modified")
    }
}
