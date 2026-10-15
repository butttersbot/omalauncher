package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.NEW_CHAT_NAME
import dev.omalauncher.oma.TaskStatus

/*
 * Where a new chat starts on the Mac (Boss, 2026-10-13: "The plus button should always open up a Mac session";
 * 2026-10-14: "i shouldn't be asked which project"). A new chat's first words always go to a new thread on the
 * Mac and nothing asks him where: in the folder of the thread the front agent named, or else in one default
 * folder, from which the session can go into any project itself. The rules are plain functions here, unit tested.
 */

/**
 * The folder a new chat's thread is made in when no thread was named. The runner takes a folder under its allowed
 * roots, the root itself included, and `~/Projects` is the first of those (tools/mac-runner/oma-runner, ROOTS).
 */
const val MAC_DEFAULT_PROJECT = "~/Projects"

/**
 * What a new chat's entry waits under while its thread in [MAC_DEFAULT_PROJECT] is made. The runner's ids are a
 * "t" and hex digits, so no thread on the Mac is ever called this, and nothing follows it there.
 */
const val MAC_NEW_THREAD = "new-chat"

/**
 * What a new chat's words say while the Mac is out of reach. They are kept, nothing is sent and no phone builder
 * takes them: `[ try again ]` under them asks Oma again once the Mac is back.
 */
const val MAC_CHAT_UNREACHABLE = "Mac unreachable — this chat is for the Mac."

/** What becomes of a new chat's first words ([chatPlace]). */
enum class ChatPlace {
    /** A new thread is made for them on the Mac. */
    Start,

    /** No Mac is set up on this phone: said, and nothing sent. */
    NotSetUp,

    /** The Mac did not answer at the last try: his words wait with `[ try again ]` under them. */
    Unreachable,
}

/** Where a new chat's first words go: to a new thread on a Mac that is [setUp] and [reachable]; otherwise they stay, saying why. */
fun chatPlace(setUp: Boolean, reachable: Boolean): ChatPlace = when {
    !setUp -> ChatPlace.NotSetUp
    !reachable -> ChatPlace.Unreachable
    else -> ChatPlace.Start
}

/**
 * The thread whose folder a new chat's first thread is made in: the one the front agent named with a mark
 * ([FrontRoute.Mac]), when it has a folder. Null for anything else (no mark, the build mark, an id not on the
 * board, work held back from the front agent, or a front agent that failed): the thread is then made in
 * [MAC_DEFAULT_PROJECT].
 */
fun chatBeside(route: FrontRoute?): MacThread? = (route as? FrontRoute.Mac)?.thread?.takeIf { macProjectKey(it.project).isNotEmpty() }

/** What the thread made in the default folder is called: the chat's own name, its auto title once it has one. */
fun chatThreadName(title: String?): String = title?.trim()?.takeIf { it.isNotEmpty() }?.take(FRESH_NAME_CHARS)?.trimEnd() ?: NEW_CHAT_NAME

/**
 * What the thread made for a new chat's first words sent straight to the Mac ([dev.omalauncher.oma.Outgoing.ChatMac])
 * is called: the chat's [title] if it has one, else the first words of his [prompt] ([titleFallback]), since
 * without the front agent no title is coming before the thread is made.
 */
fun chatDirectName(title: String?, prompt: String): String = chatThreadName(title?.takeIf { it.isNotBlank() } ?: titleFallback(prompt))

/**
 * The entry, filed at [time], of the new chat [chat]'s first words sent straight to the Mac: his words as they are
 * ([ChatTask.direct], so nothing is put ahead of them), waiting while their thread called [name] is made.
 */
fun macChatDirect(time: Long, prompt: String, chat: String, name: String) =
    macChatStart(ChatTask(time, "", prompt, direct = true, chat = chat), name)

/**
 * The entry of a new chat's first words while their thread called [name] is made in the default folder: the
 * receipt of the new thread, waiting under [MAC_NEW_THREAD] and marked [ChatTask.fresh] until the thread is there.
 */
fun macChatStart(task: ChatTask, name: String) =
    task.copy(agent = macReceipt(name), thread = MAC_NEW_THREAD, threadName = macName(name), status = TaskStatus.Queued, result = "", fresh = true)

/** Who the launcher's own words in the chat are from: Oma, never a model. */
const val OMA_NAME = "Oma"
