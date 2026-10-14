package dev.omalauncher.oma

import android.content.Context
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.ThreadState
import org.json.JSONArray
import org.json.JSONObject

/*
 * What Boss has made of the chat's tabs (Boss, 2026-10-13: "a plus icon in a square to the end of the tabs …
 * that can start a new chat … auto named … long press on a tab … including archiving it"): the names tabs are
 * shown by, the ones he pinned and archived, and the new chats he started, each with the session it is bound
 * to once its first message has gone somewhere. Every decision is a plain function here, unit tested; TabBook
 * at the end only keeps what they return and writes it to the app's preferences. Nothing here touches a
 * chat's entries, a builder's conversation or a thread on the Mac.
 */

/** Ahead of a new chat's id in its tab's key, so it is never taken for a builder's or a Mac thread's. Saved: never rename. */
const val CHAT_PREFIX = "chat:"

fun chatKey(id: String) = CHAT_PREFIX + id

/** The id of the new chat whose tab [key] is; null for any other tab. */
fun chatId(key: String): String? = key.takeIf { it.startsWith(CHAT_PREFIX) }?.removePrefix(CHAT_PREFIX)?.takeIf { it.isNotEmpty() }

/** What a new chat is called until it is named. */
const val NEW_CHAT_NAME = "New chat"

/** The longest name a tab is shown by: what fits a tab and a tile's title. */
const val TAB_NAME_CHARS = 32

/**
 * A chat he started with ＋. Its first message goes to a fresh session and binds it: [builder] once one of the
 * phone's builders has begun a new conversation for it, or [mac], the id of the thread made for it on the Mac.
 * Until then it is unbound and the front agent hears it first, in a conversation of its own whose session is
 * [front]. [made] is when he started it.
 */
data class NewChat(
    val id: String,
    val made: Long,
    val builder: BuilderId? = null,
    val mac: String = "",
    val front: String = "",
) {
    val key get() = chatKey(id)
    val bound get() = builder != null || mac.isNotEmpty()
}

/**
 * The tabs as he has arranged them: [names] he or Oma gave them, by tab key; the keys he [pinned], in the order
 * he pinned them; the keys he [archived], each with when, which is what new work is compared with; and every
 * new [chats] he started, oldest first.
 */
data class TabMeta(
    val names: Map<String, String> = emptyMap(),
    val pinned: List<String> = emptyList(),
    val archived: Map<String, Long> = emptyMap(),
    val chats: List<NewChat> = emptyList(),
) {
    fun chat(id: String) = chats.firstOrNull { it.id == id }

    /** The new chat whose tab [key] is; null for any other tab. */
    fun chatOf(key: String) = chatId(key)?.let(::chat)

    /** The new chat the Mac thread [threadId] was made for; null for a thread no chat is bound to. */
    fun chatOnMac(threadId: String) = chats.firstOrNull { it.mac == threadId }
}

/** [raw] as a tab's name: one line, its spaces collapsed, no longer than [TAB_NAME_CHARS]; null when nothing is left. */
fun tabName(raw: String): String? = raw.replace(Regex("\\s+"), " ").trim().take(TAB_NAME_CHARS).trim().ifEmpty { null }

/** [key] called [name] from now on; a name that is blank gives it back its own. */
fun TabMeta.renamed(key: String, name: String): TabMeta = when (val clean = tabName(name)) {
    null -> copy(names = names - key)
    else -> copy(names = names + (key to clean))
}

/** [key] named by Oma: only while it has no name, so a name he gave it is never replaced. */
fun TabMeta.titled(key: String, title: String): TabMeta = if (key in names) this else renamed(key, title)

fun TabMeta.pinnedAs(key: String, on: Boolean): TabMeta = when {
    on && key !in pinned -> copy(pinned = pinned + key)
    !on -> copy(pinned = pinned - key)
    else -> this
}

/** [key] put away at [at]. A pinned tab archived is no longer pinned: the two say opposite things about it. */
fun TabMeta.archivedAs(key: String, at: Long): TabMeta = copy(archived = archived + (key to at), pinned = pinned - key)

fun TabMeta.restored(key: String): TabMeta = if (key in archived) copy(archived = archived - key) else this

/** A new chat with the id [id], started at [made]. */
fun TabMeta.started(id: String, made: Long): TabMeta = if (chat(id) != null) this else copy(chats = chats + NewChat(id, made))

private fun TabMeta.changed(id: String, change: (NewChat) -> NewChat) = copy(chats = chats.map { if (it.id == id) change(it) else it })

