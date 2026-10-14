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

    @Test fun aRestartDropsWaitingFollowUpsAndSaysSo() {
        val before = listOf(task(1).copy(status = TaskStatus.Running), task(2).copy(status = TaskStatus.Queued), task(3).copy(status = TaskStatus.Queued))
        val after = ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(before)))
        assertEquals(listOf(TaskStatus.Interrupted, TaskStatus.Dropped, TaskStatus.Dropped), after.map { it.status })
        assertEquals(listOf("", FollowUpQueue.RESTARTED, FollowUpQueue.RESTARTED), after.map { it.result })
        assertEquals(listOf("prompt 2", "prompt 3"), after.drop(1).map { it.prompt })
        // Said once: the next restart finds them already dropped.
        assertEquals(after, ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(after))))
    }

    @Test fun aQuickQuestionIsSavedLikeAnyTaskAndKnownByItsAgent() {
        val quick = ChatTask(1_760_000_000_500, QUICK_AGENT, "what day is it?", steps = listOf("Bash: date"), result = "**Tuesday**", status = TaskStatus.Done)
        val build = task(1_760_000_000_000).copy(status = TaskStatus.Done)
        val read = ChatHistory.decode(ChatHistory.encode(listOf(build, quick)))
        assertEquals(listOf(build, quick), read)
        assertEquals(listOf(false, true), read.map { it.quick })
        assertEquals("quick", QUICK_AGENT)
        // No field of its own: a file with quick questions in it has the same shape as one without.
        val fields = JSONObject(ChatHistory.encode(listOf(quick))).getJSONArray("tasks").getJSONObject(0)
        assertEquals(setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals"), fields.keys().asSequence().toSet())
        // A file from before the quick lane has none.
        assertTrue(ChatHistory.decode("""{"version":1,"tasks":[{"time":5,"agent":"Claude Code","prompt":"ok","status":"done"},{"time":6,"prompt":"no agent","status":"done"}]}""").none { it.quick })
    }

    @Test fun aRestartInterruptsAQuickQuestionAndTheTaskBesideItAlike() {
        val before = listOf(
            task(1).copy(status = TaskStatus.Running, approvals = listOf(ChatApproval("Bash", "rm", ApprovalAnswer.Waiting, key = "k"))),
            task(2).copy(status = TaskStatus.Queued),
            ChatTask(3, QUICK_AGENT, "quick one", result = "half an ans", status = TaskStatus.Running),
            ChatTask(4, QUICK_AGENT, "answered", result = "yes", status = TaskStatus.Done),
        )
        val after = ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(before)))
        assertEquals(listOf(TaskStatus.Interrupted, TaskStatus.Dropped, TaskStatus.Interrupted, TaskStatus.Done), after.map { it.status })
        assertEquals(listOf("", FollowUpQueue.RESTARTED, "half an ans", "yes"), after.map { it.result })
        assertEquals(ApprovalAnswer.Unanswered, after[0].approvals.single().answer)
        assertEquals(listOf(false, false, true, true), after.map { it.quick })
        // With the quick question left out, the build task and its follow-up end exactly as they do beside it.
        assertEquals(after.filterNot { it.quick }, ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(before.filterNot { it.quick }))))
    }

    @Test fun aQuickQuestionTakesATimeOfItsOwnBesideARunningTask() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val store = ChatStore(File(dir, "oma-chat.json")).apply { load() }
            store.begin(task(100))
            val asked = ChatHistory.stamp(store.tasks, 100)
            store.begin(ChatTask(asked, QUICK_AGENT, "quick one"))
            assertEquals(101L, asked)
            // Each lane writes only its own entry.
            store.update(asked, save = true) { it.copy(result = "quick answer", status = TaskStatus.Done) }
            store.update(100) { ChatHistory.withStep(it, "Bash: ls") }
            assertEquals(listOf(TaskStatus.Running, TaskStatus.Done), store.tasks.map { it.status })
            assertEquals(listOf("", "quick answer"), store.tasks.map { it.result })
            assertEquals(listOf(listOf("Bash: ls"), emptyList()), store.tasks.map { it.steps })
        } finally { dir.deleteRecursively() }
    }

    private fun undecided(time: Long, prompt: String = "prompt $time") = ChatTask(time, UNDECIDED_AGENT, prompt)

    @Test fun anUndecidedRequestThatIsHandedOverBecomesTheBuildLanesOneEntry() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val file = File(dir, "oma-chat.json")
            val store = ChatStore(file).apply { load() }
            store.begin(task(100).copy(status = TaskStatus.Done))
            store.begin(undecided(200, "fix the overview"))
            assertEquals(listOf(false, true), store.tasks.map { it.undecided })
            assertTrue(store.tasks.none { it.quick }); assertEquals(listOf(true, false), store.tasks.map { it.build })
            // The build lane files the request as its own task, as it does for any; the undecided entry then goes.
            store.begin(task(ChatHistory.stamp(store.tasks, 200), "fix the overview"))
            store.handedOver(200)
            assertEquals(listOf(100L, 201L), store.tasks.map { it.time })
            assertEquals(listOf("Claude Code", "Claude Code"), store.tasks.map { it.agent })
            assertEquals("fix the overview", store.tasks.last().prompt)
            // On disk too: a restart finds one entry for the request, not two.
            assertEquals(listOf(100L, 201L), ChatStore(file).apply { load() }.tasks.map { it.time })
        } finally { dir.deleteRecursively() }
    }

    @Test fun onlyARequestTheFrontAgentStillHasIsTakenOutAsHandedOver() {
        val tasks = listOf(task(1), undecided(2), ChatTask(3, QUICK_AGENT, "looked first"))
        assertEquals(listOf(1L, 3L), ChatHistory.handedOver(tasks, 2).map { it.time })
        assertEquals(listOf(1L, 2L), ChatHistory.handedOver(tasks, 3).map { it.time })
        // Never a task of the build lane's, and never anything else.
        assertEquals(tasks, ChatHistory.handedOver(tasks, 1))
        assertEquals(tasks, ChatHistory.handedOver(tasks, 99))
    }

    @Test fun anUndecidedRequestTheFrontAgentAnswersBecomesItsAnswer() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val store = ChatStore(File(dir, "oma-chat.json")).apply { load() }
            store.begin(undecided(200, "capital of France?"))
            store.update(200, save = true) { it.copy(agent = QUICK_AGENT) }
            store.update(200, save = true) { it.copy(result = "Paris.", status = TaskStatus.Done) }
            val answered = store.tasks.single()
            assertTrue(answered.quick); assertFalse(answered.undecided); assertFalse(answered.build)
            assertEquals(ChatTask(200, QUICK_AGENT, "capital of France?", result = "Paris.", status = TaskStatus.Done), answered)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRestartInterruptsAnUndecidedRequestAndNothingIsLeftToSendIt() {
        assertEquals("oma", UNDECIDED_AGENT)
        val before = listOf(task(1).copy(status = TaskStatus.Running), undecided(2))
        val after = ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(before)))
        assertEquals(listOf(TaskStatus.Interrupted, TaskStatus.Interrupted), after.map { it.status })
        assertTrue(after[1].undecided); assertEquals("prompt 2", after[1].prompt); assertEquals("", after[1].result)
        // It is neither running nor waiting, which are the only two states anything is ever sent from; and it stays so.
        assertTrue(after.none { it.status == TaskStatus.Running || it.status == TaskStatus.Queued })
        assertEquals(after, ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(after))))
        // One that still waited to be read ends the same way: interrupted with his words, not dropped as a follow-up is.
        val waited = ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(listOf(undecided(3).copy(status = TaskStatus.Queued), task(4).copy(status = TaskStatus.Queued)))))
        assertEquals(listOf(TaskStatus.Interrupted, TaskStatus.Dropped), waited.map { it.status })
        assertEquals(listOf("", FollowUpQueue.RESTARTED), waited.map { it.result })
        assertTrue(waited[0].undecided); assertEquals("prompt 3", waited[0].prompt)
        // One he cancelled before it was read stays as it was: not sent, with why.
        val cancelled = ChatHistory.dropped(undecided(5).copy(status = TaskStatus.Queued), "You cancelled this before Oma read it.")
        assertEquals(listOf(cancelled), ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(listOf(cancelled)))))
        assertEquals(TaskStatus.Dropped, cancelled.status)
        // An undecided request has the shape of any task.
        val fields = JSONObject(ChatHistory.encode(listOf(undecided(2)))).getJSONArray("tasks").getJSONObject(0)
        assertEquals(setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals"), fields.keys().asSequence().toSet())
    }

    @Test fun aFrontAnswerIsSentToBuildOnce() {
        val answered = ChatTask(5, QUICK_AGENT, "add a star sticker", result = "You could add one from the menu.", status = TaskStatus.Done)
        val marked = ChatHistory.sentToBuild(answered)
        assertEquals(answered.copy(sentToBuild = true), marked)
        assertNull(ChatHistory.sentToBuild(marked!!))
        // Not while it is still being answered, and never for anything but an answer of the front agent's.
        assertNull(ChatHistory.sentToBuild(answered.copy(status = TaskStatus.Running)))
        assertNull(ChatHistory.sentToBuild(task(1).copy(status = TaskStatus.Done)))
        assertNull(ChatHistory.sentToBuild(undecided(2).copy(status = TaskStatus.Interrupted)))
        for (status in listOf(TaskStatus.Stopped, TaskStatus.Failed, TaskStatus.Interrupted)) assertNotNull(ChatHistory.sentToBuild(answered.copy(status = status)))
        // The mark survives saving, as a key that only a marked entry has; a file from before has none.
        assertEquals(listOf(marked), ChatHistory.decode(ChatHistory.encode(listOf(marked))))
        val fields = JSONObject(ChatHistory.encode(listOf(marked))).getJSONArray("tasks").getJSONObject(0)
        assertEquals(setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals", "sentToBuild"), fields.keys().asSequence().toSet())
        assertFalse(ChatHistory.decode("""{"version":1,"tasks":[{"time":5,"agent":"quick","prompt":"ok","status":"done"}]}""").single().sentToBuild)
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val store = ChatStore(File(dir, "oma-chat.json")).apply { load() }
            store.begin(answered)
            repeat(2) { store.update(5, save = true) { ChatHistory.sentToBuild(it) ?: it } }
            assertEquals(listOf(marked), ChatStore(File(dir, "oma-chat.json")).apply { load() }.tasks)
        } finally { dir.deleteRecursively() }
    }

    @Test fun onlyAWaitingFollowUpCanBeDropped() {
        assertEquals(task(1).copy(status = TaskStatus.Dropped, result = "why"), ChatHistory.dropped(task(1).copy(status = TaskStatus.Queued), "why"))
        for (status in listOf(TaskStatus.Running, TaskStatus.Done, TaskStatus.Stopped, TaskStatus.Failed, TaskStatus.Interrupted)) {
            val sent = task(1).copy(status = status, result = "answer")
            assertEquals(sent, ChatHistory.dropped(sent, "why"))
        }
    }

    @Test fun aNewTaskIsAlwaysFiledAfterTheOnesInTheThread() {
        assertEquals(500L, ChatHistory.stamp(emptyList(), 500))
        assertEquals(500L, ChatHistory.stamp(listOf(task(100), task(200)), 500))
        // Two in the same millisecond, or a clock set back, still get times of their own, in order.
        assertEquals(501L, ChatHistory.stamp(listOf(task(100), task(500)), 500))
        assertEquals(901L, ChatHistory.stamp(listOf(task(900), task(300)), 500))
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


    private fun onMac(time: Long, status: TaskStatus = TaskStatus.Running) = ChatTask(time, "→ FF4 mod", "prompt $time", status = status, thread = "t1a2b3c4d", threadName = "FF4 mod")

    @Test fun aRequestHandedToTheMacRemembersItsThreadInKeysOnlyItHas() {
        val sent = onMac(5).copy(steps = listOf("Read: a.txt"), result = "so far")
        assertEquals(listOf(sent), ChatHistory.decode(ChatHistory.encode(listOf(sent))))
        assertTrue(sent.mac); assertFalse(sent.build); assertFalse(sent.quick); assertFalse(sent.undecided)
        val fields = JSONObject(ChatHistory.encode(listOf(sent, task(6)))).getJSONArray("tasks")
        val always = setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals")
        assertEquals(always + setOf("thread", "threadName"), fields.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(always, fields.getJSONObject(1).keys().asSequence().toSet())
        // A file from before there were threads loads as it always did.
        val old = ChatHistory.decode("""{"version":1,"tasks":[{"time":5,"agent":"Claude Code","prompt":"ok","status":"done"}]}""").single()
        assertFalse(old.mac); assertTrue(old.build); assertEquals("", old.thread); assertEquals("", old.threadName)
        assertEquals(ChatHistory.MAX_THREAD, ChatHistory.bounded(sent.copy(thread = "t".repeat(900))).thread.length)
    }

    @Test fun aRestartLeavesWhatTheMacIsDoingAsItWas() {
        val before = listOf(
            task(1).copy(status = TaskStatus.Running),
            onMac(2).copy(result = "half", approvals = listOf(ChatApproval("Bash", "ls", ApprovalAnswer.Approved, key = "a0"), ChatApproval("Write", "{}", ApprovalAnswer.Waiting, key = "a1"))),
            onMac(3, TaskStatus.Queued),
            onMac(4, TaskStatus.Dropped).copy(result = "Mac unreachable"),
        )
        val after = ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(before)))
        // The phone's own task was interrupted; the Mac goes on with its, and is followed again.
        assertEquals(listOf(TaskStatus.Interrupted, TaskStatus.Running, TaskStatus.Queued, TaskStatus.Dropped), after.map { it.status })
        assertEquals(listOf("", "half", "", "Mac unreachable"), after.map { it.result })
        // A card of before cannot be answered: it goes, to be drawn again from what the Mac says still waits.
        assertEquals(listOf(ChatApproval("Bash", "ls", ApprovalAnswer.Approved)), after[1].approvals)
        assertEquals(after, ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(after))))
        // The front agent's hand-over to the build lane never takes an entry of the Mac's out.
        for (time in 2L..4L) assertEquals(after, ChatHistory.handedOver(after, time))
    }

    @Test fun theThreadIsRevisedAsAWholeAndSaved() {
        val dir = Files.createTempDirectory("oma-chat-test").toFile()
        try {
            val file = File(dir, "oma-chat.json")
            val store = ChatStore(file).apply { load() }
            store.begin(onMac(1)); store.begin(onMac(2, TaskStatus.Queued))
            store.revise { tasks -> tasks.map { if (it.time == 1L) it.copy(result = "Done.", status = TaskStatus.Done) else it.copy(status = TaskStatus.Running) } }
            assertEquals(listOf(TaskStatus.Done, TaskStatus.Running), ChatStore(file).apply { load() }.tasks.map { it.status })
            assertEquals("Done.", store.tasks[0].result)
        } finally { dir.deleteRecursively() }
    }

    @Test fun wordsSentStraightToAThreadAreRememberedAsSuch() {
        val direct = ChatTask(7, "→ Pond", "add frogs", status = TaskStatus.Dropped, result = "Mac unreachable", thread = "t-pond", threadName = "Pond", direct = true)
        val handed = direct.copy(time = 8, direct = false)
        // A restart keeps it: `[ try again ]` afterwards still sends his words alone.
        assertEquals(listOf(direct, handed), ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(listOf(direct, handed)))))
        val fields = JSONObject(ChatHistory.encode(listOf(direct, handed))).getJSONArray("tasks")
        assertTrue(fields.getJSONObject(0).getBoolean("direct"))
        // Written only where it is set, so every other task keeps the shape it has always had.
        assertFalse(fields.getJSONObject(1).has("direct"))
    }

    /** Boss 2026-10-13: two builders. An entry says which builder it went to; one saved before there were two is A's. */
    @Test fun aBuildersEntryRemembersWhichBuilderItWentTo() {
        val toB = ChatTask(7, "Claude Code", "fix the dock", status = TaskStatus.Done, result = "Fixed.", builder = "B")
        val toA = toB.copy(time = 8, builder = "")
        assertEquals(listOf(toB, toA), ChatHistory.afterRestart(ChatHistory.decode(ChatHistory.encode(listOf(toB, toA)))))
        val fields = JSONObject(ChatHistory.encode(listOf(toB, toA))).getJSONArray("tasks")
        assertEquals("B", fields.getJSONObject(0).getString("builder"))
        // Written only where it is set, so every entry of builder A's keeps the shape it has always had.
        assertFalse(fields.getJSONObject(1).has("builder"))
        assertEquals(setOf("time", "agent", "prompt", "images", "screen", "newThread", "steps", "result", "status", "approvals"), fields.getJSONObject(1).keys().asSequence().toSet())
        // A follow-up that waited for B is dropped on a restart as any is, and stays B's.
        val waiting = ChatHistory.afterRestart(listOf(toB.copy(status = TaskStatus.Queued, result = ""))).single()
        assertEquals(TaskStatus.Dropped, waiting.status); assertEquals("B", waiting.builder)
        assertEquals(ChatHistory.MAX_BUILDER, ChatHistory.bounded(toB.copy(builder = "x".repeat(100))).builder.length)
    }
}
