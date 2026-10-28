package dev.omalauncher.assist

import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.choseMode
import dev.omalauncher.oma.onMac
import dev.omalauncher.oma.started
import dev.omalauncher.oma.tabMetaFrom
import dev.omalauncher.oma.tabMetaText
import org.junit.Assert.assertEquals
import org.junit.Test

/** A new thread's mode (slice 6): which agents have one, its default, what the row says, and that the pick is kept. */
class MacModesTest {
    @Test fun claudeAndCodexPickAModeTheOthersDont() {
        assertEquals(listOf(MAC_MODE_AUTO, MAC_MODE_MANUAL), macModes("claude"))
        assertEquals(listOf(MAC_MODE_AUTO, MAC_MODE_MANUAL), macModes("codex"))
        for (other in listOf("cursor", "grok", "venice")) assertEquals(other, emptyList<String>(), macModes(other))
        assertEquals(MAC_MODE_MANUAL, macModeFor("codex", MAC_MODE_MANUAL))
        assertEquals("unpicked is the default", MAC_MODE_AUTO, macModeFor("claude", ""))
        assertEquals("a mode no agent takes is the default", MAC_MODE_AUTO, macModeFor("claude", "yolo"))
        assertEquals("none for an agent without modes", "", macModeFor("grok", MAC_MODE_MANUAL))
        assertEquals("auto · acts without asking", macModeName(MAC_MODE_AUTO))
        assertEquals("manual · asks before edits and commands", macModeName(MAC_MODE_MANUAL))
    }

    @Test fun slashModeIsReadAndAnswered() {
        assertEquals(ModeCommand.Show, modeCommand("/mode"))
        assertEquals(ModeCommand.Show, modeCommand("  /MODE  "))
        assertEquals(ModeCommand.Choose(MAC_MODE_MANUAL), modeCommand("/mode Manual"))
        assertEquals(ModeCommand.Choose(MAC_MODE_AUTO), modeCommand("/mode auto"))
        assertEquals("Not changed: type /mode auto or /mode manual.", (modeCommand("/mode yolo") as ModeCommand.Refused).why)
        assertEquals(null, modeCommand("/model sonnet"))
        assertEquals(null, modeCommand("what /mode is it"))
        assertEquals("This chat runs auto · acts without asking. Type /mode manual to change it.", modeShown("claude", "Claude", MAC_MODE_AUTO))
        assertEquals("Grok has no mode here: it always asks.", modeShown("grok", "Grok", ""))
        assertEquals("Manual from the next message: asks before edits and commands.", modeChosen(MAC_MODE_MANUAL, made = true))
        assertEquals("Auto from its first message: acts without asking.", modeChosen(MAC_MODE_AUTO, made = false))
    }

    @Test fun thePickIsKeptWithTheChatUntilItsThreadIsMade() {
        val book = TabMeta().started("c1", 1L).choseMode("c1", MAC_MODE_MANUAL)
        assertEquals(MAC_MODE_MANUAL, book.chat("c1")!!.mode)
        assertEquals(MAC_MODE_MANUAL, tabMetaFrom(tabMetaText(book)).chat("c1")!!.mode)
        assertEquals("only auto or manual", MAC_MODE_MANUAL, book.choseMode("c1", "yolo").chat("c1")!!.mode)
        val bound = book.onMac("c1", "testhost/t1")
        assertEquals("on its thread, nothing changes it", MAC_MODE_MANUAL, bound.choseMode("c1", MAC_MODE_AUTO).chat("c1")!!.mode)
    }
}
