package dev.omalauncher.oma

import dev.omalauncher.assist.*
import org.junit.Test
import org.junit.Assert.*

class AgentRankingTest {
    @Test fun unansweredRequestsBeatPinnedBuildersAndRecencyEverywhere() {
        val work = AgentThread(PHONE_THREAD, "Newer working", ThreadState.Working, "working", "working", 10000, since = 10000)
        val ask = AgentThread("mac:approval", "Approval first", ThreadState.NeedsOk, "approval", "needs OK", 1, requestedAt = 9000)
        val question = ask.copy(key = "mac:question", name = "Question second", worked = 8000, requestedAt = 8500)
        val idle = work.copy(key = "mac:idle", state = ThreadState.Idle, worked = 20000)
        val input = listOf(idle, work, question, ask)
        val expected = listOf(ask.key, question.key, work.key, idle.key)
        assertEquals(expected, input.sortedWith(agentThreadOrder).map { it.key })
        assertEquals(expected, shownThreads(input, listOf(work.key, idle.key), input).map { it.key })
        assertEquals(expected, allChatRows(input, emptySet(), emptySet(), { "" }, { emptyList() }).map { it.key })
    }
    @Test fun requestTimeComesFromQuestionOrApprovalRatherThanTurnStart() {
        val builder = phoneThread(emptyList(), BuilderNow(true, asks = listOf(ThreadAsk("AskUserQuestion", "", 9000), ThreadAsk("Bash", "git", 7000)), since = 1))
        assertEquals(ThreadState.NeedsOk, builder.state)
        assertEquals(9000L, builder.requestedAt)
    }
}
