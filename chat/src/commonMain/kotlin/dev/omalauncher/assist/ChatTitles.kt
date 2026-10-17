package dev.omalauncher.assist

import dev.omalauncher.linux.shellQuote
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.Markdown
import dev.omalauncher.oma.TAB_NAME_CHARS
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject

/*
 * New chats name themselves as Claude Code's sessions do (Boss, 2026-10-13: "we'll have those auto named based
 * on context, the same way claude code does it"): after the first exchange the front agent's fast model is asked
 * once, with no tools and no conversation, for a few words that say what the chat is about. What it says is
 * cleaned up here, and a model that cannot be asked or says nothing usable leaves the first words of his message
 * as the name. A name he gave the tab himself is never replaced. All of it decides in plain functions, unit
 * tested; ChatTitles only runs the one process and writes the result into TabBook.
 */

/** What the model is asked, word for word, ahead of the exchange. Tried on real requests (see the report of 2026-10-13). */
const val TITLE_ASK = "Give a 2–5 word title for this conversation. Reply with the title only."

/** How much of his message and of the answer the model is shown: the start says what a chat is about. */
const val TITLE_SIDE_CHARS = 600

/** How long a first message may wait for its answer before the chat is named from his words alone. */
const val TITLE_WAIT_MS = 20_000L

/** How many words of his message name a chat when the model can't. */
const val TITLE_FALLBACK_WORDS = 4

/** More words than this is the model talking, not a title. */
private const val TITLE_MAX_WORDS = 8

/** What a chat is named from: his first [message], and the [answer] to it when there is one. */
data class TitleAsk(val message: String, val answer: String? = null)

/**
 * What chat [entries], oldest first, can be named from at [now]; null while it is too soon. Once his first
 * message has its answer, from both; while it is still being answered after [TITLE_WAIT_MS], from his words
 * alone, so a long task does not leave the tab "New chat" for its whole run.
 */
fun titleDue(entries: List<ChatTask>, now: Long): TitleAsk? {
    val first = entries.firstOrNull() ?: return null
    val open = first.status == TaskStatus.Running || first.status == TaskStatus.Queued
    if (!open) return TitleAsk(first.prompt, first.result.takeIf { first.status == TaskStatus.Done && it.isNotBlank() })
    return if (now - first.time >= TITLE_WAIT_MS) TitleAsk(first.prompt) else null
}

/** The whole of what the model is sent. */
fun titlePrompt(ask: TitleAsk): String =
    TITLE_ASK + "\n\nBoss: " + ask.message.trim().take(TITLE_SIDE_CHARS) + (ask.answer?.let { "\n\nAnswer: " + it.trim().take(TITLE_SIDE_CHARS) } ?: "")

/**
 * The guest command that asks for a title: the fast model, no tools, no session to continue, from an empty
 * folder so no project's instructions are read, and JSON out so the answer is told from anything else printed.
 */
fun titleCommand(ask: TitleAsk): String =
    "mkdir -p /tmp/oma-title && cd /tmp/oma-title && claude -p --model $QUICK_MODEL --output-format json --tools '' -- ${shellQuote(titlePrompt(ask))} < /dev/null"

/** The title in what the command printed: the `result` of its JSON line; null when it failed or said nothing. */
fun titleFrom(output: String): String? = output.lineSequence().mapNotNull { line ->
    runCatching { JSONObject(line.trim()) }.getOrNull()?.takeIf { it.has("result") && !it.optBoolean("is_error") }?.optString("result")
}.lastOrNull()?.takeIf { it.isNotBlank() }

/** [text] cut to [TAB_NAME_CHARS] at a word where there is one. */
private fun capped(text: String): String {
    if (text.length <= TAB_NAME_CHARS) return text
    val cut = text.take(TAB_NAME_CHARS + 1)
    val space = cut.lastIndexOf(' ')
    return (if (space > TAB_NAME_CHARS / 2) cut.take(space) else cut.take(TAB_NAME_CHARS)).trim()
}

/**
 * What the model said as a tab's name: its first line, without Markdown, a "Title:" ahead of it, quotes or the
 * punctuation at its ends, at most [TAB_NAME_CHARS]. Null when nothing is left or it is too long to be a title.
 */
fun titleClean(raw: String): String? {
    val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    val plain = Markdown.plain(line).replace(Regex("[*_`#]"), "").trim()
        .replace(Regex("^(?i)title\\s*:\\s*"), "")
        .trim { it.isWhitespace() || it in "\"'“”‘’«».,;:!?-–—" }
        .replace(Regex("\\s+"), " ")
    if (plain.isEmpty() || plain.split(' ').size > TITLE_MAX_WORDS) return null
    return capped(plain).ifEmpty { null }
}

/** The name of a chat the model could not name: the first [TITLE_FALLBACK_WORDS] words of his [message]. */
fun titleFallback(message: String): String {
    val words = Markdown.plain(message).replace(Regex("[*_`#\"“”]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    return capped(words.take(TITLE_FALLBACK_WORDS).joinToString(" ").trim { it in ".,;:!?" }).ifEmpty { dev.omalauncher.oma.NEW_CHAT_NAME }
}
