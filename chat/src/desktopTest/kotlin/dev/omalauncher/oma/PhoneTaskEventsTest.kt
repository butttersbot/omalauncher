package dev.omalauncher.oma

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneTaskEventsTest {
    @Test fun cursorsStayStableAcrossQueuedTasksAndRestart() {
        val journal = PhoneTaskEvents()
        val first = ChatTask(1, "claude", "first")
        val queued = ChatTask(2, "claude", "queued", status = TaskStatus.Queued)
        journal.changed(emptyList(), listOf(first))
        val prefix = journal.events("builder-a", 0).map { it.toString() }
        journal.changed(listOf(first), listOf(first, queued))
        val complete = first.copy(steps = listOf("read source"), result = "done", status = TaskStatus.Done)
        journal.changed(listOf(first, queued), listOf(complete, queued))
        val all = journal.events("builder-a", 0)
        assertEquals(prefix, all.take(prefix.size).map { it.toString() })
        assertEquals((1L..all.size.toLong()).toList(), all.map { it.getLong("id") })
        val restored = PhoneTaskEvents(journal.encode())
        assertEquals(all.map { it.toString() }, restored.events("builder-a", 0).map { it.toString() })
        assertEquals(0, restored.events("builder-a", all.last().getLong("id")).size)
        restored.append("builder-a", JSONObject().put("type", "note").put("text", "after restart"))
        assertEquals(all.size.toLong() + 1, restored.last("builder-a"))
    }
    @Test fun producerEventsSurviveFortyStepAndEightThousandCharacterLimits() {
        val file = kotlin.io.path.createTempFile("phone-producer-log", ".json").toFile()
        try {
            val store = ChatStore(file)
            store.begin(ChatTask(1, "claude", "work"))
            repeat(45) { store.runnerStep(1, "step $it", "Bash") }
            repeat(100) { store.runnerText(1, "x".repeat(128), "claude", "model") }
            val events = store.runnerEvents("builder-a", 0)
            assertEquals(45, events.count { it.optString("type") == "step" })
            assertEquals(12_800, events.filter { it.optString("type") == "text" }.sumOf { it.getString("text").length })
            assertEquals(40, store.tasks.single().steps.size)
            assertEquals(8_000, store.tasks.single().result.length)
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.getLong("id") })
        } finally { file.delete() }
    }
    @Test fun twentyFiveApprovalResultsDoNotDependOnTrimmedUiIndices() {
        val file = kotlin.io.path.createTempFile("phone-approval-log", ".json").toFile()
        try {
            val store = ChatStore(file)
            store.begin(ChatTask(1, "claude", "work"))
            repeat(25) { n ->
                val key = "nonce-$n"
                store.update(1) { it.copy(approvals = it.approvals + ChatApproval("Bash", "pwd", ApprovalAnswer.Waiting, key)) }
                store.runnerRecord(1, "builder-a", JSONObject().put("type", "approval_result").put("approval", key).put("decision", "allow")) { task ->
                    task.copy(approvals = task.approvals.map { if (it.key == key) it.copy(answer = ApprovalAnswer.Approved) else it })
                }
            }
            val outcomes = store.runnerEvents("builder-a", 0).filter { it.optString("type") == "approval_result" }
            assertEquals((0 until 25).map { "nonce-$it" }, outcomes.map { it.getString("approval") })
            assertEquals(20, store.tasks.single().approvals.size)
        } finally { file.delete() }
    }
    @Test fun shiftedResultAndStepTailsDoNotLoseNewEvents() {
        assertEquals(" new", phoneTextDelta("older text", "text new"))
        val journal = PhoneTaskEvents()
        val old = ChatTask(1, "claude", "work", steps = listOf("a", "b", "c"))
        journal.changed(emptyList(), listOf(old))
        val cursor = journal.last("builder-a")
        journal.changed(listOf(old), listOf(old.copy(steps = listOf("b", "c", "d"))))
        assertEquals(listOf("d"), journal.events("builder-a", cursor).filter { it.optString("type") == "step" }.map { it.getString("text") })
    }
    @Test fun journalAndApprovalOutcomeSurviveTheTaskStoresOwnSave() {
        val file = kotlin.io.path.createTempFile("phone-task-log", ".json").toFile()
        try {
            val store = ChatStore(file)
            store.begin(ChatTask(10, "claude", "hello"))
            store.update(10) { it.copy(result = "partial") }
            val all = store.runnerEvents("builder-a", 0) // must flush unsaved partial events before exposing cursors
            val restored = ChatStore(file).apply { load() }
            assertEquals(all.map { it.toString() }, restored.runnerEvents("builder-a", 0).take(all.size).map { it.toString() })
            assertTrue(JSONObject(file.readText()).has("phoneRunner"))
            assertEquals("partial", restored.tasks.single().result)
        } finally { file.delete() }
    }
    @Test fun onlyLocalPhoneSessionsAreExposedAndTextIsNotRepeated() {
        val journal = PhoneTaskEvents()
        val task = ChatTask(1, "codex", "work", builder = "B")
        assertNull(phoneTaskSession(task.copy(thread = "t-mac")))
        assertNull(phoneTaskSession(task.copy(agent = QUICK_AGENT)))
        assertEquals("phone-d", phoneTaskSession(task.copy(builder = "D")))
        assertEquals("chat-one", phoneTaskSession(task.copy(chat = "one")))
        journal.changed(emptyList(), listOf(task))
        val partial = task.copy(result = "hello")
        journal.changed(listOf(task), listOf(partial))
        journal.changed(listOf(partial), listOf(partial.copy(result = "hello world")))
        assertEquals(listOf("hello", " world"), journal.events("builder-b", 0).filter { it.optString("type") == "text" }.map { it.getString("text") })
    }
}
