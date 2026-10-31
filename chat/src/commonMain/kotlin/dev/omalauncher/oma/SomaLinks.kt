package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MAC_ID_SPLIT
import dev.omalauncher.assist.MAC_THREAD_PREFIX
import dev.omalauncher.assist.macThreadId
import dev.omalauncher.assist.macThreadKey

/*
 * Links into Soma (cockpit/DESIGN.md, slice 10): `soma://thread/<computer>/<thread>` opens that thread's tab, or the
 * chat that lives on it; `soma://thread/<thread>` the same, on whichever computer has it; `soma://chat/<id>` a new chat's
 * tab; `soma://phone/<A-D>` a phone builder's tab; `soma://soma` the Soma tab. The desktop app takes them from the system
 * (a click in a notification, the herd's pet, a terminal's `open`); the phone takes the same ones by an intent filter; and
 * both take them from a link tapped in a chat entry. A link only ever picks a tab: it sends nothing, answers nothing and
 * starts nothing.
 */

const val SOMA_SCHEME = "soma"

/**
 * A thread's id as the runner gives it: `t` and eight hex digits, or sixteen for a session it found, after a computer's id
 * and a slash if the link names one. Computer ids are lower case (oma-runner's pairing and the desktop's key files allow
 * no other), so the link is too.
 */
private val THREAD_ID = Regex("(?:[a-z0-9][a-z0-9-]{0,62}/)?t(?:[0-9a-f]{8}|[0-9a-f]{16})")

/** A phone builder's letter, as his tabs write it, or in lower case. */
private val BUILDER_LETTER = Regex("[A-Da-d]")

private val CHAT_ID = Regex("[a-z0-9]{1,${ChatHistory.MAX_CHAT}}")

/** Whether [url] is one of Soma's own, well-formed or not: the chat answers it rather than leaving it to the browser. */
fun isSomaLink(url: String) = url.trim().startsWith("$SOMA_SCHEME://", ignoreCase = true)

/** The link to the thread [threadId] ("testhost/t1a2b3c4d"). */
fun somaThreadLink(threadId: String) = "$SOMA_SCHEME://thread/$threadId"

/**
 * The tab key [url] asks for, before the book has its say ([askedTab] then turns a thread whose chat lives on it into
 * that chat's tab): a thread's ([MAC_THREAD_PREFIX] and its id), a new chat's ([chatKey]), a phone builder's, or [OMA_TAB]. Null for
 * anything that is not a well-formed Soma link, so a malformed or foreign link opens nothing.
 */
fun somaLinkKey(url: String): String? {
    val text = url.trim()
    if (text.length > 300 || !text.startsWith("$SOMA_SCHEME://", ignoreCase = true)) return null
    val rest = text.substring(SOMA_SCHEME.length + 3).substringBefore('?').substringBefore('#').trimEnd('/')
    return when {
        rest.isEmpty() || rest == "soma" -> OMA_TAB
        rest.startsWith("thread/") -> rest.removePrefix("thread/").takeIf { THREAD_ID.matches(it) }?.let { MAC_THREAD_PREFIX + it }
        rest.startsWith("chat/") -> rest.removePrefix("chat/").takeIf { CHAT_ID.matches(it) }?.let(::chatKey)
        rest.startsWith("phone/") -> rest.removePrefix("phone/").takeIf { BUILDER_LETTER.matches(it) }?.let { letter -> BuilderId.entries.first { it.letter == letter.uppercase() }.thread }
        else -> null
    }
}

/** The tab [url] opens with the book as it stands: a thread's chat when one lives on it ([askedTab]); null for no Soma link. */
fun somaLinkTab(url: String, meta: TabMeta): String? = somaLinkKey(url)?.let { key -> if (key == OMA_TAB) OMA_TAB else askedTab(key, meta) }

/** What a link comes to among the tabs there are now. */
sealed interface SomaLinkTarget {
    /** The tab to open. */
    data class Tab(val key: String) : SomaLinkTarget

    /** No tab: [said] says so in a few plain words. [listing] when a look at the computers could still find it. */
    data class Nothing(val said: String, val listing: Boolean = false) : SomaLinkTarget
}

/**
 * Where [url] leads with [threads] as they stand. A thread's own computer knows it by the runner's id, but each device
 * names its computers itself: the phone's first Mac has no name at all (MAC_FIRST), so a thread that is `t1a2b3c4d` there
 * is `jacks-mac-pro/t1a2b3c4d` on a desktop. So the id alone finds it on any computer, and a named computer finds it
 * there or on the device's first Mac, whose name it can't know. Nothing that is no thread here is ever given a tab.
 */
fun somaLinkTarget(url: String, threads: List<AgentThread>, meta: TabMeta): SomaLinkTarget {
    val key = somaLinkKey(url) ?: return SomaLinkTarget.Nothing("That link doesn't open anything")
    if (key == OMA_TAB) return SomaLinkTarget.Tab(OMA_TAB)
    val id = macThreadId(key)
    if (id != null) {
        val runnerId = id.substringAfterLast(MAC_ID_SPLIT)
        val made = threads.mapNotNull { macThreadId(it.key) } + meta.chats.map { it.mac }.filter { it.isNotEmpty() }
        val named = if (MAC_ID_SPLIT in id) listOf(id, runnerId).filter { it in made } else made.filter { it.substringAfterLast(MAC_ID_SPLIT) == runnerId }
        val tab = named.firstOrNull()?.let { askedTab(macThreadKey(it), meta) }
        return if (tab != null) SomaLinkTarget.Tab(tab) else SomaLinkTarget.Nothing("Can't find that thread", listing = true)
    }
    if (threads.any { it.key == key }) return SomaLinkTarget.Tab(key)
    val builder = BuilderId.ofThread(key)
    return SomaLinkTarget.Nothing(if (builder != null) "Phone ${builder.letter} has no tab here" else "Can't find that chat")
}
