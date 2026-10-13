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
