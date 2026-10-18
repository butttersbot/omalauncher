package dev.omalauncher.desktop

import dev.omalauncher.assist.DESKTOP_CANVAS_ALLOWED
import dev.omalauncher.assist.FrontAgent
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.assist.QUICK_GROK_MODEL
import dev.omalauncher.assist.grokDesktopCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Grok as Soma desktop's front agent (Boss, 2026-10-18), with `soma front` to switch back to Haiku. */
class DesktopGrokFrontTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun front(claude: Boolean, grok: Boolean, signedIn: Boolean, chosen: String?): DesktopFrontAgent {
        val dir = tmp.newFolder()
        if (claude) File(dir, "claude").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        if (grok) File(dir, "grok").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        return DesktopFrontAgent(MacThreadsEngine(platform), platform, notices, places = listOf(File(dir, "claude").path), grokSignedIn = { signedIn }, choice = { chosen })
    }

    @Test fun grokAnswersWhenFoundAndSignedInHereUnlessHaikuWasChosen() {
        assertEquals(FrontAgent.Grok, front(claude = true, grok = true, signedIn = true, chosen = null).agent)
        assertEquals(FrontAgent.Haiku, front(claude = true, grok = true, signedIn = true, chosen = "haiku\n").agent)
        assertEquals(FrontAgent.Haiku, front(claude = true, grok = true, signedIn = false, chosen = null).agent)
        assertEquals(FrontAgent.Haiku, front(claude = true, grok = false, signedIn = true, chosen = "grok").agent)
        assertEquals(FrontAgent.Grok, front(claude = false, grok = true, signedIn = true, chosen = null).agent)
        assertNull(front(claude = false, grok = true, signedIn = false, chosen = null).agent)
        assertFalse(front(claude = false, grok = false, signedIn = false, chosen = null).ready)
        assertTrue(front(claude = false, grok = true, signedIn = true, chosen = null).ready)
    }

    @Test fun noClaudeMeansNoGrokEither() {
        // The tests that say there is no front agent (places = emptyList()) still mean it on a computer with Grok signed in.
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        assertFalse(DesktopFrontAgent(MacThreadsEngine(platform), platform, notices, places = emptyList(), grokSignedIn = { true }).ready)
    }

    @Test fun somaFrontKeepsTheChoiceAndSaysWhoAnswers() {
        val home = tmp.newFolder()
        val said = mutableListOf<String>()
        assertEquals(0, DesktopFrontAgent.choose(emptyList(), home, grok = false, signedIn = false) { said += it })
        assertTrue(said.last().contains("not installed"))
        assertEquals(0, DesktopFrontAgent.choose(listOf("haiku"), home, grok = true, signedIn = true) { said += it })
        assertEquals("haiku\n", File(home, DesktopFrontAgent.FRONT_CHOICE_FILE).readText())
        assertTrue(said.last().contains("The front agent is Haiku"))
        DesktopFrontAgent.choose(listOf("Grok"), home, grok = true, signedIn = false) { said += it }
        assertEquals("grok\n", File(home, DesktopFrontAgent.FRONT_CHOICE_FILE).readText())
        assertTrue(said.last().contains("grok login"))
        DesktopFrontAgent.choose(emptyList(), home, grok = true, signedIn = true) { said += it }
        assertTrue(said.last().contains("The front agent is Grok"))
        assertEquals(2, DesktopFrontAgent.choose(listOf("opus"), home, grok = true, signedIn = true) { said += it })
        assertEquals("grok\n", File(home, DesktopFrontAgent.FRONT_CHOICE_FILE).readText())
    }

    @Test fun grokOnAComputerHasTheWebAndSomaCanvasAlone() {
        val plain = grokDesktopCommand("q")
        assertTrue(plain.startsWith("grok --output-format streaming-messages-json --include-partial-messages --model $QUICK_GROK_MODEL "))
        assertTrue(plain.contains(" --tools web_search,web_fetch --allow 'WebFetch' --deny "))
        assertTrue(plain.contains(" --permission-mode dontAsk "))
        val canvas = grokDesktopCommand("q", canvas = true)
        assertTrue(canvas.contains(" --tools web_search,web_fetch,run_terminal_cmd "))
        assertEquals(listOf("WebFetch") + DESKTOP_CANVAS_ALLOWED, Regex("--allow '([^']*)'").findAll(canvas).map { it.groupValues[1] }.toList())
        assertTrue(canvas.contains("soma canvas layout"))
        for (denied in listOf("MCPTool", "Edit", "Write", "Bash(mkdir*)", "Bash(touch*)")) assertTrue(denied, plain.contains("--deny '$denied'") && canvas.contains("--deny '$denied'"))
        assertTrue(canvas.endsWith(" < /dev/null"))
    }
}
