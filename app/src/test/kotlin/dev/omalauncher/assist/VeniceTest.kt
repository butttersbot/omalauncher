package dev.omalauncher.assist

import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.oma.chatTabs
import dev.omalauncher.oma.dotTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Venice threads (a chat with Venice's models through the runner), and the desktop's chat with no Soma tab. */
class VeniceTest {
    @Test fun theRunnersVeniceModelsAreOfferedAfterItsDefault() {
        assertEquals(listOf("", "kimi-k2-5", "venice-uncensored"), macModels(MAC_VENICE, emptyList(), listOf("kimi-k2-5", " venice-uncensored ", "", "not a model!")))
        assertEquals(listOf(""), macModels(MAC_VENICE, emptyList()))
        // Claude's are its own whatever is listed.
        assertEquals(MAC_CLAUDE_MODELS, macModels(MAC_CLAUDE, emptyList(), listOf("x-1")))
    }

    @Test fun veniceIsNamedAndInstalledButNeverChosenOnItsOwn() {
        assertEquals("Venice", macKindName("venice"))
        assertEquals("Venice", macReplyName("venice", "kimi-k2-5"))
        assertEquals(listOf(MAC_CLAUDE, MAC_VENICE), macInstalled(listOf("venice", "claude")))
        assertEquals(MAC_CLAUDE, macKind(listOf("claude", "venice"), emptySet()))
        // A computer with Venice alone, or every coding agent at its limit: nothing goes to Venice unasked.
        assertNull(macKind(listOf("claude", "venice"), setOf(MAC_CLAUDE)))
        assertNull(macInstead(MAC_CLAUDE, listOf("claude", "venice"), setOf(MAC_CLAUDE)))
        // Picked, it is used.
        assertEquals(MAC_VENICE, macChatKind("venice", listOf("claude", "venice"), emptySet()))
        // Venice at its limit hands on to a coding agent.
        assertEquals(MAC_CLAUDE, macInstead(MAC_VENICE, listOf("claude", "venice"), setOf(MAC_VENICE)))
    }

    @Test fun switchingToVeniceIsAsked() {
        assertEquals(MacPick(MAC_VENICE), macSwitchAsked("switch to venice"))
        assertEquals(MacPick(MAC_VENICE, "kimi-k2-5"), macSwitchAsked("switch to venice kimi-k2-5"))
        assertEquals(listOf(MacPick(MAC_CLAUDE, ""), MacPick(MAC_VENICE, ""), MacPick(MAC_VENICE, "kimi-k2-5")),
            macSwitchTargets(MacPick(MAC_CLAUDE, "opus"), listOf("claude", "venice"), emptyList(), mapOf(MAC_VENICE to listOf("kimi-k2-5"))).filter { it.model != "claude-fable-5-1" && it.model != "sonnet" && it.model != "haiku" })
    }

    @Test fun healthCarriesTheModelsTheRunnerLists() {
        val health = parseMacHealth(MacReply(200, """{"ok": true, "runner": "oma-runner", "kinds": ["claude", "venice"], "models": {"Venice": ["kimi-k2-5", "", 3]}}"""))
        assertEquals(MacAnswer.Ok(MacHealth(0, 0, 0, listOf("claude", "venice"), mapOf("venice" to listOf("kimi-k2-5")))), health)
        assertEquals(MacAnswer.Ok(MacHealth(0, 0, 0, listOf("claude"))), parseMacHealth(MacReply(200, """{"ok": true, "runner": "oma-runner", "kinds": ["claude"], "models": 7}""")))
    }

    @Test fun withNoSomaTabTheStripHasOnlyThreadsAndTheOpenDotStays() {
        assertEquals(emptyList<String>(), chatTabs(emptyList(), soma = false))
        assertEquals(listOf(OMA_TAB), chatTabs(emptyList()))
        assertEquals("mac:t1", dotTap("mac:t1", "mac:t1", soma = false))
        assertEquals(OMA_TAB, dotTap("mac:t1", "mac:t1"))
    }
}
