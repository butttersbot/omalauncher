package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.widgets.TileLife
import dev.omalauncher.widgets.tileLife
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
 * A chat he started with ＋, which is always for the Mac (Boss, 2026-10-13: "The plus button should always open
 * up a Mac session"). Its first words that are handed on go to a new thread on the Mac and bind it: [mac] is that
 * thread's id. Until then it is unbound and the front agent hears it first, in a conversation of its own whose
 * session is [front]. [made] is when he started it, and [used] when he last renamed or restored it, 0 if never: until
 * it is bound, the later of those and its newest entry is when it was last used.
 */
data class NewChat(
    val id: String,
    val made: Long,
    val mac: String = "",
    val front: String = "",
    val used: Long = 0L,
    /**
     * The computer he picked for its thread before its first words ([MacMachine.id]); null when he picked none, which
     * leaves it to [macChatMachine]. Not the empty string: that is the first Mac's id ([MAC_FIRST]), a pick like any other.
     */
    val host: String? = null,
    /** The agent he picked for its thread ("claude", "codex", "grok"…); empty leaves it to [macChatKind]. */
    val agent: String = "",
) {
    val key get() = chatKey(id)
    val bound get() = mac.isNotEmpty()
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

/**
 * [key] called [name] from now on; a name that is blank gives it back its own. A new chat he renames at [at] counts
 * as used then, so a chat he has only named rests its full time like one he wrote in; Oma's own naming passes no [at].
 */
fun TabMeta.renamed(key: String, name: String, at: Long = 0L): TabMeta = when (val clean = tabName(name)) {
    null -> copy(names = names - key)
    else -> copy(names = names + (key to clean))
}.usedAt(key, at)

/** The new chat whose tab [key] is, used at [at] ([NewChat.used]); any other tab, or no time, leaves the book as it is. */
private fun TabMeta.usedAt(key: String, at: Long): TabMeta {
    val chat = chatOf(key)?.takeIf { at > 0L } ?: return this
    return changed(chat.id) { it.copy(used = at) }
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

/** [key] back from Archived. A new chat he restores at [at] counts as used then, so it is not put away again at the next look. */
fun TabMeta.restored(key: String, at: Long = 0L): TabMeta = if (key in archived) copy(archived = archived - key).usedAt(key, at) else this

/** A new chat with the id [id], started at [made]. */
fun TabMeta.started(id: String, made: Long): TabMeta = if (chat(id) != null) this else copy(chats = chats + NewChat(id, made))

private fun TabMeta.changed(id: String, change: (NewChat) -> NewChat) = copy(chats = chats.map { if (it.id == id) change(it) else it })

/**
 * Chat [id] living on the Mac thread [mac] from now on: the one made for its first words, or the one its words
 * went on to when another agent's turn ended at a limit.
 */
fun TabMeta.onMac(id: String, mac: String): TabMeta = changed(id) { it.copy(mac = mac) }

/** The computer and agent he picked for chat [id]'s thread; only a chat with no thread yet takes them. */
fun TabMeta.chose(id: String, host: String, agent: String): TabMeta = changed(id) { if (it.bound) it else it.copy(host = host, agent = agent) }

/** The front agent's session for chat [id], which its next message there continues. */
fun TabMeta.heard(id: String, session: String?): TabMeta = changed(id) { it.copy(front = session.orEmpty()) }

/** The id a chat started at [now] gets: the time in base 36, with a letter after it in the rare case it is taken. */
fun newChatId(now: Long, taken: Collection<String>): String {
    val base = "c" + now.toString(36)
    return (sequenceOf(base) + ('a'..'z').asSequence().map { base + it }).first { it !in taken }
}

/**
 * The chat ＋ opens rather than starting another: an unbound one he has not sent anything in yet, not named and not
 * archived, so tapping ＋ twice never leaves two empty "New chat" tabs. One he named is his, as one he wrote in is
 * (decided for Boss, 2026-10-14, OPEN-BUGS #14): it was set up for something, so ＋ starts a fresh chat beside it.
 * Only he names a chat that has nothing in it, since Oma names a chat from its first exchange. Null when there is none.
 */
fun emptyChat(meta: TabMeta, tasks: List<ChatTask>): String? =
    meta.chats.lastOrNull { chat -> !chat.bound && chat.key !in meta.archived && chat.key !in meta.names && tasks.none { it.chat == chat.id } }?.id

/**
 * [this] once the new chats with nowhere to go yet have had their time (the Critic, 2026-10-14): one that has rested
 * as long as a thread's tile stays ([tileLife]) leaves the strip, and so as not to lose what he made of it, one
 * he named or wrote in goes to Archived at [now], where he can bring it back; one with neither is let go. A chat
 * bound to a thread on the Mac rests as that thread does, and a pinned one never leaves. [threads] is every
 * thread as it stands, the chats among them, and [tasks] the Oma thread.
 */
fun TabMeta.retired(threads: List<AgentThread>, tasks: List<ChatTask>, now: Long): TabMeta =
    chats.fold(this) { meta, chat ->
        val thread = threads.firstOrNull { it.key == chat.key }
        when {
            chat.bound || chat.key in archived || chat.key in pinned || thread == null -> meta
            tileLife(thread.state, thread.worked, now) != TileLife.Gone -> meta
            chat.key in names || tasks.any { it.chat == chat.id } -> meta.archivedAs(chat.key, now)
            else -> meta.copy(chats = meta.chats - chat)
        }
    }

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
                .apply { if (chat.mac.isNotEmpty()) put("mac", chat.mac) }
                .apply { if (chat.front.isNotEmpty()) put("front", chat.front) }
                .apply { if (chat.used != 0L) put("used", chat.used) }
                .apply { chat.host?.let { put("host", it) } }
                .apply { if (chat.agent.isNotEmpty()) put("agent", chat.agent) }
        }))
    }
}.toString()

/**
 * The kept book; what can't be read is left out, and an unreadable whole is an empty book. A chat kept with a
 * phone builder (the "builder" of an earlier version, before new chats were the Mac's alone) loads unbound: its
 * entries stay in its tab, and its next words go to the Mac as a new chat's do. The "asking" an earlier version kept
 * while a chat waited for him to name a project is not read: its next words simply start the chat on the Mac.
 */
fun tabMetaFrom(text: String?): TabMeta {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return TabMeta()
    val names = saved.optJSONObject("names")?.let { o -> o.keys().asSequence().mapNotNull { key -> tabName(o.optString(key))?.let { key to it } }.toMap() }.orEmpty()
    val pinned = saved.optJSONArray("pinned")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } }.orEmpty().distinct()
    val archived = saved.optJSONObject("archived")?.let { o -> o.keys().asSequence().map { it to o.optLong(it) }.toMap() }.orEmpty()
    val chats = saved.optJSONArray("chats")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() && it.length <= ChatHistory.MAX_CHAT } ?: return@mapNotNull null
            NewChat(id, o.optLong("made"), o.optString("mac"), o.optString("front"), used = o.optLong("used"), host = if (o.has("host")) o.optString("host") else null, agent = o.optString("agent"))
        }
    }.orEmpty().distinctBy { it.id }
    return TabMeta(names, pinned, archived, chats)
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

/** What the chat and home make of the book at each look at [now]: archived tabs with new work back ([revive]), rested new chats away ([retired]). */
fun TabMeta.settled(threads: List<AgentThread>, tasks: List<ChatTask>, now: Long): TabMeta = revive(threads, tasks).retired(threads, tasks, now)
