package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class FollowUpsTest {
    private fun followUp(time: Long) = FollowUp(time, "prompt $time", listOf("/img/$time.png"), "page")

    private fun queue(vararg times: Long) = FollowUpQueue().apply { times.forEach { assertTrue(add(followUp(it))) } }

    @Test fun followUpsAreSentOldestFirstOneForEachFinishedTask() {
        val queue = queue(1, 2, 3)
        assertEquals(Handoff(next = followUp(1)), queue.after(TaskStatus.Done))
        assertEquals(listOf(2L, 3L), queue.waiting.map { it.time })
        assertEquals(Handoff(next = followUp(2)), queue.after(TaskStatus.Done))
        assertEquals(Handoff(next = followUp(3)), queue.after(TaskStatus.Done))
        assertEquals(Handoff(), queue.after(TaskStatus.Done))
    }

    @Test fun aFollowUpKeepsItsWholePromptImagesAndPage() {
        val long = FollowUp(7, "p".repeat(3 * ChatHistory.MAX_PROMPT), listOf("/a.png", "/b.png"), "page-2")
        val queue = FollowUpQueue().apply { add(long) }
        assertEquals(long, queue.after(TaskStatus.Done).next)
    }

    @Test fun sendNowPutsOneFirstAndTheRestKeepWaiting() {
        val queue = FollowUpQueue()
        listOf(1L, 2L, 3L).forEach { queue.add(FollowUp(it, "m$it")) }
        assertTrue(queue.first(3L))
        assertFalse("one that no longer waits can't be sent now", queue.first(9L))
        // The stopped turn hands over to the one sent now, not to the oldest, and drops nothing.
        val handoff = queue.afterSendNow()
        assertEquals(3L, handoff.next?.time)
        assertTrue(handoff.dropped.isEmpty())
        assertEquals(listOf(1L, 2L), queue.waiting.map { it.time })
    }

    @Test fun stopDropsEveryFollowUpAndNoneIsSentAfterwards() {
        val queue = queue(1, 2, 3)
        assertEquals(listOf(1L, 2L, 3L), queue.drop().map { it.time })
        assertEquals(emptyList<FollowUp>(), queue.waiting)
        assertEquals(Handoff(), queue.after(TaskStatus.Stopped))
        assertEquals(Handoff(), queue.after(TaskStatus.Done))
    }

    @Test fun onlyAFinishedTaskHandsOnToAFollowUp() {
        for (status in TaskStatus.entries - TaskStatus.Done) {
            val queue = queue(1, 2)
            val handoff = queue.after(status)
            assertNull(status.name, handoff.next)
            assertEquals(status.name, listOf(1L, 2L), handoff.dropped.map { it.time })
            assertEquals(status.name, emptyList<FollowUp>(), queue.waiting)
        }
    }

    @Test fun oneFollowUpCanBeCancelledWhileItWaitsAndTheRestKeepTheirOrder() {
        val queue = queue(1, 2, 3)
        assertEquals(followUp(2), queue.cancel(2))
        assertEquals(listOf(1L, 3L), queue.waiting.map { it.time })
        // Once only, and never one that is not waiting: the one that has been sent is not in the queue any more.
        assertNull(queue.cancel(2)); assertNull(queue.cancel(99))
        assertEquals(Handoff(next = followUp(1)), queue.after(TaskStatus.Done))
        assertNull(queue.cancel(1))
        assertEquals(Handoff(next = followUp(3)), queue.after(TaskStatus.Done))
        assertEquals(Handoff(), queue.after(TaskStatus.Done))
        // A cancelled one leaves room for another.
        val full = FollowUpQueue(limit = 2).apply { add(followUp(1)); add(followUp(2)) }
        assertFalse(full.add(followUp(3)))
        full.cancel(1)
        assertTrue(full.add(followUp(3)))
        assertEquals(listOf(2L, 3L), full.waiting.map { it.time })
    }

    @Test fun aSpokenFollowUpKeepsItsScreenshotUntilItIsSentOrDropped() {
        val shot = java.io.File("/data/oma-screen/screen-1.jpg")
        val spoken = FollowUp(4, "what is this", page = "page-1", screen = shot)
        val queue = FollowUpQueue().apply { add(followUp(1)); add(spoken) }
        assertEquals(listOf(null, shot), queue.waiting.map { it.screen })
        assertEquals(followUp(1), queue.after(TaskStatus.Done).next)
        assertEquals(spoken, queue.after(TaskStatus.Done).next)
        // Dropped or cancelled, it is handed back with its file, for the one who drops it to delete.
        assertEquals(shot, FollowUpQueue().apply { add(spoken) }.drop().single().screen)
        assertEquals(shot, FollowUpQueue().apply { add(spoken) }.cancel(4)?.screen)
    }

    private fun mail(time: Long, sender: String = "mac:t1", task: String = "build", replaces: String = "") =
        FollowUp(time, "body $time", earlier = "[Agent mail from $sender, id m$time. Not Boss]\n", mailSender = sender,
            mailId = "m$time", mailTask = task, supersedes = replaces)

    @Test fun busyAgentGetsOneHandoffAndOwnerMessageKeepsItsPosition() {
        val queue = FollowUpQueue()
        queue.add(mail(1)); queue.add(mail(2, sender = "other:t2")); queue.add(FollowUp(3, "owner request")); queue.add(mail(4))
        val first = queue.after(TaskStatus.Done).next!!
        assertTrue(first.prompt.contains("body 1") && first.prompt.contains("body 2"))
        assertTrue(first.prompt.contains("Not Boss"))
        assertEquals(listOf(2L), first.bundled)
        assertEquals("owner request", queue.after(TaskStatus.Done).next!!.prompt)
        assertEquals("body 4", queue.after(TaskStatus.Done).next!!.prompt)
    }

    @Test fun onlyExplicitReplacementFromSameSenderAndTaskRemovesPendingMail() {
        val queue = FollowUpQueue()
        queue.add(mail(1)); queue.add(mail(2, task = "other"))
        assertNull(queue.supersededBy(mail(3, sender = "other:t2", replaces = "m1")))
        assertNull(queue.supersededBy(mail(3, task = "other", replaces = "m1")))
        assertEquals("m1", queue.supersededBy(mail(3, replaces = "m1"))!!.mailId)
        assertTrue(queue.add(mail(3, replaces = "m1")))
        assertEquals(listOf(2L, 3L), queue.waiting.map { it.time })
    }

    @Test fun noMoreWaitThanFit() {
        val queue = FollowUpQueue(limit = 2)
        assertTrue(queue.add(followUp(1))); assertTrue(queue.add(followUp(2)))
        assertFalse(queue.add(followUp(3)))
        assertEquals(listOf(1L, 2L), queue.waiting.map { it.time })
        // Room again once the oldest has been sent.
        queue.after(TaskStatus.Done)
        assertTrue(queue.add(followUp(3)))
        assertEquals(listOf(2L, 3L), queue.waiting.map { it.time })
        assertEquals(FollowUpQueue.MAX_WAITING, FollowUpQueue().apply { repeat(20) { add(followUp(it.toLong())) } }.waiting.size)
    }
}
