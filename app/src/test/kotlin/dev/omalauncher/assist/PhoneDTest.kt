package dev.omalauncher.assist

import dev.omalauncher.linux.AGENTS
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.FollowUp
import dev.omalauncher.oma.FollowUpQueue
import dev.omalauncher.oma.TaskStatus
import org.junit.Assert.*
import org.junit.Test

class PhoneDTest {
    @Test fun mailRoutesToDWithoutOpeningAnOwnerThreadOrTakingGeneralWork() {
        assertEquals(BuilderId.D, mailBuilder("D", emptyMap()))
        assertEquals(BuilderId.D, mailBuilder("phone-d", emptyMap()))
        assertEquals(4, AgentTasks.builders.size)
        val busy = BuilderId.entries.associateWith { BuilderLoad(true, if (it == BuilderId.D) 0 else 3) }
        assertEquals(BuilderId.A, pickBuilder(null, busy))
        assertEquals(BuilderId.D, pickBuilder(BuilderId.D, busy))
        val tasks = listOf(ChatTask(1, "Codex", "status", builder = "D"))
        assertFalse(builderShown(BuilderId.D, tasks, true))
        assertEquals(listOf("phone", "phone-b", "phone-c"), phoneThreads(tasks, emptyMap()).map { it.key })
        assertFalse(builderWorktreeWanted(BuilderId.D, mapOf(BuilderId.A to true), true))
        assertFalse(builderWorktreeWanted(BuilderId.A, mapOf(BuilderId.D to true), true))
    }

    @Test fun dRequiresCodexAndUsesTheSameCommandAsBWithTerraAndItsOwnIdentity() {
        val codex = AGENTS.first { it.bin == "codex" }
        val claude = AGENTS.first { it.bin == "claude" }
        assertEquals(BuilderAgent(codex, "gpt-6.1-terra"), builderAgent(BuilderId.D, listOf(codex, claude), claude))
        assertNull(builderAgent(BuilderId.D, listOf(claude), claude))
        val d = codexBuilderCommand(emptyList(), PHONE_D_CODEX_MODEL, null, true)
        val b = codexBuilderCommand(emptyList(), PHONE_B_CODEX_MODEL, null, true)
        assertEquals(b.replace(PHONE_B_CODEX_MODEL, PHONE_D_CODEX_MODEL), d)
        val resumed = codexBuilderCommand(emptyList(), PHONE_D_CODEX_MODEL, "11111111-1111-1111-1111-111111111111", true)
        assertTrue(resumed.startsWith("codex exec resume --json"))
        assertTrue(d.contains("--dangerously-bypass-approvals-and-sandbox"))
        assertEquals("export OMA_BUILDER=D\n$d", withSomaArtifacts("export OMA_BUILDER=D\n$d", BuilderId.D))
        val mail = AgentMail("mail-1", "macbook-pro", "codex", "sender", "task", "D", "status", "")
        assertTrue(mail.wrapper(BuilderId.D).contains("oma msg send macbook-pro:sender --reply-to mail-1 --as D"))
    }

    @Test fun mailWaitsBehindTheRunningTaskAndStopsDropQueuedWork() {
        val queue = FollowUpQueue()
        assertTrue(queue.add(FollowUp(2, "status", earlier = "[Agent mail]", mailSender = "mac:sender", mailId = "m2")))
        assertEquals("m2", queue.after(TaskStatus.Done).next!!.mailId)
        assertNull(queue.after(TaskStatus.Done).next)
        queue.add(FollowUp(3, "status", mailSender = "mac:sender", mailId = "m3"))
        assertNull(queue.after(TaskStatus.Stopped).next)
        assertTrue(queue.waiting.isEmpty())
    }
}
