package dev.omalauncher.oma

import android.content.Context

/** The book of the one Oma chat, kept in the app's preferences. Read and written from the screen, home and the agents' threads. */
object TabBook {
    @Volatile private var meta: TabMeta? = null
    private const val PREFS = "oma-tabs"
    private const val KEY = "book"
    private const val LAST_TAB = "last-tab"

    fun of(context: Context): TabMeta = meta ?: synchronized(this) {
        meta ?: tabMetaFrom(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).also { meta = it }
    }

    /** The tab he last had open, across restarts; null for the Soma tab. Only his opening a tab changes it. */
    fun lastTab(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_TAB, null)

    fun setLastTab(context: Context, tab: String?) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_TAB, tab).apply()

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
