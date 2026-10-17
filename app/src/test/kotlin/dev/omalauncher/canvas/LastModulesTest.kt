package dev.omalauncher.canvas

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.MacHandoff
import dev.omalauncher.assist.MacThread
import dev.omalauncher.assist.MacThreadStatus
import dev.omalauncher.assist.RegisteredProject
import dev.omalauncher.assist.ThreadHome
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastModulesTest {
    @Test fun theTimelineIsTheChatsOwnEntriesNewestFirst() {
        val tasks = listOf(
            ChatTask(1_000, "→ cockpit canvas", "build slice 3c\nand test it", thread = "mac/t1", threadName = "cockpit canvas", status = TaskStatus.Done),
            ChatTask(3_000, "→ notch trading", "run swift build", thread = "mac/t2", threadName = "notch trading", status = TaskStatus.Running),
            ChatTask(2_000, "Haiku", "what's left?", status = TaskStatus.Failed),
        )
        val events = moduleTimeline(tasks)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), events.map { it.time })
        assertEquals("notch trading · run swift build", events[0].text)
        assertEquals(ThreadState.Working, events[0].state)
        assertEquals(ThreadState.Attention, events[1].state)
        assertEquals("cockpit canvas · build slice 3c", events[2].text)
        assertEquals("mac:mac/t1", events[2].threadKey)
        assertEquals("", events[1].threadKey)
        assertEquals(2, moduleTimeline(tasks, limit = 2).size)
        // Every status has its state: waiting on him, queued, stopped, and the app dying under it.
        val waiting = ChatTask(1, "a", "x", approvals = listOf(dev.omalauncher.oma.ChatApproval("Bash", "ls", dev.omalauncher.oma.ApprovalAnswer.Waiting)))
        assertEquals(
            listOf(ThreadState.NeedsOk, ThreadState.Queued, ThreadState.Idle, ThreadState.Attention),
            listOf(waiting, waiting.copy(status = TaskStatus.Queued), waiting.copy(status = TaskStatus.Stopped), waiting.copy(status = TaskStatus.Interrupted)).flatMap { moduleTimeline(listOf(it)) }.map { it.state },
        )
        // An image-only request and a stored receipt with no thread name read cleanly.
        assertEquals("notch trading", moduleTimeline(listOf(ChatTask(1, "→ notch trading", "", thread = "mac/t2"))).single().text)
        assertEquals("only this", moduleTimeline(listOf(ChatTask(1, "", "\n\n  only this  \nnot this"))).single().text)
    }

    @Test fun aNewThreadStartsOnTheFirstComputerThatAnswers() {
        val down = dev.omalauncher.oma.HostChoice("mac", "Mac Pro", false, listOf("claude"))
        val up = dev.omalauncher.oma.HostChoice("mbp", "MacBook Pro", true, listOf("claude", "codex"), default = dev.omalauncher.assist.MacPick("codex", "gpt-5.5"))
        assertEquals(NewThreadPick("MacBook Pro", "codex", "gpt-5.5"), moduleNewThread(listOf(down, up)))
        assertEquals(NewThreadPick("Mac Pro", "claude", "default"), moduleNewThread(listOf(down)))
        assertNull(moduleNewThread(emptyList()))
    }

    @Test fun aProjectGathersItsThreadsByFolder() {
        val registry = listOf(RegisteredProject("omalauncher", listOf("~/ClaudeSync/projects/omalauncher", "~/Projects/omalauncher", "~/Projects/omalauncher-*"), "CURRENT-STATE.md"))
        fun mac(id: String, project: String) = MacThread(id, id, project, ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList())
        val macThreads = listOf(mac("mac/t1", "/Users/jackkim/Projects/omalauncher-c3"), mac("mac/t2", "/Users/jackkim/ClaudeSync/projects/pond"), mac("mac/t3", "~/Projects/omalauncher"))
        val threads = listOf(AgentThread("mac:mac/t1", "a", ThreadState.Working, "", ""), AgentThread("mac:mac/t3", "c", ThreadState.NeedsOk, "", ""))
        val handoff = MacHandoff("omalauncher", "CURRENT-STATE.md", 1, "# T\n## Next\n- Ship 3c.\n")
        val p = moduleProject("omalauncher", registry, macThreads, threads, handoff)
        assertEquals(listOf(ThreadState.Working, ThreadState.NeedsOk), p.states)
        assertEquals("~/ClaudeSync/projects/omalauncher", p.folder)
        assertEquals("Ship 3c.", p.next)
        // A tap opens the thread that most needs him: t3 waits on an OK, t1 only works.
        assertEquals("mac:mac/t3", p.firstThread)
        val empty = moduleProject("cove", registry, macThreads, threads, null)
        assertTrue(empty.states.isEmpty())
        assertNull(empty.firstThread)
        assertEquals("", empty.next)
    }
}
