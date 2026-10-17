package dev.omalauncher.assist

import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.chose
import dev.omalauncher.oma.onMac
import dev.omalauncher.oma.started
import dev.omalauncher.oma.tabMetaFrom
import dev.omalauncher.oma.tabMetaText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a new chat's thread is made and who runs it, when he picked (cockpit slice 5). */
class MacChatPicksTest {
    private val macs = listOf(MacSeen("mac", "Mac Pro", reachable = true), MacSeen("omarchy-mg1", "MG1", reachable = true), MacSeen("macbook-pro", "MacBook Pro"))

    @Test fun hisPickOfComputerIsKeptWhileItIsSetUp() {
        assertEquals("omarchy-mg1", macChatMachine("omarchy-mg1", macs))
        // One out of reach is kept: his words wait for it rather than going where he did not say.
        assertEquals("macbook-pro", macChatMachine("macbook-pro", macs))
        // One no longer set up gives way to the one it would have been anyway.
        assertEquals("mac", macChatMachine("gone", macs))
        assertEquals("mac", macChatMachine(null, macs))
        assertNull(macChatMachine("mac", emptyList()))
    }

    /** The first Mac's id is the empty string: picking it is a pick, and no pick is null (Critic, round 3). */
    @Test fun theFirstMacIsAPickLikeAnyOther() {
        val first = listOf(MacSeen(MAC_FIRST, "Mac Pro", reachable = false), MacSeen("macbook-pro", "MacBook Pro", reachable = true))
        // Nothing picked and the Mac Pro out of reach: the Mac that answers, as before picks existed.
        assertEquals("macbook-pro", macChatMachine(null, first))
        // The Mac Pro picked, out of reach: kept, so his words wait for it.
        assertEquals(MAC_FIRST, macChatMachine(MAC_FIRST, first))
        // And it survives being kept: a pick of the Mac Pro reads back as that pick, not as none.
        val book = TabMeta().started("c1", 5).chose("c1", MAC_FIRST, "codex")
        assertEquals(MAC_FIRST, tabMetaFrom(tabMetaText(book)).chat("c1")!!.host)
    }

    @Test fun hisPickOfAgentIsAlwaysTheOneThatRuns() {
        val installed = listOf("claude", "codex", "grok")
        assertEquals("grok", macChatKind("grok", installed, emptySet()))
        assertEquals("codex", macChatKind(" Codex ", installed, emptySet()))
        // Not there: still his pick; the runner refuses it and the entry says so, never another agent in its place.
        assertEquals("cursor", macChatKind("cursor", installed, emptySet()))
        // At its limit: still his pick; the turn is handed on with a step saying why, as any thread's is.
        assertEquals("grok", macChatKind("grok", installed, setOf("grok")))
        assertEquals("codex", macChatKind("codex", emptyList(), emptySet()))
        // No pick: the first not at its limit, as for any new thread.
        assertEquals("claude", macChatKind("", installed, emptySet()))
        assertEquals("codex", macChatKind("", installed, setOf("claude")))
    }

    @Test fun whyARunnerWouldNotMakeTheThreadIsPassedOn() {
        assertEquals("The Mac could not make a new thread (HTTP 400). It said: kind must be one of claude, codex, cursor",
            macMadeReason(MacAnswer.Failed(400, "kind must be one of claude, codex, cursor")))
    }

    @Test fun wordsForAComputerOutOfReachSayWhichOneTheyWaitFor() {
        assertEquals("MacBook Pro is out of reach — this chat is for MacBook Pro.", macChatUnreachable("MacBook Pro"))
        assertEquals(MAC_CHAT_UNREACHABLE, macChatUnreachable(""))
    }

    @Test fun thePicksAreKeptOnTheChatUntilItsThreadIsMade() {
        val book = TabMeta().started("c1", 5).chose("c1", "omarchy-mg1", "grok")
        assertEquals("omarchy-mg1" to "grok", book.chat("c1")!!.let { it.host to it.agent })
        assertEquals(book, tabMetaFrom(tabMetaText(book)))
        // A chat that has its thread takes no other pick.
        val bound = book.onMac("c1", "t1").chose("c1", "mac", "claude")
        assertEquals("omarchy-mg1", bound.chat("c1")!!.host)
        // A book kept before picks existed reads as no pick.
        assertEquals(null to "", tabMetaFrom("""{"chats":[{"id":"c2","made":1}]}""").chat("c2")!!.let { it.host to it.agent })
    }
}
