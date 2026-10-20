package dev.omalauncher.assist

import dev.omalauncher.oma.TabAction
import dev.omalauncher.oma.botInfoLine
import dev.omalauncher.oma.cardMarks
import dev.omalauncher.oma.shownThreads
import dev.omalauncher.oma.tabMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bots (soma-bots PRD): a bot's session is a tab always shown, first, named by the bot, with a bot's menu. */
class BotTabsTest {
    private fun thread(id: String, name: String, worked: Long?, bot: MacBot? = null) =
        MacThread(id, name, "~/Soma/bots/x/work", ThreadHome.Mac, "venice", "", MacThreadStatus.Idle, worked, "", 0, 0, emptyList(), bot = bot)

    private fun listed(body: String) = (parseMacThreads(MacReply(200, """{"ok": true, "threads": [$body]}""")) as MacAnswer.Ok).value.single()

    @Test fun aThreadsBotIsReadFromTheRunner() {
        val bot = listed("""{"id": "t1", "name": "Linux setup", "kind": "venice", "status": "idle",
            "bot": {"id": "linux", "name": "Linux setup", "emoji": "🐧", "problem": null, "machine": "omarchy-mg1", "tools": ["shell"], "memory": 41, "summaries": 2}}""").bot
        assertEquals(MacBot("linux", "Linux setup", "🐧", "", "omarchy-mg1", listOf("shell"), 41, 2), bot)
        assertNull(listed("""{"id": "t2", "bot": null}""").bot)
        assertEquals(100, listed("""{"id": "t3", "bot": {"id": "x", "memory": 900}}""").bot!!.memory)
    }

    @Test fun botsComeFirstNamedWithTheirEmojiAndAreAlwaysShown() {
        val now = System.currentTimeMillis() / 1000
        val view = MacView(threads = listOf(
            thread("t1", "work", now),
            thread("t2", "Linux setup", now - 30 * 86_400, MacBot("linux", "Linux setup", "🐧")),
            thread("t3", "Writer", null, MacBot("writer", "Writer")),
        ))
        val all = agentThreads(emptyList(), BuilderNow(), view)
        val macs = all.filter { it.mac }
        assertEquals(listOf("Linux setup", "Writer", "work"), macs.map { it.bot?.name ?: it.name })
        assertEquals("🐧 Linux setup", macs[0].name)
        // Live is only the recent one; the bots are shown all the same, before it.
        val shown = shownThreads(live = macs.filter { it.bot == null }, pinned = emptyList(), all = all)
        assertEquals(listOf("🐧 Linux setup", "Writer", "work"), shown.map { it.name })
    }

    @Test fun aBotsMenuHasNoRenamePinSwitchOrArchive() {
        assertEquals(listOf(TabAction.Instructions, TabAction.Notes, TabAction.Stop, TabAction.Fresh), tabMenu("mac:t2", false, true, false, 3, bot = true))
        assertTrue(TabAction.Archive in tabMenu("mac:t1", false, false, false, 0))
    }

    @Test fun theInfoLineAndTheCardsRedMarks() {
        assertEquals("Venice · kimi-k2-5 · on omarchy-mg1 · memory 41% · 2 summaries", botInfoLine(MacBot("x", "X", machine = "omarchy-mg1", memory = 41, summaries = 2), "kimi-k2-5"))
        assertEquals("Venice · memory 0%", botInfoLine(MacBot("x", "X"), ""))
        assertEquals(listOf("Runs as administrator.") to "Up to 10 min. Installs steam.", cardMarks("Runs as administrator. Up to 10 min. Installs steam."))
        assertEquals(emptyList<String>() to "Up to 5 min.", cardMarks("Up to 5 min."))
    }

    @Test fun anApprovalAnsweredElsewhereSaysWhere() {
        assertEquals("the Fold", deviceName("galaxy-z-fold8"))
        assertEquals("the Mac Pro", deviceName("jacks-mac-pro"))
        assertEquals("omarchy-mg1", deviceName("omarchy-mg1"))
        val running = dev.omalauncher.oma.ChatTask(1000L, "mac", "install steam", status = dev.omalauncher.oma.TaskStatus.Running, thread = "t1")
        val after = macApply(listOf(running), "t1", MacEvent.ApprovalResult(5, 0, "a1", MacDecision.Allow, "galaxy-z-fold8"))
        assertTrue(after.single().steps.contains("allowed on the Fold"))
        val quiet = macApply(listOf(running), "t1", MacEvent.ApprovalResult(5, 0, "a1", MacDecision.Withdrawn, "galaxy-z-fold8"))
        assertTrue(quiet.single().steps.isEmpty())
    }
}
