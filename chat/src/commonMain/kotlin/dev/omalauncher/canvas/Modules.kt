package dev.omalauncher.canvas

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_B
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.builderApprovalTitle
import dev.omalauncher.assist.macApprovalDetail
import dev.omalauncher.assist.macApprovalNotice
import dev.omalauncher.assist.macThreadKey
import dev.omalauncher.oma.ChatHost

/*
 * Cockpit modules (Soma Canvas, cockpit/canvas/PRD.md §4): live views over shared state that can sit anywhere on any
 * screen's canvas, each drawn at four sizes that are only cell counts. This file is what every module reads from and
 * how a module's size follows from its cells; the modules themselves are composables beside it.
 */

/** A module's size, from the cells it is given: a mark, one row, a box, or most of the page. */
enum class ModuleSize { Dot, Line, Card, Full }

/**
 * The size [w] by [h] cells is on a grid of [columns]: 1×1 is a dot, one row is a line, and a box at least two thirds of the
 * grid wide and three rows tall (on the Fold's six columns: five wide) is full; anything else is a card.
 */
fun moduleSize(w: Int, h: Int, columns: Int): ModuleSize = when {
    w <= 1 && h <= 1 -> ModuleSize.Dot
    h <= 1 -> ModuleSize.Line
    h >= 3 && w >= if (columns <= 6) 5 else (columns * 2 + 2) / 3 -> ModuleSize.Full
    else -> ModuleSize.Card
}

/**
 * Something that waits for Boss, from any thread: a thread's tool on a computer, or a phone builder's. [title] and [detail]
 * are what the chat's own card says; [threadKey] is the tab the thread has; [ref] is what answering needs, kept opaque.
 */
data class ModuleAsk(val id: String, val title: String, val detail: String, val threadKey: String, val where: String, internal val ref: Any)

/**
 * What the modules read and the one thing they do: answer an ask, on his tap. Needs you lists approvals, the things he can
 * answer from a module; a thread that needs attention (its last request failed) shows in Threads and opens its chat.
 */
interface ModuleSource {
    fun threads(): List<AgentThread>

    fun asks(): List<ModuleAsk>

    /** His tap on approve or deny; [said] gets what there is to tell him, or null when there is nothing. */
    fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit)
}

/**
 * The modules' source on a device that has the chat: its threads and its waiting approvals, answered through the very
 * paths the chat's own approval cards use ([ChatHost.answerMac], [ChatHost.answerBuilder]), so the guards, the one-time
 * nonces and the expiry are the same wherever he taps.
 */
fun chatHostModules(host: ChatHost): ModuleSource = object : ModuleSource {
    override fun threads(): List<AgentThread> = host.threads()

    override fun asks(): List<ModuleAsk> {
        val mac = host.macPending().map { p ->
            ModuleAsk("mac:${p.ticket}", "Allow ${p.request.tool} on ${p.threadName}?", macApprovalDetail(p.request), macThreadKey(p.threadId), p.threadName, p)
        }
        val phone = host.builders().flatMap { (builder, state) ->
            state.pending.map { p ->
                ModuleAsk("phone:${p.nonce}", builderApprovalTitle(p.approval.tool, builder), p.approval.detail, if (builder == BuilderId.A) PHONE_THREAD else PHONE_THREAD_B, "Builder ${builder.letter}", p)
            }
        }
        return mac + phone
    }

    override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) {
        when (val ref = ask.ref) {
            is dev.omalauncher.assist.MacPending -> host.answerMac(ref.ticket, allow) { said(macApprovalNotice(it)) }
            is PendingApproval -> said(
                when (host.answerBuilder(ref, allow)) {
                    ApprovalOutcome.Decided -> null
                    ApprovalOutcome.Expired -> "That request has expired"
                    ApprovalOutcome.Locked -> "Unlock your phone to answer that request"
                },
            )
            else -> said("That request can't be answered here")
        }
    }
}

/** The order states are shown in, most urgent first, with the words a heading uses. */
val MODULE_STATES = listOf(
    ThreadState.NeedsOk to "needs your OK",
    ThreadState.Attention to "needs attention",
    ThreadState.Working to "working",
    ThreadState.Queued to "queued",
    ThreadState.Idle to "resting",
)

/** The most urgent state among [threads]; idle with none. */
fun worstState(threads: List<AgentThread>): ThreadState = MODULE_STATES.map { it.first }.firstOrNull { s -> threads.any { it.state == s } } ?: ThreadState.Idle