/**
 * Chat [id] bound to [builder], or to the Mac thread [mac]. Only an unbound chat is: its first message decides
 * where it lives, and nothing later moves it.
 */
fun TabMeta.bound(id: String, builder: BuilderId? = null, mac: String = ""): TabMeta = changed(id) { if (it.bound) it else it.copy(builder = builder, mac = mac) }

/** The front agent's session for chat [id], which its next message there continues. */
fun TabMeta.heard(id: String, session: String?): TabMeta = changed(id) { it.copy(front = session.orEmpty()) }

/** The id a chat started at [now] gets: the time in base 36, with a letter after it in the rare case it is taken. */
fun newChatId(now: Long, taken: Collection<String>): String {
    val base = "c" + now.toString(36)
    return (sequenceOf(base) + ('a'..'z').asSequence().map { base + it }).first { it !in taken }
}

/**
 * The chat ＋ opens rather than starting another: an unbound one he has not sent anything in yet and has not
 * archived, so tapping ＋ twice never leaves two empty "New chat" tabs. Null when there is none.
 */
fun emptyChat(meta: TabMeta, tasks: List<ChatTask>): String? =
    meta.chats.lastOrNull { chat -> !chat.bound && chat.key !in meta.archived && tasks.none { it.chat == chat.id } }?.id

/**
 * Whether an archived tab comes back by itself: it needs his OK, or something was sent to it after he archived
 * it at [at]. [newest] is when its newest entry was sent, null for none.
 */
fun revived(at: Long, newest: Long?, needsOk: Boolean): Boolean = needsOk || (newest != null && newest > at)

/** The book as it is kept; an empty part is left out, so a phone that never touched a tab writes nothing new. */
fun tabMetaText(meta: TabMeta): String = JSONObject().apply {
    if (meta.names.isNotEmpty()) put("names", JSONObject(meta.names))
    if (meta.pinned.isNotEmpty()) put("pinned", JSONArray(meta.pinned))
    if (meta.archived.isNotEmpty()) put("archived", JSONObject().apply { for ((key, at) in meta.archived) put(key, at) })
    if (meta.chats.isNotEmpty()) {
        put("chats", JSONArray(meta.chats.map { chat ->
            JSONObject().put("id", chat.id).put("made", chat.made)
                .apply { chat.builder?.let { put("builder", it.letter) } }
                .apply { if (chat.mac.isNotEmpty()) put("mac", chat.mac) }
                .apply { if (chat.front.isNotEmpty()) put("front", chat.front) }
        }))
    }
}.toString()

/** The kept book; what can't be read is left out, and an unreadable whole is an empty book. */
fun tabMetaFrom(text: String?): TabMeta {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return TabMeta()
    val names = saved.optJSONObject("names")?.let { o -> o.keys().asSequence().mapNotNull { key -> tabName(o.optString(key))?.let { key to it } }.toMap() }.orEmpty()
    val pinned = saved.optJSONArray("pinned")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } }.orEmpty().distinct()
    val archived = saved.optJSONObject("archived")?.let { o -> o.keys().asSequence().map { it to o.optLong(it) }.toMap() }.orEmpty()
    val chats = saved.optJSONArray("chats")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() && it.length <= ChatHistory.MAX_CHAT } ?: return@mapNotNull null
            NewChat(id, o.optLong("made"), BuilderId.named(o.optString("builder").ifEmpty { null }), o.optString("mac"), o.optString("front"))
        }
    }.orEmpty().distinctBy { it.id }
    return TabMeta(names, pinned, archived, chats)
}

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

/**
 * The archived tabs that come back by themselves now ([revived]): one whose thread waits for his OK, or that was
 * sent something after he archived it. [threads] is every thread as it stands and [tasks] the Oma thread.
 */
fun revivals(meta: TabMeta, threads: List<AgentThread>, tasks: List<ChatTask>): Set<String> =
    meta.archived.filter { (key, at) ->
        val needsOk = threads.firstOrNull { it.key == key }?.state == ThreadState.NeedsOk
        revived(at, tabEntries(tasks, key, meta).maxOfOrNull { it.time }, needsOk)
    }.keys

/** [meta] with the tabs of [revivals] brought back. */
fun TabMeta.revive(threads: List<AgentThread>, tasks: List<ChatTask>): TabMeta = revivals(this, threads, tasks).fold(this) { meta, key -> meta.restored(key) }
