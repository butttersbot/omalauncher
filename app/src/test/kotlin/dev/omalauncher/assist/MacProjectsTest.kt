package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.NEW_CHAT_NAME
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.*
import org.junit.Test

/**
 * Where a new chat starts on the Mac (Boss, 2026-10-13: "The plus button should always open up a Mac session";
 * 2026-10-14: "i shouldn't be asked which project").
 */
class MacProjectsTest {
    private fun thread(id: String, name: String, project: String) =
        MacThread(id, name, project, ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList())

    private val ff4 = thread("t1", "FF4 better menu mod", "~/ClaudeSync/projects/ffiv-ds-better-menu-mod")

    @Test fun aNamedThreadPlacesTheChatInItsFolderAndAnythingElseInTheDefault() {
        // <<mac ID>> and <<mac-new ID>> both name the thread whose folder the new thread is made in.
        assertEquals(ff4, chatBeside(FrontRoute.Mac(ff4)))
        assertEquals(ff4, chatBeside(FrontRoute.Mac(ff4, fresh = true)))
        // No mark, the build mark, an id not on the board, held work or a failing front agent: the default folder.
        for (route in listOf(null, FrontRoute.Build, FrontRoute.Unknown("t404"))) assertNull(route.toString(), chatBeside(route))
        // A named thread with no folder has nowhere to put a new thread beside it either.
        assertNull(chatBeside(FrontRoute.Mac(thread("t2", "Loose", "  "))))
        // The default is the runner's first allowed root, which it takes as a project itself.
        assertEquals("~/Projects", MAC_DEFAULT_PROJECT)
        val runner = moduleFile("../tools/mac-runner/oma-runner").readText()
        assertTrue(runner.contains("'Projects'"))
        assertTrue(runner.contains("path == base"))
    }

    @Test fun aNewChatIsNeverAskedWhichProject() {
        // Every case either starts the chat or says plainly why it can't; none is a question.
        assertEquals(ChatPlace.Start, chatPlace(setUp = true, reachable = true))
        assertEquals(ChatPlace.Unreachable, chatPlace(setUp = true, reachable = false))
        assertEquals(ChatPlace.NotSetUp, chatPlace(setUp = false, reachable = false))
        assertEquals(ChatPlace.NotSetUp, chatPlace(setUp = false, reachable = true))
        assertEquals(listOf("Start", "NotSetUp", "Unreachable"), ChatPlace.entries.map { it.name })
        assertEquals("Mac unreachable — this chat is for the Mac.", MAC_CHAT_UNREACHABLE)
        // Nothing in the launcher writes the question any more, nor reads an answer to it.
        val sources = moduleFile("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        for (gone in listOf("Which project on the Mac", "askProject", "ChatProject", "macPickProject")) assertTrue(gone, sources.none { it.contains(gone) })
    }

    @Test fun theDefaultThreadIsNamedByTheChatAndWaitsUnderNoRealThread() {
        assertEquals(NEW_CHAT_NAME, chatThreadName(null))
        assertEquals(NEW_CHAT_NAME, chatThreadName("   "))
        assertEquals("Menu colours", chatThreadName(" Menu colours "))
        assertEquals(FRESH_NAME_CHARS, chatThreadName("x".repeat(200)).length)
        val asked = ChatTask(7, UNDECIDED_AGENT, "make the menu blue", chat = "c1")
        val waiting = macChatStart(asked, "Menu colours")
        assertEquals(asked.copy(agent = "→ Menu colours", thread = MAC_NEW_THREAD, threadName = "Menu colours", status = TaskStatus.Queued, fresh = true), waiting)
        // It is the Mac's entry, so a thread that was never made leaves it with [ try again ] under it.
        assertTrue(waiting.mac)
        assertNotNull(macRetried(macUnsent(waiting, MAC_UNREACHABLE)))
        // Once the thread is made, the entry is that thread's.
        val made = thread("tab12cd34", "Menu colours", MAC_DEFAULT_PROJECT)
        assertEquals("tab12cd34", macMade(waiting, made).thread)
        assertFalse(macMade(waiting, made).fresh)
        // The runner's ids are "t" and hex digits, so none is ever the placeholder.
        assertFalse(Regex("t[0-9a-f]{8}").matches(MAC_NEW_THREAD))
        assertEquals("Oma", OMA_NAME); assertNotEquals(QUICK_AGENT, OMA_NAME)
    }
}
