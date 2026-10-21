package dev.omalauncher.keyboard

import android.content.Context
import dev.omalauncher.search.PhotoIndex
import dev.omalauncher.search.PhotoMac
import dev.omalauncher.search.PhotoTextService
import java.io.ByteArrayOutputStream

/**
 * The GIFs saved on the phone, for the keyboard (Boss, 2026-10-21: KLIPY "is just not as good as tenor so lets also
 * pull in my local gifs"): read from Soma's photo index, which already knows what is in each, so they are found by
 * words ("beach") as photos are. They go with his hearted ones under Saved, and first in a search.
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

    /**
     * His GIFs that match [query]: those whose words have it (text in them, what the Mac saw, the name), then those
     * nearest in meaning, as photo search finds photos. Never waits long on the Mac: the phone's own model reads the
     * words when it is away.
     */
    fun search(
        context: Context,
        query: String,
        limit: Int = 12,
    ): List<GifItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val db = PhotoIndex.open(context, writable = false) ?: return emptyList()
        return try {
            val byWords = PhotoIndex.byWords(db, q, 40).filter { it.kind == "gif" }.map { it.uri }
            val near =
                runCatching {
                    val vector = PhotoMac.text(PhotoMac.base(context), q) ?: PhotoTextService.vector(context, q) ?: return@runCatching emptyList()
                    val ids = ArrayList<Long>()
                    val out = ByteArrayOutputStream()
                    db.rawQuery("SELECT id, vec FROM photos WHERE kind = 'gif' AND vec IS NOT NULL", null).use { c ->
                        while (c.moveToNext()) {
                            val v = c.getBlob(1) ?: continue
                            if (v.size != PhotoIndex.DIM) continue
                            ids += c.getLong(0)
                            out.write(v)
                        }
                    }
                    val vectors = PhotoIndex.Vectors(ids.toLongArray(), out.toByteArray())
                    PhotoIndex.hits(db, PhotoIndex.shown(PhotoIndex.nearest(vectors, vector, limit), named = false)).map { it.uri }
                }.getOrDefault(emptyList())
            val names = HashMap<String, String>()
            (byWords + near).distinct().take(limit).map { uri ->
                names.getOrPut(uri) {
                    runCatching { db.rawQuery("SELECT name FROM photos WHERE uri = ?", arrayOf(uri)).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } }.getOrDefault("")
                }.let { item(uri, it) }
            }
        } finally {
            db.close()
        }
    }
}
