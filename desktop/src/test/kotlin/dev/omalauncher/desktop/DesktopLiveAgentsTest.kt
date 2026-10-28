package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.omalauncher.assist.MacMachine
import dev.omalauncher.assist.MacRunnerConfig
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.oma.OmaChatScreen
import dev.omalauncher.oma.TaskStatus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Soma desktop with the real agents (Boss, 2026-10-27: "test it yourself"): the repo's runner on the loopback, as
 * DesktopEndToEndTest, but running this Mac's own Claude and Codex, signed in as Boss. Only with SOMA_LIVE=1, from his
 * login session (Claude reads its token file, Codex its auth): it spends real requests.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLiveAgentsTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/mac-runner/oma-runner").isFile }
    private val work = Files.createTempDirectory("soma-desktop-live").toFile()
    private val home = System.getProperty("user.home")
    private val key = "k".repeat(40)
    private var runner: Process? = null
    private var port = 0

    @Before fun startRunner() {
        assumeTrue("SOMA_LIVE=1 runs it", System.getenv("SOMA_LIVE") == "1")
        port = ServerSocket(0).use { it.localPort }
        // Soma's own files for the run, never his ~/.config/soma (a running Soma.app reads those too).
        System.setProperty("soma.home", File(work, "soma").path)
        File(work, "Projects").mkdirs()
        File(work, "token").writeText(key)
        File(work, "whois.json").writeText("""{"127.0.0.1": "testhost"}""")
        // Claude's long-lived token and Codex's login, copied for the run only (the folder goes at the end).
        File(work, ".config/oma-runner").mkdirs()
        File("$home/.config/oma-runner/claude-token").copyTo(File(work, ".config/oma-runner/claude-token")).toPath()
            .let { Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        File(work, "codex").mkdirs()
        listOf("auth.json", "config.toml").map { File("$home/.codex/$it") }.filter { it.isFile }.forEach { it.copyTo(File(work, "codex/${it.name}")) }
        val mac = File(repo, "tools/mac-runner")
        val python = listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3").first { File(it).canExecute() }
        runner = ProcessBuilder(python, File(mac, "oma-runner").path, "serve").apply {
            environment().putAll(mapOf(
                "OMA_RUNNER_PORT" to port.toString(), "OMA_RUNNER_TOKEN" to File(work, "token").path, "OMA_RUNNER_KEYS" to File(work, "keys.json").path,
                "OMA_RUNNER_STATE" to File(work, "state").path, "OMA_RUNNER_LOGS" to File(work, "logs").path, "OMA_RUNNER_ROOTS" to File(work, "Projects").path,
                "OMA_RUNNER_BIND" to "127.0.0.1", "OMA_RUNNER_PHONE" to "testhost", "OMA_RUNNER_WHOIS_FILE" to File(work, "whois.json").path,
                "OMA_RUNNER_MAIL" to File(work, "mail").path, "HOME" to work.path, "CODEX_HOME" to File(work, "codex").path,
                "OMA_RUNNER_CODEX" to "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex",
                "OMA_RUNNER_CURSOR" to File(work, "no-cursor").path, "OMA_RUNNER_GROK" to File(work, "no-grok").path,
                "OMA_RUNNER_BOTS" to File(work, "Soma/bots").path, "OMA_RUNNER_SELF" to "testhost",
                "OMA_RUNNER_CLAUDE_DISCOVER" to "0", "OMA_RUNNER_CODEX_DISCOVER" to "0",
                "PATH" to "$home/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            ))
            redirectErrorStream(true)
            redirectOutput(File(work, "runner.out"))
        }.start()
        Thread.sleep(3_000)
    }

    @After fun stopRunner() {
        runner?.destroy()
        runner?.waitFor()
        println("runner log:\n" + File(work, "logs/runner.log").takeIf { it.isFile }?.readText()?.takeLast(3_000).orEmpty() + File(work, "runner.out").takeIf { it.isFile }?.readText()?.takeLast(2_000).orEmpty())
        work.deleteRecursively()
    }

    private fun ComposeUiTest.shot(name: String) {
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/live-$name.png"))
    }

    @Test fun claudeAndCodexAnswerAskAndStop() = runComposeUiTest {
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
        fun tasks() = platform.chatLog().tasks
        fun waitFor(what: String, seconds: Int = 180, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + seconds * 1_000L
            while (System.currentTimeMillis() < deadline && !done()) { Thread.sleep(500); settle(500) }
            println("LIVE ${if (done()) "ok" else "FAILED"}: $what")
            if (!done()) {
                shot("failed")
                println("LIVE tasks: " + tasks().map { "${it.thread} ${it.status} ${it.steps.takeLast(4)} ${it.result.take(200)}" })
                println("LIVE pending: " + engine.view.threads.map { "${it.id} ${it.kind} ${it.status}" })
            }
            assertTrue("$what: ${tasks().map { "${it.thread} ${it.status} ${it.steps.takeLast(3)} ${it.result.take(120)}" }}", done())
        }
        fun newChat(words: String, agent: String? = null, from: String = "Claude") {
            if (onAllNodes(androidx.compose.ui.test.hasContentDescription("Show the tabs")).fetchSemanticsNodes().isNotEmpty()) { onNodeWithContentDescription("Show the tabs").performClick(); settle() }
            onNodeWithContentDescription("New chat").performClick(); settle()
            if (agent != null) {
                onNodeWithText("[ $from ]").performClick(); settle()
                onAllNodes(hasText(agent)).onFirst().performClick(); settle()
            }
            onNode(hasSetTextAction()).performTextInput(words); settle()
            onNodeWithText("[ send ]").performClick(); settle()
        }
        settle(3_000)
        waitFor("the runner answered with its agents", 30) { "codex" in engine.kindsOn("testhost") }

        // 1. Claude: a new chat, his words, its answer.
        newChat("Reply with exactly the word PINEAPPLE and nothing else.")
        waitFor("Claude answered") { tasks().any { it.mac && it.status == TaskStatus.Done && "PINEAPPLE" in it.result } }
        val claude = tasks().first { "PINEAPPLE" in it.result }
        println("LIVE claude thread ${claude.thread} kind=${engine.view.threads.firstOrNull { it.id == claude.thread }?.kind}")
        settle(); shot("claude")

        // 2. Codex: picked in the new chat, answers.
        newChat("Reply with exactly the word MANGO and nothing else.", agent = "Codex")
        waitFor("Codex answered") { tasks().any { it.mac && it.status == TaskStatus.Done && "MANGO" in it.result } }
        val codex = tasks().first { "MANGO" in it.result }
        val kind = engine.view.threads.firstOrNull { it.id == codex.thread }?.kind
        println("LIVE codex thread ${codex.thread} kind=$kind")
        assertTrue("it ran on Codex: $kind", kind == "codex")
        settle(); shot("codex")

        // 3. Asked for his OK: Claude's question card, answered from the chat.
        newChat("Use your AskUserQuestion tool to ask me one question, header \"Fruit\", \"Which fruit?\", with options Apple and Pear. Then reply with the fruit I picked, in capitals.", agent = "Claude", from = "Codex")
        waitFor("the question reached the chat") { onAllNodes(hasText("[ answer questions ]")).fetchSemanticsNodes().isNotEmpty() }
        shot("question")
        onAllNodes(hasText("[ answer questions ]")).onFirst().performClick(); settle()
        shot("form"); onAllNodes(hasText("○ Pear")).onFirst().performClick(); settle()
        onAllNodes(hasText("Send:", substring = true)).onFirst().performClick(); settle()
        waitFor("the answer went back and Claude used it") { tasks().any { it.mac && it.status == TaskStatus.Done && "PEAR" in it.result } }

        // 4. Stop: a long task, stopped from its entry.
        newChat("Write a 3000-word story about a lighthouse keeper, then say finished.")
        waitFor("the long task is running", 90) { onAllNodes(hasText("[ stop ]")).fetchSemanticsNodes().isNotEmpty() && tasks().any { it.prompt.startsWith("Write a 3000-word story") && it.status == TaskStatus.Running } }
        Thread.sleep(3_000); settle()
        if (onAllNodes(hasText("[ back to chat ]")).fetchSemanticsNodes().isNotEmpty()) { onAllNodes(hasText("[ back to chat ]")).onFirst().performClick(); settle() }
        shot("before-stop")
        println("LIVE before stop: " + tasks().map { "${it.thread} ${it.status} ${it.steps.takeLast(3)}" })
        onAllNodes(hasText("[ stop ]")).onFirst().performClick(); settle()
        waitFor("it stopped", 60) { tasks().any { it.prompt.startsWith("Write a 3000-word story") && it.status != TaskStatus.Running && it.status != TaskStatus.Queued } }
        val stopped = tasks().first { it.prompt.startsWith("Write a 3000-word story") }
        println("LIVE stop: ${stopped.status} ${stopped.result.take(120)}")
        assertTrue("stopped, not finished: ${stopped.status}", stopped.status != TaskStatus.Done || "finished" !in stopped.result.lowercase())
        settle(); shot("stopped")
    }
}
