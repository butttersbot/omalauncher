package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class QueuedMailTest {
    private fun mail(time: Long, id: String, sender: String = "phone:A@galaxy") = FollowUp(time, "text $id", earlier = "", mailSender = sender, mailId = id, mailTask = "t", supersedes = "")
    private fun owner(time: Long) = FollowUp(time, "his own words")

    @Test fun waitingMailIsKeptAcrossARestartAndHisOwnFollowUpsAreNot() {
        val folder = Files.createTempDirectory("queued-mail").toFile()
        try {
            val file = java.io.File(folder, "queued-mail-b.json")
            QueuedMail.save(file, listOf(mail(1, "m1"), owner(2), mail(3, "m3", "mac-pro:t1")))
            val back = QueuedMail.read(file)
            assertEquals(listOf(1L, 3L), back.map { it.time })
            assertEquals(listOf("m1", "m3"), back.map { it.mailId })
            assertEquals("mac-pro:t1", back[1].mailSender)
            assertEquals(setOf(1L, 3L), QueuedMail.times(folder, listOf("queued-mail-a.json", "queued-mail-b.json")))
            // Nothing waiting: the file goes, so a later restart has nothing to put back.
            QueuedMail.save(file, emptyList())
            assertFalse(file.exists()); assertTrue(QueuedMail.read(file).isEmpty())
            // A damaged file is no mail, not a crash.
            file.writeText("not json"); assertTrue(QueuedMail.read(file).isEmpty())
        } finally { folder.deleteRecursively() }
    }
    @Test fun restoredMailIsSentOnceAndANewQueueWithTheSameMailHasNothingMoreToSend() {
        val saved = QueuedMail.decode(QueuedMail.encode(listOf(mail(1, "m1"), mail(2, "m2"))))
        val queue = FollowUpQueue()
        saved.forEach { assertTrue(queue.add(it)) }
        // The builder is free: the mail goes as one handoff, and the queue is empty after it.
        val first = queue.after(TaskStatus.Done)
        assertNotNull(first.next)
        assertTrue(first.next!!.prompt.contains("text m1") && first.next!!.prompt.contains("text m2"))
        val again = queue.after(TaskStatus.Done)
        assertNull("no double delivery", again.next)
        assertTrue(queue.waiting.isEmpty())
        // What was saved after it left is empty: a second restart brings nothing back.
        assertTrue(QueuedMail.decode(QueuedMail.encode(queue.waiting)).isEmpty())
    }
    @Test fun aWaitingMailTaskStaysQueuedAfterARestartOnlyWhenItWasKept() {
        fun task(time: Long, status: TaskStatus = TaskStatus.Queued) = ChatTask(time, "Claude Code", "p$time", status = status, builder = "B", mail = "Phone A")
        val after = ChatHistory.afterRestart(listOf(task(1), task(2)), keepQueued = setOf(1L))
        assertEquals(TaskStatus.Queued, after[0].status)
        assertEquals(TaskStatus.Dropped, after[1].status) // not kept: dropped, as before
        assertEquals(TaskStatus.Dropped, ChatHistory.afterRestart(listOf(task(1))).single().status)
    }
}
