package dev.omalauncher.oma

import dev.omalauncher.assist.ClaudeProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatHistoryTest {
    private fun task(time: Long, prompt: String = "prompt $time") = ChatTask(time, "Claude Code", prompt)

    @Test fun aTaskSurvivesSavingAndLoading() {
        val task = ChatTask(
            time = 1_760_000_000_000, agent = "Claude Code", prompt = "Book \"a table\"\nfor two", images = listOf("/root/.local/share/omalauncher/attachments/a.png"),
            screen = true, newThread = true, steps = listOf("Bash: oma-phone read", "Read: /tmp/x"),
            approvals = listOf(ChatApproval("Bash", "rm -rf build", ApprovalAnswer.Denied), ChatApproval("Write", "{}", ApprovalAnswer.Approved)),
            result = "Done ✓", status = TaskStatus.Done,
        )
        assertEquals(listOf(task), ChatHistory.decode(ChatHistory.encode(listOf(task))))
    }

    @Test fun onlyTheNewestFiftyTasksAreKept() {
        var tasks = emptyList<ChatTask>()
        for (i in 1..120L) tasks = ChatHistory.append(tasks, task(i))
        assertEquals(ChatHistory.MAX_TASKS, tasks.size)
        assertEquals(71L, tasks.first().time); assertEquals(120L, tasks.last().time)
        assertEquals(ChatHistory.MAX_TASKS, ChatHistory.decode(ChatHistory.encode((1..200L).map(::task))).size)
    }

    @Test fun eachTaskIsBounded() {
        val huge = ChatTask(
            1, "a".repeat(500), "p".repeat(100_000), images = (1..9).map { "/img/$it" }, steps = (1..500).map { "step $it " + "s".repeat(1000) },
            approvals = (1..90).map { ChatApproval("t".repeat(900), "d".repeat(50_000), ApprovalAnswer.Approved) }, result = "start" + "r".repeat(100_000) + "end",
        )
        val kept = ChatHistory.bounded(huge)
        assertEquals(ChatHistory.MAX_PROMPT, kept.prompt.length)
        assertEquals(ChatHistory.MAX_IMAGES, kept.images.size)
        assertEquals(ChatHistory.MAX_STEPS, kept.steps.size); assertTrue(kept.steps.all { it.length <= ChatHistory.MAX_STEP })
        assertTrue(kept.steps.last().startsWith("step 500 "))
        assertEquals(ChatHistory.MAX_APPROVALS, kept.approvals.size); assertTrue(kept.approvals.all { it.detail.length == ChatHistory.MAX_DETAIL && it.tool.length == 60 })
        assertEquals(ChatHistory.MAX_RESULT, kept.result.length); assertTrue(kept.result.endsWith("end"))
        // Fifty of the largest possible tasks stay a small file.
        assertTrue(ChatHistory.encode(List(50) { huge.copy(time = it.toLong()) }).length < 2_500_000)
    }

    @Test fun approvalKeysAndImageBytesAreNeverSaved() {
        val saved = ChatHistory.encode(listOf(task(1).copy(screen = true, approvals = listOf(ChatApproval("Bash", "ls", ApprovalAnswer.Waiting, key = "secret-nonce")))))
        assertFalse(saved.contains("secret-nonce"))
        val fields = JSONObject(saved).getJSONArray("tasks").getJSONObject(0)
        assertEquals(setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals"), fields.keys().asSequence().toSet())
        assertEquals(true, fields.get("screen"))
    }

    @Test fun aRestartEndsWhatWasRunningAndWaiting() {
        val before = listOf(
            task(1).copy(status = TaskStatus.Done, approvals = listOf(ChatApproval("Bash", "ls", ApprovalAnswer.Approved, key = "k1"))),
            task(2).copy(status = TaskStatus.Running, approvals = listOf(ChatApproval("Bash", "rm", ApprovalAnswer.Waiting, key = "k2"))),
        )
        val after = ChatHistory.afterRestart(before)
        assertEquals(TaskStatus.Done, after[0].status); assertEquals(ApprovalAnswer.Approved, after[0].approvals.single().answer)
        assertEquals(TaskStatus.Interrupted, after[1].status); assertEquals(ApprovalAnswer.Unanswered, after[1].approvals.single().answer)
        assertTrue(after.flatMap { it.approvals }.all { it.key.isEmpty() })
    }

    @Test fun damagedFilesLoadAsWhatCanBeRead() {
        assertEquals(emptyList<ChatTask>(), ChatHistory.decode(""))
        assertEquals(emptyList<ChatTask>(), ChatHistory.decode("{not json"))
        assertEquals(emptyList<ChatTask>(), ChatHistory.decode("""{"tasks":"no"}"""))
        val mixed = """{"version":1,"tasks":[{"time":5,"prompt":"ok","status":"done"},{"prompt":"no time"},7,{"time":6,"prompt":"odd","status":"??","approvals":[{"tool":"Bash","answer":"??"}]}]}"""
        val read = ChatHistory.decode(mixed)
        assertEquals(listOf(5L, 6L), read.map { it.time })
        assertEquals(TaskStatus.Interrupted, read[1].status); assertEquals(ApprovalAnswer.Unanswered, read[1].approvals.single().answer)
    }

    @Test fun aFileWithMoreThanFiftyTasksLoadsOnlyTheNewestFifty() {
        // Written by hand, not by encode, which would already have cut it.
        val text = (1..75).joinToString(",", """{"version":1,"tasks":[""", "]}") { """{"time":$it,"prompt":"p$it","status":"done"}""" }
        val read = ChatHistory.decode(text)
        assertEquals(ChatHistory.MAX_TASKS, read.size)
        assertEquals(26L, read.first().time); assertEquals(75L, read.last().time)
    }

    @Test fun anOversizedFileIsNotRead() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val file = File(dir, "oma-chat.json")
            file.writeText(ChatHistory.encode(listOf(task(1))))
            assertEquals(1, ChatStore(file).apply { load() }.tasks.size)
            // The same readable file, one byte over and exactly at the limit.
            assertTrue(ChatStore(file, maxBytes = file.length() - 1).apply { load() }.tasks.isEmpty())
            assertEquals(1, ChatStore(file, maxBytes = file.length()).apply { load() }.tasks.size)
            // The real limit is far above the largest thread this app writes.
            assertTrue(ChatHistory.MAX_FILE_BYTES > 3L * 2_500_000)
        } finally { dir.deleteRecursively() }
    }

    @Test fun onlyUnsentChatAttachmentsAreSweptAtStartup() {
        val dir = Files.createTempDirectory("oma-drafts-test").toFile()
        try {
            val attachments = File(dir, "attachments").apply { mkdirs() }
            fun image(name: String) = File(attachments, name).apply { writeText("x") }
            val unsent = image("11111111-1111-4111-8111-111111111111.jpg")
            val sent = image("22222222-2222-4222-8222-222222222222.png")
            val fromHome = image("33333333-3333-4333-8333-333333333333.webp") // Never listed: not the chat's.
            val phone = image("phone-screen-44444444-4444-4444-8444-444444444444.png")
            val list = File(dir, "oma-chat-drafts.txt")
            val before = ChatDrafts(list, attachments)
            before.add(listOf(unsent, sent))
            // The process dies after `sent` went out in a task but before it came off the list.
            val saved = listOf(task(1).copy(images = listOf("/root/.local/share/omalauncher/attachments/${sent.name}")))

            ChatDrafts(list, attachments).sweep(saved)
            assertFalse(unsent.exists())
            assertTrue(sent.exists()); assertTrue(fromHome.exists()); assertTrue(phone.exists())
            assertFalse(list.exists())

            // Removed or sent drafts come off the list and are left alone by a later sweep.
            val again = ChatDrafts(list, attachments)
            again.add(listOf(fromHome)); again.remove(listOf(fromHome)); again.sweep(emptyList())
            assertTrue(fromHome.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aDraftListCannotNameAnythingButImportedImages() {
        val saved = emptyList<ChatTask>()
        val good = "11111111-1111-4111-8111-111111111111.jpg"
        assertEquals(listOf(good), unsentDrafts(listOf(good, good, "../oma-chat.json", "/etc/passwd", "a.jpg", "phone-screen-$good", "$good/..", ""), saved))
    }

    @Test fun stepsAreOneLineAndDoNotRepeat() {
        var t = task(1)
        t = ChatHistory.withStep(t, "Bash:  oma-phone\n read")
        t = ChatHistory.withStep(t, "Bash: oma-phone read")
        t = ChatHistory.withStep(t, "  ")
        t = ChatHistory.withStep(t, "Read: /tmp/x")
        assertEquals(listOf("Bash: oma-phone read", "Read: /tmp/x"), t.steps)
        assertEquals("Bash: oma-phone read", ClaudeProtocol.step(JSONObject("""{"type":"tool_use","name":"Bash","input":{"command":"oma-phone read\nsecond line"}}""")))
        assertEquals("Read: /tmp/x", ClaudeProtocol.step(JSONObject("""{"name":"Read","input":{"file_path":"/tmp/x"}}""")))
        assertEquals("TodoWrite", ClaudeProtocol.step(JSONObject("""{"name":"TodoWrite","input":{"todos":[]}}""")))
        assertEquals("a tool", ClaudeProtocol.step(JSONObject("{}")))
    }

    @Test fun theStoreKeepsTheThreadAcrossAProcess() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val file = File(dir, "oma-chat.json")
            val first = ChatStore(file).apply { load() }
            assertTrue(first.tasks.isEmpty())
            first.begin(task(1))
            first.update(1, save = true) { it.copy(result = "answer", status = TaskStatus.Done) }
            first.begin(task(2))
            first.update(2) { ChatHistory.withStep(it, "Bash: ls") } // Not saved: the process dies here.
            first.update(99, save = true) { it.copy(result = "no such task") }

            val second = ChatStore(file).apply { load() }
            assertEquals(listOf(1L, 2L), second.tasks.map { it.time })
            assertEquals("answer", second.tasks[0].result); assertEquals(TaskStatus.Done, second.tasks[0].status)
            assertEquals(TaskStatus.Interrupted, second.tasks[1].status); assertEquals("prompt 2", second.tasks[1].prompt)
            assertEquals(listOf("oma-chat.json"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
