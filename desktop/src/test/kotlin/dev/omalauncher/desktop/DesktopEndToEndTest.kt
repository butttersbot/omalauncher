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
        // A link to the chat's thread opens the chat's tab in the chat block, as Main's opener does.
        wanted = null; settle()
        val tab = dev.omalauncher.oma.somaLinkTab("soma://thread/$thread", platform.book())
        assertEquals(dev.omalauncher.oma.chatKey(platform.book().chats.single().id), tab)
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
}
