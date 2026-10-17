package dev.omalauncher.oma

import android.content.Context

/** The book of the one Oma chat, kept in the app's preferences. Read and written from the screen, home and the agents' threads. */
object TabBook {
    @Volatile private var meta: TabMeta? = null
    private const val PREFS = "oma-tabs"
    private const val KEY = "book"

    fun of(context: Context): TabMeta = meta ?: synchronized(this) {
        meta ?: tabMetaFrom(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).also { meta = it }
    }

    /** Changes the book and saves it; one that comes out the same is not written. */
    @Synchronized fun change(context: Context, change: (TabMeta) -> TabMeta): TabMeta {
        val now = of(context)
        val next = change(now)
        if (next != now) {
            meta = next
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tabMetaText(next)).apply()
        }
        return next
    }
}
