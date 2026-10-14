package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.NEW_CHAT_NAME
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TAB_NAME_CHARS
import dev.omalauncher.oma.TaskStatus
import org.junit.Assert.*
import org.junit.Test

/** New chats name themselves as Claude Code's sessions do (Boss, 2026-10-13): when, from what, and how a title is cleaned. */
class ChatTitlesTest {
    private val now = 1_800_000_000_000L

    @Test fun aChatIsNamedAfterItsFirstExchangeOrFromHisWordsOnceItRunsLong() {
        assertNull(titleDue(emptyList(), now))
        val answered = ChatTask(now - 5_000, QUICK_AGENT, "how do worktrees work", result = "A second checkout.", status = TaskStatus.Done)
        assertEquals(TitleAsk("how do worktrees work", "A second checkout."), titleDue(listOf(answered, answered.copy(time = now)), now))
        val running = ChatTask(now - 5_000, "Claude Code", "make the dock bigger", status = TaskStatus.Running)
        assertNull(titleDue(listOf(running), now))
        assertEquals(TitleAsk("make the dock bigger"), titleDue(listOf(running), now - 5_000 + TITLE_WAIT_MS))
        assertEquals(TitleAsk("make the dock bigger"), titleDue(listOf(running.copy(status = TaskStatus.Queued)), now + TITLE_WAIT_MS))
        // A first request that failed is named from his words: an error is not what the chat is about.
        assertEquals(TitleAsk("make the dock bigger"), titleDue(listOf(running.copy(status = TaskStatus.Failed, result = "boom")), now))
    }

    @Test fun theModelIsAskedOnceWithNoToolsNoSessionAndFromAnEmptyFolder() {
        val ask = TitleAsk("fix the crash in the magic menu", "Fixed.")
        assertEquals("$TITLE_ASK\n\nBoss: fix the crash in the magic menu\n\nAnswer: Fixed.", titlePrompt(ask))
        assertEquals("$TITLE_ASK\n\nBoss: hi", titlePrompt(TitleAsk(" hi ")))
        assertEquals(TITLE_SIDE_CHARS, titlePrompt(TitleAsk("x".repeat(5_000))).removePrefix("$TITLE_ASK\n\nBoss: ").length)
        val command = titleCommand(ask)
        assertTrue(command.startsWith("mkdir -p /tmp/oma-title && cd /tmp/oma-title && claude -p --model haiku --output-format json --tools '' -- "))
        assertFalse("--resume" in command); assertFalse("--allowedTools" in command); assertTrue(command.endsWith(" < /dev/null"))
    }

    @Test fun theTitleIsTheResultOfItsJsonLine() {
        assertEquals("Git worktree commands", titleFrom("some warning\n{\"type\":\"result\",\"is_error\":false,\"result\":\"Git worktree commands\"}\n"))
        assertNull(titleFrom("{\"type\":\"result\",\"is_error\":true,\"result\":\"Not logged in\"}"))
        assertNull(titleFrom("command not found: claude"))
        assertNull(titleFrom("{\"result\":\"  \"}"))
    }

    @Test fun whatTheModelSaysIsCleanedToATabsName() {
        assertEquals("Dock icon size and spacing", titleClean("Dock icon size and spacing"))
        assertEquals("Seattle Weather", titleClean("**\"Seattle Weather.\"**"))
        assertEquals("FF4 magic menu crash fix", titleClean("Title: FF4 magic menu crash fix\n\nThis title..."))
        assertEquals("Calendar widget", titleClean("# “Calendar widget”"))
        assertEquals("Git worktree commands", titleClean("  `Git worktree commands`!  "))
        val long = titleClean("Making the launcher remember every single cursor position")!!
        assertTrue(long.length <= TAB_NAME_CHARS); assertFalse(long.endsWith(" ")); assertTrue("Making the launcher remember every single cursor position".startsWith(long))
        // Talk, not a title; or nothing at all.
        assertNull(titleClean("Sure! Here is a short title that describes what this conversation is about"))
        assertNull(titleClean("\"\""))
        assertNull(titleClean("  \n "))
    }

    @Test fun withoutTheModelItIsTheFirstFourWordsOfHisMessage() {
        assertEquals("make the dock icons", titleFallback("make the dock icons a bit bigger"))
        assertEquals("what's the weather", titleFallback("what's the weather?"))
        assertEquals("fix this", titleFallback("fix **this**"))
        assertTrue(titleFallback("x".repeat(100)).length <= TAB_NAME_CHARS)
        assertEquals(NEW_CHAT_NAME, titleFallback("  "))
    }
}
