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
