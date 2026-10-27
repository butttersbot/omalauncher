package dev.omalauncher.keyboard

import android.content.Context
import dev.omalauncher.search.PhotoIndex

/**
 * The GIFs saved on the phone, for the keyboard (Boss, 2026-10-21: KLIPY "is just not as good as tenor so lets also
 * pull in my local gifs"): read from Soma's photo index, which already knows what is in each, so they are found by
 * words ("beach") as photos are. They go with his hearted ones under Saved (not in a search since 2026-10-27).
 */
object GifLocal {
    private fun item(
        uri: String,
        name: String,
    ) = GifItem("local:$uri", name, uri, uri, 1f, local = true, link = "")

    /** Every GIF on the phone the index knows, newest first. */
    fun all(context: Context): List<GifItem> {
        val db = PhotoIndex.open(context, writable = false) ?: return emptyList()
        return try {
            runCatching {
                db.rawQuery("SELECT uri, name FROM photos WHERE kind = 'gif' ORDER BY taken DESC LIMIT 400", null).use { c ->
                    buildList { while (c.moveToNext()) add(item(c.getString(0), c.getString(1) ?: "")) }
                }
            }.getOrDefault(emptyList())
        } finally {
            db.close()
        }
    }
}
