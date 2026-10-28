package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import dev.omalauncher.assist.MacMachine
import dev.omalauncher.assist.MacRunnerConfig
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.assist.MacRunnerClient
import dev.omalauncher.assist.reachableFor
import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.oma.OmaChatScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Soma desktop end to end, with no window and no Keychain: a real oma-runner on the loopback (the repo's own, with
 * its fake agent), and the desktop's real chat host and the shared threads engine talking to it over HTTP. He starts a
 * new chat, types, sends; the thread is made on that runner and its answer comes back into the chat.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopEndToEndTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/mac-runner/oma-runner").isFile }
    private val work = Files.createTempDirectory("soma-desktop-e2e").toFile()
    private val key = "k".repeat(40)
    private var runner: Process? = null
    private var port = 0

    @Before fun startRunner() {
        port = ServerSocket(0).use { it.localPort }
        launchRunner()
        System.setProperty("soma.home", File(work, "soma").path)
    }

    /** Starts the runner on [port] with the test's state, as a restart of the computer's runner would find it. */
    private fun launchRunner() {
        // New chats go to ~/Projects on their runner (MAC_DEFAULT_PROJECT): this runner's home is the test's own.
        val root = File(work, "Projects").apply { mkdirs() }
        File(work, "token").writeText(key)
        File(work, "whois.json").writeText("""{"127.0.0.1": "testhost"}""")
        val mac = File(repo, "tools/mac-runner")
        val python = listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3").first { File(it).canExecute() }
        runner = ProcessBuilder(python, File(mac, "oma-runner").path, "serve").apply {
            environment().putAll(mapOf(
                "OMA_RUNNER_PORT" to port.toString(), "OMA_RUNNER_TOKEN" to File(work, "token").path, "OMA_RUNNER_KEYS" to File(work, "keys.json").path,
                "OMA_RUNNER_STATE" to File(work, "state").path, "OMA_RUNNER_LOGS" to File(work, "logs").path, "OMA_RUNNER_ROOTS" to root.path,
                "OMA_RUNNER_AGENT" to File(mac, "fake-agent").path, "OMA_RUNNER_BIND" to "127.0.0.1", "OMA_RUNNER_PHONE" to "testhost",
                "OMA_RUNNER_WHOIS_FILE" to File(work, "whois.json").path, "OMA_RUNNER_MAIL" to File(work, "mail").path,
                "HOME" to work.path, "OMA_RUNNER_CODEX" to File(mac, "fake-codex").path, "OMA_RUNNER_CURSOR" to File(mac, "fake-cursor").path, "CODEX_HOME" to File(work, "codex").path,
                // No Grok on this runner, whatever this computer has installed: a switch to it can't make its thread.
                "OMA_RUNNER_GROK" to File(work, "no-grok").path,
                // Bots: this runner hosts the folders in the test's own ~/Soma/bots, as the machine "testhost".
                "OMA_RUNNER_BOTS" to File(work, "Soma/bots").path, "OMA_RUNNER_SELF" to "testhost",
            ))
            redirectErrorStream(true)
        }.start()
        runner!!.inputStream.bufferedReader().readLine()
    }

    @After fun stopRunner() {
        runner?.destroy()
        work.deleteRecursively()
    }

    @Test fun aNewChatOnTheDesktopMakesItsThreadOnTheRunnerAndShowsItsAnswer() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, platform, notices, places = emptyList())
        val host = DesktopChatHost(platform, engine, front, notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNodeWithText("[ Test box ]").assertExists()
        onNode(hasText("start this chat on Test box…", substring = true)).assertExists()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle()
        onNodeWithText("[ send ]").performClick()
        // The runner makes the thread and its fake agent answers; the engine follows it on threads of its own.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && platform.chatLog().tasks.none { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done || it.status == dev.omalauncher.oma.TaskStatus.Dropped || it.status == dev.omalauncher.oma.TaskStatus.Failed }) {
            Thread.sleep(250); settle(500)
        }
        val entry = platform.chatLog().tasks.last()
        assertTrue("the entry went to a thread on the runner: $entry", entry.mac && entry.thread.startsWith("testhost/"))
        // Done, with the fake agent's own answer: a "needs attention" result can't pass for one.
        assertEquals("its answer came back: ${entry.result}", dev.omalauncher.oma.TaskStatus.Done, entry.status)
        assertEquals("done", entry.result.trim())
        settle()
        onNode(hasText(entry.result.trim().take(20), substring = true)).assertExists()
        println("E2E entry: thread=${entry.thread} status=${entry.status} result=${entry.result.trim().take(120)}")
        val shot = onRoot().captureToImage()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(shot.toAwtImage(), "png", File("build/render/desktop-e2e.png"))
    }

    /**
     * A bot end to end (soma-bots PRD): a folder in the runner's ~/Soma/bots is a tab on the desktop, first and named with
     * its emoji, saying what it is under its name; its instructions read from the runner; Start fresh reaches it.
     */
    @Test fun aBotFolderOnTheRunnerIsATabOnTheDesktop() = runComposeUiTest {
        val folder = File(work, "Soma/bots/linux").apply { mkdirs() }
        File(folder, "bot.json").writeText("""{"name": "Linux setup", "emoji": "🐧", "brain": "venice", "model": "venice-uncensored", "machine": "testhost", "tools": ["shell"]}""")
        File(folder, "instructions.md").writeText("You are my Linux setup agent.")
        File(folder, "notes.md").writeText("- yay is the AUR helper")
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        engine.open()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && host.threads().none { it.bot != null }) { Thread.sleep(250); engine.refresh() }
        val bot = host.threads().first { it.bot != null }
        assertEquals("🐧 Linux setup", bot.name)
        val id = dev.omalauncher.assist.macThreadId(bot.key)!!
        var wanted: String? by androidx.compose.runtime.mutableStateOf(bot.key)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        settle()
        onAllNodes(hasText("🐧 Linux setup", substring = true)).onFirst().assertExists()
        onNode(hasText("on testhost · memory 0%", substring = true)).assertExists()
        var files: dev.omalauncher.assist.MacBotFiles? = null
        var asked = false
        host.botFiles(id) { files = it; asked = true }
        val readBy = System.currentTimeMillis() + 10_000
        while (!asked && System.currentTimeMillis() < readBy) { Thread.sleep(100); settle(100) }
        assertEquals(dev.omalauncher.assist.MacBotFiles("You are my Linux setup agent.", "- yay is the AUR helper"), files)
        var said: String? = null
        host.freshBot(id) { said = it }
        val freshBy = System.currentTimeMillis() + 10_000
        while (said == null && System.currentTimeMillis() < freshBy) { Thread.sleep(100); settle(100) }
        assertEquals("Started fresh. Its instructions and notes are kept.", said)
    }

    /**
     * Every Soma sees every session (Boss, 2026-10-19): two devices, each with its own Soma home (its own chat log and
     * tabs) and its own engine, follow one runner. A chat started on one is a thread the other lists and shows a tab
     * for, and the other way round. What does not cross: the chat's own entries (each device's log is its own).
     */
    @Test fun aSessionStartedOnOneDeviceIsSeenOnTheOther() {
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        fun device(name: String): Triple<DesktopThreadsPlatform, MacThreadsEngine, DesktopChatHost> {
            System.setProperty("soma.home", File(work, name).path)
            val notices = Notices()
            val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
            val engine = MacThreadsEngine(platform)
            return Triple(platform, engine, DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices))
        }
        fun until(what: String, check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && !check()) Thread.sleep(250)
            assertTrue(what, check())
        }
        val (deskA, engineA, hostA) = device("desk-a")
        engineA.open()
        until("desk A reaches the runner") { engineA.kindsOn("testhost").isNotEmpty() }
        engineA.startChatDirect("what files are here", "")
        until("desk A's chat is answered") { deskA.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val made = deskA.chatLog().tasks.last { it.mac }.thread

        val (deskB, engineB, hostB) = device("desk-b")
        engineB.open()
        until("desk B lists desk A's thread") { engineB.refresh(); engineB.view.threads.any { it.id == made } }
        assertTrue("desk B has a tab for it", hostB.threads().any { it.key == dev.omalauncher.assist.macThreadKey(made) })
        assertTrue("desk B's own log has none of desk A's entries", deskB.chatLog().tasks.none { it.thread == made })

        engineB.startChatDirect("what files are here", "")
        until("desk B's chat is answered") { deskB.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val madeB = deskB.chatLog().tasks.last { it.mac }.thread
        until("desk A lists desk B's thread") { engineA.refresh(); engineA.view.threads.any { it.id == madeB } }
        assertTrue("desk A has a tab for it", hostA.threads().any { it.key == dev.omalauncher.assist.macThreadKey(madeB) })
    }

    /**
     * Switch agent, end to end (cockpit/DESIGN.md §4): a chat's Claude thread is moved to Codex from its tab's menu. The
     * Claude thread is asked for a handoff (the fake agent's "done" is too short to be one, so the record is used), a
     * Codex thread is made on the same runner and project with it, answers, and the chat lives on the Codex thread.
     */
    @Test fun switchAgentMovesAChatsThreadToCodexOnTheSameRunner() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, platform, notices, places = emptyList())
        val host = DesktopChatHost(platform, engine, front, notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.time} ${it.thread} ${it.status} ${it.steps} ${it.result.take(60)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle()
        onNodeWithText("[ send ]").performClick()
        waitFor("the Claude thread answered") { platform.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val claude = platform.chatLog().tasks.first { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done }.thread
        val chat = platform.book().chats.single()
        assertEquals(claude, chat.mac)
        waitFor("the runner said it runs Codex") { "codex" in engine.kindsOn("testhost") && engine.view.threads.any { it.id == claude } }
        settle()
        // The fake Claude holds its handoff turn while "hold" is in the project, so the first switch is still on its way
        // when the second is asked: on its own the whole switch can be over in a few milliseconds.
        val hold = File(work, "Projects/hold")
        assertTrue("the Claude thread runs in ~/Projects", File(work, "Projects/starts.log").isFile)
        hold.writeText("")
        // His long press on the chat's tab, then Switch agent, then Codex.
        onAllNodes(hasText("New chat") and hasClickAction()).onFirst().performTouchInput { longClick() }
        settle()
        onNodeWithText("Switch agent").performClick(); settle()
        onNodeWithText("Codex").performClick(); settle()
        // A second switch while the first is on its way is refused, so one switch never makes two threads.
        engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"))
        val second = platform.chatLog().tasks.last()
        assertEquals(dev.omalauncher.oma.TaskStatus.Failed, second.status)
        assertTrue(second.result, second.result.startsWith("This thread is already moving to Codex"))
        hold.delete()
        // Taken by the Codex thread: the fake Codex then asks to write a file (it does whenever its words say "write"), and waits on him.
        waitFor("a Codex thread was made and took the handoff") {
            platform.chatLog().tasks.any { task -> task.status != dev.omalauncher.oma.TaskStatus.Queued && !task.fresh && engine.view.threads.any { it.id == task.thread && it.kind == "codex" } }
        }
        settle(2_000)
        assertEquals("exactly one Codex thread", 1, engine.view.threads.count { it.kind == "codex" })
        val codex = engine.view.threads.first { it.kind == "codex" }
        val seed = platform.chatLog().tasks.first { it.thread == codex.id }
        assertTrue("same runner and project", codex.id.startsWith("testhost/") && codex.project == engine.view.threads.first { it.id == claude }.project)
        assertTrue("the seed says whom it takes over from: ${seed.prompt.take(200)}", seed.prompt.startsWith("You're taking over this thread from Claude"))
        assertTrue("the record went with it", seed.prompt.contains("what files are here"))
        // The chat lives on the Codex thread now; the Claude thread's entry says where it went; Codex is the default for ~/Projects.
        assertEquals(codex.id, platform.book().chat(chat.id)!!.mac)
        val asked = platform.chatLog().tasks.single { it.thread == claude && it.steps.any { step -> step.startsWith("moved to ") } }
        assertEquals("his switch is in the chat where he asked", chat.id, asked.chat)
        assertEquals("Switch to Codex", asked.prompt)
        // Kept under the thread's project (this runner's home is a temp folder, so it doesn't fold to ~/Projects as a real one does).
        assertEquals("codex", dev.omalauncher.assist.macDefaultsFrom(platform.prefString(dev.omalauncher.assist.MAC_DEFAULTS_PREF))[dev.omalauncher.assist.macProjectKey(codex.project)]?.kind)
        // A switch whose new thread can't be made (this runner has no Grok) says so, moves nothing and changes no default.
        engine.switchTo(codex.id, dev.omalauncher.assist.MacPick("grok"))
        val toGrok = platform.chatLog().tasks.last { it.prompt == "Switch to Grok" }
        waitFor("the Grok switch said why it couldn't move") { platform.chatLog().tasks.first { it.time == toGrok.time }.steps.any { it.startsWith("couldn't move: ") } }
        assertTrue("never said it moved", platform.chatLog().tasks.first { it.time == toGrok.time }.steps.none { it.startsWith("moved to ") })
        assertEquals(codex.id, platform.book().chat(chat.id)!!.mac)
        assertEquals("codex", dev.omalauncher.assist.macDefaultsFrom(platform.prefString(dev.omalauncher.assist.MAC_DEFAULTS_PREF))[dev.omalauncher.assist.macProjectKey(codex.project)]?.kind)
        settle()
        println("E2E switch: $claude -> ${codex.id} (${codex.kind}), seed ${seed.status}: ${seed.result.trim().take(80)}")
        val shot = onRoot().captureToImage()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(shot.toAwtImage(), "png", File("build/render/desktop-e2e-switch.png"))
    }

    /**
     * A switch is on its way until its new thread is being made, even across the moment its handoff turn has ended and
     * the new thread's first words are not yet filed. A second switch of the same thread asked right then, here from
     * inside the engine's own save of its switches as the first one leaves them, is refused, so it never makes two.
     * This reaches finishSwitch's part of the gap only: the moment between the handoff's entry turning Done and
     * finishSwitch, which the engine's handingOff holds, can't be reached from here (the chat store is final).
     */
    @Test fun aSecondSwitchAskedAsTheFirstsHandoffEndsIsRefused() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val inner = DesktopThreadsPlatform(notices) { listOf(machine) }
        var asked: String? = null
        var second: dev.omalauncher.oma.ChatTask? = null
        lateinit var engine: MacThreadsEngine
        val platform = object : dev.omalauncher.assist.ThreadsPlatform by inner {
            override fun putPref(key: String, value: String) {
                inner.putPref(key, value)
                // The first switch has just left the switches under way; its handoff has ended and its new thread isn't asked for yet.
                val claude = asked ?: return
                if (key != dev.omalauncher.assist.MAC_SWITCHES_PREF || second != null || dev.omalauncher.assist.macSwitchesFrom(value).isNotEmpty()) return
                if (inner.chatLog().tasks.none { it.thread == claude && it.prompt == "Switch to Codex" && dev.omalauncher.assist.macHandoffEnded(it) }) return
                second = dev.omalauncher.oma.ChatTask(0, "", "")
                engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"), words = "Switch to Codex again")
                second = inner.chatLog().tasks.last { it.prompt == "Switch to Codex again" }
            }
        }
        engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, inner, notices, places = emptyList())
        val host = DesktopChatHost(inner, engine, front, notices)
        engine.open()
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = null, onTab = {}) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${inner.chatLog().tasks.map { "${it.time} ${it.thread} ${it.status} ${it.steps} ${it.result.take(60)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle()
        onNodeWithText("[ send ]").performClick()
        waitFor("the Claude thread answered") { inner.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val claude = inner.chatLog().tasks.first { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done }.thread
        waitFor("the runner said it runs Codex") { "codex" in engine.kindsOn("testhost") && engine.view.threads.any { it.id == claude } }
        asked = claude
        engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"))
        waitFor("a Codex thread was made") { engine.view.threads.any { it.kind == "codex" } }
        val refused = second
        assertTrue("the second switch was asked in the gap", refused != null && refused.prompt == "Switch to Codex again")
        assertEquals(dev.omalauncher.oma.TaskStatus.Failed, refused!!.status)
        assertTrue(refused.result, refused.result.startsWith("This thread is already moving to Codex"))
        settle(2_000)
        assertEquals("exactly one Codex thread", 1, engine.view.threads.count { it.kind == "codex" })
    }

    /**
     * A switch whose handoff turn the runner drops (here it restarts under the turn, and comes back with the thread
     * interrupted, so the engine ends the entry as failed when it finds the thread resting) still moves the thread: the
     * new thread is made from the record, as when the handoff ends any other way. It used to stall, never moving and
     * never saying so.
     */
    @Test fun aSwitchWhoseHandoffTheRunnerDropsStillMovesTheThread() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, platform, notices, places = emptyList())
        val host = DesktopChatHost(platform, engine, front, notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.time} ${it.thread} ${it.status} ${it.steps} ${it.result.take(60)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle()
        onNodeWithText("[ send ]").performClick()
        waitFor("the Claude thread answered") { platform.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val claude = platform.chatLog().tasks.first { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done }.thread
        val chat = platform.book().chats.single()
        assertEquals(claude, chat.mac)
        waitFor("the runner said it runs Codex") { "codex" in engine.kindsOn("testhost") && engine.view.threads.any { it.id == claude } }
        val hold = File(work, "Projects/hold")
        hold.writeText("")
        engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"))
        val switch = platform.chatLog().tasks.last { it.prompt == "Switch to Codex" }
        waitFor("the handoff turn is running") { platform.chatLog().tasks.first { it.time == switch.time }.status == dev.omalauncher.oma.TaskStatus.Running }
        // The runner goes away under the held turn and comes back: the thread is interrupted, its turn never ends with an event.
        runner!!.destroy()
        runner!!.waitFor()
        hold.delete()
        launchRunner()
        waitFor("the dropped handoff ended") { dev.omalauncher.assist.macHandoffEnded(platform.chatLog().tasks.first { it.time == switch.time }) }
        waitFor("the thread moved to Codex all the same") {
            platform.chatLog().tasks.first { it.time == switch.time }.steps.any { it.startsWith("moved to ") } && engine.view.threads.any { it.kind == "codex" }
        }
        settle(2_000)
        assertEquals("exactly one Codex thread", 1, engine.view.threads.count { it.kind == "codex" })
        val codex = engine.view.threads.first { it.kind == "codex" }
        assertEquals("the chat lives on the Codex thread", codex.id, platform.book().chat(chat.id)!!.mac)
        val seed = platform.chatLog().tasks.first { it.thread == codex.id }
        assertTrue("the record went with it: ${seed.prompt.take(200)}", seed.prompt.startsWith("You're taking over this thread from Claude") && seed.prompt.contains("what files are here"))
    }

    /**
     * A switch whose handoff the runner never got is left with `[ try again ]`. Once another switch of the thread starts,
     * that one is called off and can't be tried again, so the two never make two threads.
     */
    @Test fun aNeverTakenSwitchIsCalledOffByALaterOneSoNoSecondThreadIsMade() = neverTakenSwitch(runnerSeenGone = false)

    /** The same with the runner already found gone: the switch makes its new thread at once, which isn't made. */
    @Test fun aNeverMadeSwitchThreadIsCalledOffByALaterOneSoNoSecondThreadIsMade() = neverTakenSwitch(runnerSeenGone = true)

    private fun neverTakenSwitch(runnerSeenGone: Boolean) = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, platform, notices, places = emptyList())
        val host = DesktopChatHost(platform, engine, front, notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.time} ${it.thread} ${it.status} ${it.steps} ${it.result.take(60)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle()
        onNodeWithText("[ send ]").performClick()
        waitFor("the Claude thread answered") { platform.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val claude = platform.chatLog().tasks.first { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done }.thread
        waitFor("the runner said it runs Codex") { "codex" in engine.kindsOn("testhost") && engine.view.threads.any { it.id == claude } }
        // The runner goes away just before the switch. While the thread still counts as heard from, its handoff is asked for
        // and isn't sent; once its follower has found the runner gone, the new thread is made at once and isn't. Either way
        // one entry of the switch is left with [ try again ]: the handoff's, or the new thread's first words.
        runner!!.destroy()
        runner!!.waitFor()
        if (runnerSeenGone) waitFor("the runner was found gone") { !engine.view.reachableFor(claude) }
        engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"))
        val asked = platform.chatLog().tasks.last { it.prompt == "Switch to Codex" }
        fun unsent() = platform.chatLog().tasks.firstOrNull { it.thread == claude && it.time >= asked.time && it.status == dev.omalauncher.oma.TaskStatus.Dropped }
        waitFor("the first switch never got there") { unsent() != null }
        val first = unsent()!!
        if (runnerSeenGone) assertTrue("the new thread's first words were left unsent", first.fresh)
        launchRunner()
        engine.switchTo(claude, dev.omalauncher.assist.MacPick("codex"), words = "Switch to Codex again")
        val second = platform.chatLog().tasks.last { it.prompt == "Switch to Codex again" }
        waitFor("the second switch moved the thread") { engine.view.threads.any { it.kind == "codex" } && platform.chatLog().tasks.first { it.time == second.time }.steps.any { it.startsWith("moved to ") } }
        // His [ try again ] on the first, pressed now (from a screen drawn before the second began), does nothing.
        engine.tryAgain(first.time)
        val calledOff = platform.chatLog().tasks.first { it.time == first.time }
        assertEquals("the first switch was called off, not tried again: ${calledOff.steps}", dev.omalauncher.oma.TaskStatus.Stopped, calledOff.status)
        // Long enough for a handoff asked again to be written and a second Codex thread made, as it was before.
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) { Thread.sleep(250); settle(500) }
        assertEquals("exactly one Codex thread", 1, engine.view.threads.count { it.kind == "codex" })
        assertTrue("the first says it was called off: ${calledOff.steps}", calledOff.steps.any { it.startsWith(dev.omalauncher.assist.MAC_SWITCH_OFF) })
    }

    /**
     * The window as a canvas, end to end (slice 5): against the real runner, a new chat's thread asks to write a file, the
     * ask shows in the Needs you module beside the chat, and approving it there lets the agent write it. While arranging,
     * Threads is resized by its corner, a verb in the log that a restart replays, and a drag onto another block is refused. A
     * soma:// link to the chat's thread opens its tab in the chat block. Narrowed, the window is the chat alone.
     */
    @Test fun theCanvasApprovesFromAModuleResizesAndOpensALink() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val front = DesktopFrontAgent(engine, platform, notices, places = emptyList())
        val host = DesktopChatHost(platform, engine, front, notices)
        engine.open()
        val canvas = DesktopCanvas(SomaHome.canvas, "testhost").also { it.load() }
        val source = dev.omalauncher.canvas.chatHostModules(host, handoffs = engine::handoffs, registry = engine::projects)
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        var now by androidx.compose.runtime.mutableStateOf(0L)
        var width by androidx.compose.runtime.mutableStateOf(1000.dp)
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(width, 760.dp)) {
                CanvasScreen(canvas, host, source, now, wanted, onTab = { wanted = it })
            }
        }
        fun settle(ms: Long = 1_200) { now = System.currentTimeMillis(); mainClock.advanceTimeBy(ms) }
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.thread} ${it.status} ${it.steps} ${it.result.take(60)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Chat block").assertExists()
        onNodeWithContentDescription("Needs you module").assertExists()
        onNodeWithText("Nothing waits on you.", substring = true).assertExists()
        // A new chat in the chat block; its thread on the runner asks to write a file.
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("write the notes file")
        settle()
        onNodeWithText("[ send ]").performClick()
        waitFor("the thread's ask reached the modules") { source.asks().isNotEmpty() }
        settle()
        // Approved from the module, not the chat's card: the agent writes it.
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        val thread = platform.chatLog().tasks.last { it.mac }.thread
        val project = File(engine.view.threads.first { it.id == thread }.project)
        waitFor("approved from the module, the file was written") { File(project, "written.txt").isFile }
        // Arranging: Threads is made a row shorter by its corner; a drag onto Needs you is refused.
        onRoot().performMouseInput { rightClick(androidx.compose.ui.geometry.Offset(990f, 745f)) }
        settle(300)
        onNodeWithText("arrange").performClick(); settle(300)
        val logBefore = canvas.log.entries.size
        val threads = canvas.items().first { it.key == "module:threads" }.cell!!
        // While arranging, the bar's 36 dp strip comes off the grid's height.
        val pitchY = (760f - 36f - 8f * (DESKTOP_ROWS + 1)) / DESKTOP_ROWS + 8f
        onNodeWithContentDescription("resize module:threads").performTouchInput { swipe(center, center - androidx.compose.ui.geometry.Offset(0f, pitchY * 1.1f), 400) }
        settle(300)
        assertEquals(threads.copy(h = threads.h - 1), canvas.items().first { it.key == "module:threads" }.cell)
        val grown = canvas.items().first { it.key == "module:threads" }.cell!!
        assertEquals("hand", canvas.log.entries.last().origin.actor)
        assertEquals(logBefore + 1, canvas.log.entries.size)
        // A drag onto Needs you is refused: it goes back where it was and nothing is kept.
        onNodeWithContentDescription("arrange module:threads").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-200f, 0f), 400) }
        settle(300)
        assertEquals(grown, canvas.items().first { it.key == "module:threads" }.cell)
        assertEquals(logBefore + 1, canvas.log.entries.size)
        // Replayed after a restart, as it was left.
        assertEquals(canvas.page, DesktopCanvas(SomaHome.canvas, "testhost").also { it.load() }.page)
        onNodeWithText("  [ done ]").performClick(); settle(300)
        // A link to the chat's thread opens the chat's tab in the chat block, as Main's opener does. Let go of first, the
        // chat block opens a new chat (no Soma tab here), beside the one the thread is on.
        wanted = null; settle()
        assertEquals(2, platform.book().chats.size)
        val tab = dev.omalauncher.oma.somaLinkTab("soma://thread/$thread", platform.book())
        assertEquals(dev.omalauncher.oma.chatKey(platform.book().chats.single { it.mac == thread }.id), tab)
        wanted = tab; settle()
        onNode(hasText("write the notes file", substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasContentDescription("Chat block"))).assertExists()
        // The Timeline module says it once, though the chat is named by its words.
        onNode(hasText("write the notes file · write the notes file", substring = true)).assertDoesNotExist()
        // Narrowed under the canvas's room, the window is the chat alone; widened, the canvas is back as it was.
        width = 600.dp; settle()
        onNodeWithContentDescription("Needs you module").assertDoesNotExist()
        width = 1000.dp; settle()
        onNodeWithContentDescription("Needs you module").assertExists()
        val shot = onRoot().captureToImage()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(shot.toAwtImage(), "png", File("build/render/desktop-canvas-e2e.png"))
    }

    /**
     * Slice 1 of desktop parity (Boss, 2026-10-28): a chat's pick of agent is its own. A new chat with Codex picked runs
     * on Codex; the next new chat, picked nothing, shows and runs Claude again, where before the pick became the default.
     * And chats are named: the fast model's words, or his first words when it can't (every tab said "New chat").
     */
    @Test fun aPickIsTheChatsOwnAndChatsAreNamed() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.chat} ${it.thread} ${it.status}" }} ${engine.view.threads.map { "${it.id} ${it.kind}" }}", done())
        }
        fun kindOf(chat: String) = engine.view.threads.firstOrNull { it.id == platform.book().chat(chat)?.mac }?.kind
        fun newChat() {
            if (onAllNodes(androidx.compose.ui.test.hasContentDescription("Show the tabs")).fetchSemanticsNodes().isNotEmpty()) { onNodeWithContentDescription("Show the tabs").performClick(); settle() }
            onNodeWithContentDescription("New chat").performClick(); settle()
        }
        settle()
        waitFor("the runner said it runs Codex") { "codex" in engine.kindsOn("testhost") }
        settle()
        newChat()
        onNodeWithText("[ Claude ]").performClick(); settle()
        onAllNodes(hasText("Codex") and hasClickAction()).onFirst().performClick(); settle()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("say hello from codex")
        settle(); onNodeWithText("[ send ]").performClick(); settle()
        val first = platform.book().chats.last().id
        waitFor("the first chat runs on Codex") { kindOf(first) == "codex" }
        newChat()
        onNodeWithText("[ Claude ]").assertExists()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what files are here")
        settle(); onNodeWithText("[ send ]").performClick(); settle()
        val second = platform.book().chats.last().id
        assertTrue("a second chat", second != first)
        waitFor("the next chat, picked nothing, runs on Claude") { kindOf(second) == "claude" }
        waitFor("both answered") { platform.chatLog().tasks.count { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } >= 2 }
        // Named: by the model where it answers, by his first words where it can't.
        DesktopChatTitles(platform) { ask -> if ("codex" in ask.message) "Codex Says Hello" else null }.check()
        waitFor("both chats named") { platform.book().names.keys.containsAll(listOf(dev.omalauncher.oma.chatKey(first), dev.omalauncher.oma.chatKey(second))) }
        assertEquals("Codex Says Hello", platform.book().names[dev.omalauncher.oma.chatKey(first)])
        assertEquals("what files are here", platform.book().names[dev.omalauncher.oma.chatKey(second)])
        settle()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/desktop-e2e-names.png"))
    }

    /**
     * Slice 2 (Boss, 2026-10-28): a picture pasted or dropped on the desktop's chat is taken as a picked one, shown under
     * the field, put on the Mac with his words, and the thread is told where it is, as the phone's pictures are.
     */
    @Test fun aPastedPictureGoesToTheMacThreadWithHisWords() = runComposeUiTest {
        val notices = Notices()
        val machine = MacMachine("testhost", "Test box", MacRunnerConfig("http://127.0.0.1:$port", key))
        val platform = DesktopThreadsPlatform(notices) { listOf(machine) }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        engine.open()
        var wanted: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }) }
        fun settle(ms: Long = 1_200) = mainClock.advanceTimeBy(ms)
        fun waitFor(what: String, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(250); settle(500) }
            assertTrue("$what: ${platform.chatLog().tasks.map { "${it.thread} ${it.status} ${it.result.take(80)}" }}", done())
        }
        settle()
        onNodeWithContentDescription("Show the tabs").performClick(); settle()
        onNodeWithContentDescription("New chat").performClick(); settle()
        onNodeWithContentDescription("Attach images").assertExists()
        val shot = File(work, "screenshot.png").also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }
        val pasted = File(work, "pasted.png").also { shot.copyTo(it) }
        runOnIdle { assertTrue("the chat took it", host.arrived(listOf(FileImage(shot), FileImage(pasted, temporary = true)))) }
        waitFor("both pictures under the field") { onAllNodes(androidx.compose.ui.test.hasContentDescription("Remove image 2")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("his own file stays", shot.isFile)
        assertTrue("the pasted copy's temporary file went", !pasted.exists())
        // Three more pasted: two fit (four at most), the third is let go, its temporary file too (round-1 Critic).
        val more = (1..3).map { n -> File(work, "more$n.png").also { shot.copyTo(it) } }
        runOnIdle { assertTrue(host.arrived(more.map { FileImage(it, temporary = true) })) }
        waitFor("four pictures under the field") { onAllNodes(androidx.compose.ui.test.hasContentDescription("Remove image 4")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("none left behind: ${more.filter { it.exists() }}", more.none { it.exists() })
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("what is in this picture")
        settle(); onNodeWithText("[ send ]").performClick(); settle()
        waitFor("the answer came") { platform.chatLog().tasks.any { it.mac && it.status == dev.omalauncher.oma.TaskStatus.Done } }
        val entry = platform.chatLog().tasks.first { it.mac }
        val said = (MacRunnerClient(machine.config, "testhost").events(entry.thread, 0) as dev.omalauncher.assist.MacAnswer.Ok).value.events
            .filterIsInstance<dev.omalauncher.assist.MacEvent.Message>().first().text
        assertTrue("his words, then where the pictures are: $said", said.startsWith("what is in this picture") && dev.omalauncher.assist.PICTURES_NOTE.trim() in said)
        val kept = File(work, "Downloads/From Fold").listFiles().orEmpty()
        assertEquals("all four pictures are on the Mac", 4, kept.size)
        assertTrue("the thread is told their paths", kept.all { it.path in said })
        settle()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/desktop-e2e-pictures.png"))
    }
}
