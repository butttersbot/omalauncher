package dev.omalauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Soma Notes in Soma's search (Boss, 2026-10-24: "couple that with our spotlight search and that gives me an easy way
 * to search docs"): his notes, his agents' notes and each project's Brief and Current state, as Soma Notes keeps them
 * on the phone, asked of its search provider. Nothing when Soma Notes isn't installed.
 */
object NotesFound {
    data class Hit(
        val id: String,
        val title: String,
        /** The agent project it's filed under; empty for one of Boss's own. */
        val project: String,
        val snippet: String,
        val modified: Long,
    )

    const val PACKAGE = "dev.omalauncher.notes"
    private val URI: Uri = Uri.parse("content://$PACKAGE.search/notes")

    /** Notes with every word of [query]; [titlesOnly] for Quick, where only a title that matches is worth the room. */
    fun find(
        context: Context,
        query: String,
        titlesOnly: Boolean,
        limit: Int,
    ): List<Hit> =
        runCatching {
            val uri = URI.buildUpon().appendQueryParameter("q", query).appendQueryParameter("titles", if (titlesOnly) "1" else "0").appendQueryParameter("limit", limit.toString()).build()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val out = ArrayList<Hit>()
                while (c.moveToNext()) out += Hit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())

    /** Soma Notes, opened on [hit]. */
    fun open(hit: Hit): Intent = Intent("$PACKAGE.OPEN").setClassName(PACKAGE, "$PACKAGE.MainActivity").putExtra("id", hit.id)

    /** "Mine" or the project's name, as Soma Notes heads it ("soma-notes" is Soma Notes). */
    fun where(hit: Hit): String =
        if (hit.project.isEmpty()) "Mine" else hit.project.split('-', '_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
