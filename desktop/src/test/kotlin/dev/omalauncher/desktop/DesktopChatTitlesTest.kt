package dev.omalauncher.desktop

import dev.omalauncher.assist.TitleAsk
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopChatTitlesTest {
    /** His own Claude Code setup stays out of a title's ask: no MCP servers, skills, user settings or hooks, no session kept. */
    @Test fun theTitleAskLeavesHisSetupOut() {
        val command = desktopTitleCommand(TitleAsk("Boss's 'quoted' words"))
        for (flag in listOf("--strict-mcp-config", "--disable-slash-commands", "--no-session-persistence", "--setting-sources project", "--tools ''", "--model haiku")) {
            assertTrue("$flag in $command", flag in command)
        }
        assertTrue("his words are quoted for the shell: $command", "Boss'\\''s '\\''quoted'\\'' words" in command)
    }
}
