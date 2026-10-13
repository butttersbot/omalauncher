package dev.omalauncher.theme

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import com.samsung.android.gtscell.RemoteCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LockStar's lock-screen layout, taken and put back through the provider Good Lock shares setups with. LockStar
 * keeps the layout to itself (its clock, what stands where); this is the one way in that an app that isn't
 * Samsung's may use, and only while it holds WRITE_SECURE_SETTINGS, which the owner grants over adb. A capture
 * is LockStar's own backup text, kept as it came and handed back unchanged: nothing in it is read or edited
 * here beyond counting what it holds. Read off LockStar 9.0 (2026-10); another version may answer differently.
 */
object LockStarShare {
    private val PROVIDER = Uri.parse("content://com.samsung.systemui.lockstar.gts.gtslockscreenprovider")
    private const val ITEM = "lock_star"

    /** A capture's name: a look's id, or a word of the owner's. */
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")

    fun valid(name: String) = NAME.matches(name)

    private fun dir(context: Context) = File(context.filesDir, "lockstar").apply { mkdirs() }

    fun names(context: Context): List<String> = dir(context).list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    /** What a cell holds, for telling the owner: how many items, and of the layout item, its length and its files. */
    fun summary(cell: String): JSONObject {
        val items = JSONObject(cell).optJSONArray("items")
        val out = JSONObject().put("items", items?.length() ?: 0)
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items!!.getJSONObject(i)
            if (item.optString("key") != ITEM) continue
            out.put("layoutChars", item.optString("value").length).put("files", item.optJSONObject("embeddedItems")?.length() ?: item.optJSONObject("embedded_items")?.length() ?: 0)
            item.optJSONObject("tags")?.optString("device_type")?.takeIf { it.isNotEmpty() }?.let { out.put("device", it) }
        }
        return out
    }

    /** Asks LockStar for its layout and keeps it as [name]. Not on the main thread. Throws what the provider throws. */
    fun capture(
        context: Context,
        name: String,
    ): JSONObject {
        require(valid(name)) { "Give a name of lower-case letters, digits and dashes." }
        var cell = ""
        // A backup holds everything; a share is what LockStar gives when it won't back up.
        for (action in listOf(2, 1)) {
            val extras = Bundle().apply {
                putStringArrayList("gts_item_keys", arrayListOf(ITEM))
                putString("target_category", "com.samsung.android.gts.category.DEFAULT")
                putInt("gts_action", action)
            }
            cell = context.contentResolver.call(PROVIDER, "get_item", null, extras)?.getString("gts_item_cell") ?: error("LockStar gave no answer.")
            if (summary(cell).optInt("items") > 0) break
        }
        val told = summary(cell)
        check(told.optInt("items") > 0) { "LockStar gave an empty layout: it is switched off, or has none of its own." }
        File(dir(context), "$name.json").writeText(cell)
        return told.put("name", name)
    }

    /**
     * Hands the capture [name] back to LockStar and waits for it to say it is done. True if it answered in time;
     * what it made of the layout is LockStar's to say, in its own log. Not on the main thread.
     */
    fun apply(
        context: Context,
        name: String,
    ): Boolean {
        require(valid(name)) { "Give a name of lower-case letters, digits and dashes." }
        val file = File(dir(context), "$name.json")
        check(file.isFile) { "No capture is called \"$name\". Captures: ${names(context).joinToString(", ").ifEmpty { "none" }}." }
        val done = CountDownLatch(1)
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    // The answer's bundle holds a class of LockStar's that can't be read here: that it came is what is known.
                    data.enforceInterface(RemoteCallback.INTERFACE)
                    reply?.writeNoException()
                    done.countDown()
                    return true
                }
            }
        val extras = Bundle().apply {
            putString("gts_item_cell", file.readText())
            putParcelable("finish_callback", RemoteCallback(binder))
            putLong("timeout", 10_000L)
        }
        context.contentResolver.call(PROVIDER, "set_item", null, extras)
        return done.await(12, TimeUnit.SECONDS)
    }
}
