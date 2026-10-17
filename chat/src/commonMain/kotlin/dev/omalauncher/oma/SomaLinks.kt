package dev.omalauncher.oma

import dev.omalauncher.assist.MAC_THREAD_PREFIX

/*
 * Links into Soma (cockpit/DESIGN.md, slice 10): `soma://thread/<computer>/<thread>` opens that thread's tab, or the
 * chat that lives on it; `soma://chat/<id>` a new chat's tab; `soma://soma` the Soma tab. The desktop app takes them
 * from the system (a click in a notification, the herd's pet, a terminal's `open`); the phone takes the same ones by an
 * intent filter. A link only ever picks a tab: it sends nothing, answers nothing and starts nothing.
 */

const val SOMA_SCHEME = "soma"

/**
 * A thread's id as the runner gives it: `t` and eight hex digits, after its computer's id and a slash on any but the first.
 * Computer ids are lower case (oma-runner's pairing and the desktop's key files allow no other), so the link is too.
 */
private val THREAD_ID = Regex("(?:[a-z0-9][a-z0-9-]{0,62}/)?t[0-9a-f]{8}")

private val CHAT_ID = Regex("[a-z0-9]{1,${ChatHistory.MAX_CHAT}}")

/** The link to the thread [threadId] ("testhost/t1a2b3c4d"). */
fun somaThreadLink(threadId: String) = "$SOMA_SCHEME://thread/$threadId"

/**
 * The tab key [url] asks for, before the book has its say ([askedTab] then turns a thread whose chat lives on it into
 * that chat's tab): a thread's ([MAC_THREAD_PREFIX] and its id), a new chat's ([chatKey]), or [OMA_TAB]. Null for
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
        else -> null
    }
}

/** The tab [url] opens with the book as it stands: a thread's chat when one lives on it ([askedTab]); null for no Soma link. */
fun somaLinkTab(url: String, meta: TabMeta): String? = somaLinkKey(url)?.let { key -> if (key == OMA_TAB) OMA_TAB else askedTab(key, meta) }
