package dev.omalauncher.assist

import dev.omalauncher.oma.NewChat
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.archivedAs
import dev.omalauncher.oma.restored
import dev.omalauncher.oma.tabMetaFrom
import dev.omalauncher.oma.tabMetaText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One archive for a thread, kept on its runner and read by every Soma (A, 2026-10-29): the book and the runner made one. */
class SharedArchiveTest {
    private fun thread(id: String, archived: Long?, shares: Boolean = true, bot: MacBot? = null) =
        MacThread(id, id, "/p", ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList(), bot = bot, archived = archived, sharesArchive = shares)

    private fun look(meta: TabMeta, vararg threads: MacThread): Pair<List<Pair<String, Boolean>>, TabMeta> =
        archiveShare(meta, threads.toList()).let { it.send to (it.change?.invoke(meta) ?: meta) }

    /** What a sent archive leaves: the runner says so, and the book agrees. */
    private fun sent(meta: TabMeta, send: List<Pair<String, Boolean>>) = send.fold(meta) { m, (id, on) -> m.agreedAs(macThreadKey(id), on) }

    @Test fun theFirstLookJoinsTheTwoArchivedOnEitherSideIsArchivedOnBoth() {
        val meta = TabMeta().archivedAs("mac:m/t1", 100L)
        val (send, after) = look(meta, thread("m/t1", null), thread("m/t2", 500L), thread("m/t3", null))
        assertEquals(listOf("m/t1" to true), send)  // archived here only: the runner is told
        assertEquals(500L, after.archived["mac:m/t2"])  // archived there only: archived here too
        assertFalse("mac:m/t3" in after.archived)
        assertEquals(mapOf("mac:m/t2" to true, "mac:m/t3" to false), after.agreed)  // t1 agrees once it is sent
    }

    @Test fun hisArchiveHereGoesToTheRunnerAndAFailedSendGoesAgainAtTheNextLook() {
        var meta = TabMeta(agreed = mapOf("mac:m/t1" to false)).archivedAs("mac:m/t1", 100L)
        assertEquals(listOf("m/t1" to true), look(meta, thread("m/t1", null)).first)
        // Not sent (the Mac asleep): still different, so the next look sends it again.
        assertEquals(listOf("m/t1" to true), look(meta, thread("m/t1", null)).first)
        meta = sent(meta, listOf("m/t1" to true))
        val (again, same) = look(meta, thread("m/t1", 120L))
        assertTrue(again.isEmpty())
        assertEquals(meta, same)
        // His restore here goes too.
        meta = meta.restored("mac:m/t1")
        assertEquals(listOf("m/t1" to false), look(meta, thread("m/t1", 120L)).first)
    }

    @Test fun anArchiveOrRestoreOnAnotherSomaComesHere() {
        val agreed = TabMeta(agreed = mapOf("mac:m/t1" to false))
        val (send, archived) = look(agreed, thread("m/t1", 700L))
        assertTrue(send.isEmpty())
        assertEquals(700L, archived.archived["mac:m/t1"])
        assertEquals(true, archived.agreed["mac:m/t1"])
        val (none, restored) = look(archived, thread("m/t1", null))
        assertTrue(none.isEmpty())
        assertFalse("mac:m/t1" in restored.archived)
        assertEquals(false, restored.agreed["mac:m/t1"])
    }

    @Test fun anOlderRunnerABotAndAThreadOfANewChatAreEachTheirOwn() {
        // An older runner says nothing of archives: the book keeps its own, and nothing is sent.
        val meta = TabMeta().archivedAs("mac:m/t1", 100L)
        val (send, same) = look(meta, thread("m/t1", null, shares = false), thread("m/b1", null, bot = MacBot("b", "Bot")))
        assertTrue(send.isEmpty())
        assertEquals(meta, same)
        // A new chat's tab is the chat's own key: archived there, it is that key that is archived here.
        val chat = TabMeta(chats = listOf(NewChat("c1", 1L, mac = "m/t5")))
        val (_, after) = look(chat, thread("m/t5", 900L))
        assertEquals(900L, after.archived[chat.chats.single().key])
        assertNull(after.archived["mac:m/t5"])
    }

    @Test fun aChatThatGoesOnOnAnotherThreadIsNotRestoredByTheOldAgreement() {
        // Archived everywhere on t1, then its words went on on t2 (a limit): t2 starts afresh, and he keeps it archived.
        val archived = TabMeta(chats = listOf(NewChat("c1", 1L, mac = "m/t1")), agreed = mapOf("mac:m/t1" to true)).archivedAs("chat:c1", 100L)
        val moved = archived.copy(chats = listOf(NewChat("c1", 1L, mac = "m/t2")))
        val (send, after) = look(moved, thread("m/t2", null))
        assertEquals(listOf("m/t2" to true), send)
        assertTrue("chat:c1" in after.archived)
    }

    @Test fun hisArchiveMadeWhileItLooksIsNotUndone() {
        val meta = TabMeta(agreed = mapOf("mac:m/t1" to true)).archivedAs("mac:m/t1", 100L)
        // Another Soma restored it; he archives it here again before the look's change lands.
        val share = archiveShare(meta.restored("mac:m/t1"), listOf(thread("m/t1", null)))
        val after = share.change!!.invoke(meta)
        assertTrue("mac:m/t1" in after.archived)
    }

    @Test fun whatWasAgreedIsKeptWithTheBook() {
        val meta = TabMeta(agreed = mapOf("mac:m/t1" to true, "mac:m/t2" to false))
        assertEquals(meta.agreed, tabMetaFrom(tabMetaText(meta)).agreed)
    }
}
