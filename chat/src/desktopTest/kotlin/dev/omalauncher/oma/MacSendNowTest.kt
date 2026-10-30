package dev.omalauncher.oma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.omalauncher.assist.MacAnswer
import dev.omalauncher.assist.MacApproval
import dev.omalauncher.assist.MacEvent
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacReply
import dev.omalauncher.assist.MacSentNow
import dev.omalauncher.assist.macApply
import dev.omalauncher.assist.parseMacSentNow
import dev.omalauncher.assist.waitingLine
import org.junit.Test
import org.junit.Assert.assertEquals

/** Send now on a computer's thread (Boss, 2026-10-30): the phone's button, for words the runner holds behind a turn. */
class MacSendNowTest : ChatScreenTestBase() {
    private val at = System.currentTimeMillis()
    private val thread = "testhost/t1"
    private fun entry(time: Long, words: String, status: TaskStatus) = ChatTask(time, "Sent to Work", words, thread = thread, threadName = "Work", status = status, direct = true)

    @Test fun aTurnStoppedForWordsSentNowLeavesAllThatWaited() {
        val tasks = listOf(entry(1, "running", TaskStatus.Running), entry(2, "go now", TaskStatus.Queued), entry(3, "behind", TaskStatus.Queued))
        val stopped = macApply(tasks, thread, MacEvent.Stopped(9, 0, 0, kept = true))
        assertEquals(listOf(TaskStatus.Stopped, TaskStatus.Queued, TaskStatus.Queued), stopped.map { it.status })
        val began = macApply(stopped, thread, MacEvent.Message(10, 0, "go now"))
        assertEquals(listOf(TaskStatus.Stopped, TaskStatus.Running, TaskStatus.Queued), began.map { it.status })
    }

    @Test fun aPlainStopStillEndsAllThatWaited() {
        val tasks = listOf(entry(1, "running", TaskStatus.Running), entry(2, "behind", TaskStatus.Queued))
        assertEquals(listOf(TaskStatus.Stopped, TaskStatus.Stopped), macApply(tasks, thread, MacEvent.Stopped(9, 0, 1)).map { it.status })
    }

    @Test fun onlyWordsTheComputerHoldsCanBeSentNow() {
        val queued = entry(2, "x", TaskStatus.Queued)
        assertEquals(true, waitingLine(queued, macLabel = "Queued on MG1")?.sendNow)
        assertEquals(false, waitingLine(queued, macLabel = "Queued on MG1")?.cancel)
        assertEquals(false, waitingLine(queued, macLabel = "Sending to MG1")?.sendNow)
        assertEquals(false, waitingLine(queued, macLabel = "Sent; MG1 is out of reach")?.sendNow)
    }

    @Test fun whatTheRunnerSaysOfWordsSentNow() {
        fun read(body: String) = parseMacSentNow(MacReply(200, body))
        assertEquals(MacAnswer.Ok(MacSentNow.Stopping), read("""{"ok": true, "sent": "now"}"""))
        assertEquals(MacAnswer.Ok(MacSentNow.Next), read("""{"ok": true, "sent": "next"}"""))
        assertEquals(MacAnswer.Ok(MacSentNow.Gone), read("""{"ok": true, "sent": "gone"}"""))
        assertEquals(MacAnswer.Ok(MacSentNow.Question), read("""{"ok": true, "sent": "question"}"""))
        assertEquals(MacAnswer.Ok(MacSentNow.Refused("updating")), read("""{"ok": true, "sent": "refused", "error": "updating"}"""))
    }

    private fun question() = MacPending(thread, "Work", MacApproval("a1", "n1", "AskUserQuestion", """{"questions":[{"question":"Which?","options":[{"label":"One"}]}]}""", ""), "t1", null)

    @OptIn(ExperimentalTestApi::class)
    @Test fun theButtonStandsUnderHeldWordsOnlyWhereItCanWork() = runComposeUiTest {
        var runnerCan by mutableStateOf(true)
        var waiting by mutableStateOf(emptyList<MacPending>())
        val sent = mutableListOf<Long>()
        val host = object : SampleHost() {
            override fun tasks() = listOf(entry(at, "running", TaskStatus.Running), entry(at + 1, "go now", TaskStatus.Queued))
            override fun macLabel(task: ChatTask) = if (task.status == TaskStatus.Queued) "Queued on MG1" else "Working on MG1"
            override fun macPending() = waiting
            override fun macSendsNow(thread: String) = runnerCan
            override fun sendMacNow(time: Long, said: (String?) -> Unit) { sent += time; said(null) }
        }
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, null, {}, null, {}) }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ send now ]").assertExists()
        // No [ cancel ]: the computer's queue can't take words back one by one, as before.
        onAllNodesWithText("[ cancel ]").assertCountEquals(0)
        onNodeWithText("[ send now ]").performClick()
        mainClock.advanceTimeBy(100)
        assertEquals(listOf(at + 1), sent)
        // A question form of the thread waits: Send now would end it unanswered.
        runOnIdle { waiting = listOf(question()) }
        mainClock.advanceTimeBy(1200)
        onAllNodesWithText("[ send now ]").assertCountEquals(0)
        // A runner from before can only stop and drop, so nothing is offered.
        runOnIdle { waiting = emptyList(); runnerCan = false }
        mainClock.advanceTimeBy(1200)
        onAllNodesWithText("[ send now ]").assertCountEquals(0)
    }
}
