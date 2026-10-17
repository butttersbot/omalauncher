package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
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
            ))
            redirectErrorStream(true)
        }.start()
        runner!!.inputStream.bufferedReader().readLine()
        System.setProperty("soma.home", File(work, "soma").path)
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
}
