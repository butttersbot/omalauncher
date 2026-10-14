package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Who the chat says wrote a reply (Boss, 2026-10-13: "the username can just be the name of the ai"). */
class AgentNamesTest {
    @Test fun aModelIdIsNamedByItsFamily() {
        assertEquals("Opus", agentName("claude-opus-5-5"))
        assertEquals("Haiku", agentName("claude-haiku-5-5"))
        assertEquals("Sonnet", agentName("claude-sonnet-4-5-20250929"))
        assertEquals("Sonnet", agentName("claude-3-5-sonnet-20241022"))
        assertEquals("Opus", agentName("opus[1m]"))
        assertEquals("Fable", agentName("claude-fable-1"))
        // The aliases Claude Code is started with, the front agent's among them.
        assertEquals("Haiku", agentName(QUICK_MODEL))
        assertEquals("Opus", agentName("Opus"))
        // A Claude model of a family not named here is still Claude's.
        assertEquals("Claude", agentName("claude-nova-1"))
    }

    @Test fun otherAgentsHaveTheirOwnNames() {
        assertEquals("Codex", agentName("codex"))
        assertEquals("Codex", agentName("gpt-5-codex"))
        assertEquals("Grok", agentName("grok"))
        assertEquals("Grok", agentName("grok-4-fast"))
        // The kind a thread on the Mac is run by.
        assertEquals("Claude", agentName("claude"))
        // Any other agent the launcher installs is called what the launcher calls it.
        assertEquals("OpenCode", agentName("opencode"))
        assertEquals("Pi", agentName("pi"))
    }

    @Test fun whatSaysNothingLeavesItUnnamed() {
        for (id in listOf(null, "", "  ", "<synthetic>", "gpt-4o", "unknown-model")) assertNull(id, agentName(id))
    }

    @Test fun theStreamSaysWhichModelAnswers() {
        assertEquals("Opus", streamAgentName(JSONObject("""{"type":"system","subtype":"init","session_id":"s","model":"claude-opus-5-5"}""")))
        assertEquals("Haiku", streamAgentName(JSONObject("""{"type":"assistant","message":{"model":"claude-haiku-5-5","content":[]}}""")))
        // Claude Code's own messages, and lines that name no model, say nothing about who answers.
        assertNull(streamAgentName(JSONObject("""{"type":"assistant","message":{"model":"<synthetic>","content":[]}}""")))
        assertNull(streamAgentName(JSONObject("""{"type":"system","subtype":"status","model":"claude-opus-5-5"}""")))
        assertNull(streamAgentName(JSONObject("""{"type":"result","result":"ok"}""")))
    }

    @Test fun aBuildTaskIsNamedByItsStreamOrByItsAgent() {
        assertEquals("", builderName("claude"))
        assertEquals("Codex", builderName("codex"))
    }

    @Test fun theNameStandsOverAReplyOnly() {
        val answered = ChatTask(1, "Claude Code", "fix it", result = "Fixed.", status = TaskStatus.Done, answeredBy = "Opus")
        assertEquals("Opus", replyName(answered, answered.result))
        // A reply still being written has its name already.
        assertEquals("Opus", replyName(answered.copy(status = TaskStatus.Running, result = ""), "Looking"))
        assertEquals("Opus", replyName(answered.copy(status = TaskStatus.Stopped), "half of it"))
        // No reply yet, or words that are an error or the launcher's own: no name.
        assertNull(replyName(answered.copy(status = TaskStatus.Running), " "))
        assertNull(replyName(answered.copy(status = TaskStatus.Failed), "Failed to authenticate"))
        assertNull(replyName(answered.copy(status = TaskStatus.Dropped), "Not sent"))
    }

    @Test fun anEntryFromBeforeNamesIsNamedOnlyWhereThatIsKnown() {
        // The front agent has only ever been Haiku.
        val quick = ChatTask(1, QUICK_AGENT, "what time is it", result = "It's 9.", status = TaskStatus.Done)
        assertEquals("Haiku", replyName(quick, quick.result))
        // Which model the builder or a thread on the Mac was is not known: no guess.
        assertNull(replyName(ChatTask(2, "Claude Code", "fix it", result = "Fixed.", status = TaskStatus.Done), "Fixed."))
        assertNull(replyName(ChatTask(3, "→ FF4 mod", "fix it", result = "Fixed.", status = TaskStatus.Done, thread = "t1a2b3c4d", threadName = "FF4 mod"), "Fixed."))
        assertNull(replyName(ChatTask(4, UNDECIDED_AGENT, "hm", result = "x", status = TaskStatus.Interrupted), "x"))
    }

    /** The runner says which agent and model answered (MAC-RUNNER-SPEC.md, "As built: Codex and Cursor threads"). */
    @Test fun aMacReplyIsNamedByItsAgentThenItsModel() {
        assertEquals("Codex", macReplyName("codex", "gpt-6.1-sol"))
        assertEquals("Cursor", macReplyName("cursor", "cursor-auto"))
        // Cursor running a Claude model is still Cursor's answer.
        assertEquals("Cursor", macReplyName("cursor", "claude-sonnet-4-5"))
        assertEquals("Opus", macReplyName("claude", "claude-opus-5-5"))
        assertEquals("Claude", macReplyName("claude", ""))
        // No kind is the runner's default, Claude.
        assertEquals("Sonnet", macReplyName("", "claude-sonnet-4-5")); assertEquals("Claude", macReplyName("", ""))
        assertEquals("Grok", macReplyName("grok", "")); assertNull(macReplyName("nobody", "x-1"))
    }
}
