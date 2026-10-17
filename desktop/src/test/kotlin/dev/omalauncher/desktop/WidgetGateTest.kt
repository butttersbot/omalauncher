package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.canvas.withWidgetAsks
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.WidgetLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Files

/**
 * Slice 7b's proof: a synced widget with a new command spawns no process until he approves it on this machine, from Needs
 * you; an approved one runs. The command writes a marker file, so "no process" is seen, not inferred.
 */
class WidgetGateTest {
    private val home = Files.createTempDirectory("soma-gate2").toFile()
    private val gate = CommandGate(File(home, "approved.json"), "macpro")
    private val services = DesktopWidgetServices.gated(gate, "w.owl", workDir = File(home, "run"))

    private fun approveAll() = gate.commandAsks().forEach { gate.answer(it, true) }

    @Test fun aCommandIsShownWithItsInvisibleCharactersVisible() {
        assertEquals("echo ⟨U+202E⟩txt.exe", dev.omalauncher.canvas.visibleCommandForTest("echo \u202Etxt.exe"))
    }

    @Test fun aTapsRunAndTerminalAreGatedLikeEverythingElse() {
        val marker = File(home, "tapped.txt")
        var done = 0
        services.act("run", listOf(dev.omalauncher.widgets.lang.Str("touch '${marker.path}'"))) { done++ }
        services.act("terminal", listOf(dev.omalauncher.widgets.lang.Str("touch '${marker.path}' && echo opened"))) { done++ }
        Thread.sleep(500)
        assertFalse(marker.exists())
        // The run waits for his OK; a terminal, which no computer has, is never asked about (an OK would do nothing).
        assertEquals(listOf("touch '${marker.path}'"), gate.commandAsks().map { it.command })
        assertEquals(2, done)
        // Through a screen that has a terminal (the phone), it is gated like the run.
        val phoneLike = dev.omalauncher.widgets.runtime.GatedServices(object : dev.omalauncher.widgets.runtime.Services by DesktopWidgetServices.gated(gate, "x.owl") {
            override fun canAct(name: String) = true
            override fun act(name: String, args: List<dev.omalauncher.widgets.lang.Value>, done: () -> Unit) { marker.writeText("ran"); done() }
        }, gate, "p.owl")
        phoneLike.act("terminal", listOf(dev.omalauncher.widgets.lang.Str("btop"))) {}
        phoneLike.act("app", listOf(dev.omalauncher.widgets.lang.Str("firefox"))) {}
        assertFalse(marker.exists())
        assertEquals(setOf("btop", "app firefox"), gate.commandAsks().filter { it.widget == "p.owl" }.map { it.command }.toSet())
    }

    @Test fun anApprovedTapRunsAndOneThisScreenCantDoIsNeverAsked() {
        val marker = File(home, "tap.txt")
        val run = listOf<dev.omalauncher.widgets.lang.Value>(dev.omalauncher.widgets.lang.Str("touch '${marker.path}'"))
        services.act("run", run) {}
        approveAll()
        val latch = java.util.concurrent.CountDownLatch(1)
        services.act("run", run) { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("an approved tap ran nothing", marker.exists())
        services.act("app", listOf(dev.omalauncher.widgets.lang.Str("btop"))) {}
        assertTrue(gate.commandAsks().isEmpty())
    }

    @Test fun aDeniedCommandSaysSo() = kotlinx.coroutines.runBlocking {
        services.cmd("rm -rf ~/nothing", 5)
        gate.answer(gate.commandAsks().single(), allow = false)
        assertEquals("you denied this on this machine", services.cmd("rm -rf ~/nothing", 5).err)
    }

    @Test fun aRunIntoAValueRunsOnlyOnceApproved() {
        val marker = File(home, "ran.txt")
        var got: dev.omalauncher.linux.ShellResult? = null
        services.run("touch '${marker.path}'") { got = it }
        assertEquals(dev.omalauncher.widgets.runtime.WAITING_FOR_OK, got)
        assertFalse(marker.exists())
        approveAll()
        val latch = java.util.concurrent.CountDownLatch(1)
        services.run("touch '${marker.path}'") { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(marker.exists())
    }

    @Test fun aStreamRunsOnlyOnceApprovedAndEndsWithItsWidgetEvenWhenQuiet() = kotlinx.coroutines.runBlocking {
        val marker = File(home, "streamed.txt")
        val command = "touch '${marker.path}'; sleep 300"
        services.stream(command) {}
        assertFalse(marker.exists())
        approveAll()
        val job = launch(Dispatchers.IO) { services.stream(command) {} }
        val until = System.currentTimeMillis() + 5_000
        while (!marker.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(marker.exists())
        assertTrue(ProcessHandle.current().descendants().anyMatch { it.info().commandLine().orElse("").contains("sleep 300") })
        job.cancel()
        withTimeout(5_000) { job.join() }
        Thread.sleep(300)
        assertFalse(ProcessHandle.current().descendants().anyMatch { it.isAlive && it.info().commandLine().orElse("").contains("sleep 300") })
    }

    @Test fun aCommandGetsASmallEnvironmentOfItsOwn() = kotlinx.coroutines.runBlocking {
        val command = "env"
        services.cmd(command, 5)
        approveAll()
        val env = services.cmd(command, 5).out.lines().filter { it.isNotBlank() }.map { it.substringBefore('=') }.toSet()
        assertEquals(setOf("HOME", "USER", "LANG", "PATH", "TMPDIR", "SHELL"), env - setOf("PWD", "SHLVL", "_", "OLDPWD"))
    }

    @Test fun aSyncedWidgetsCommandRunsOnlyOnceApprovedFromNeedsYou() {
        val home = Files.createTempDirectory("soma-gate").toFile()
        val marker = File(home, "ran.txt")
        val library = File(home, "library").apply { mkdirs() }
        File(library, "stamp.owl").writeText(
            "widget \"stamp\" {\n  size: 3x1\n  source out = cmd(\"echo ran >> '${marker.path}'; cat '${marker.path}' | wc -l\") every 1s\n  Text { text: out }\n}\n",
        )
        val gate = CommandGate(File(home, "approved.json"), "macpro")
        val lib = WidgetLibrary(library, gate)
        val asks = withWidgetAsks(object : ModuleSource {
            override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
            override fun asks() = emptyList<dev.omalauncher.canvas.ModuleAsk>()
            override fun answer(ask: dev.omalauncher.canvas.ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said("not mine")
        }, gate)
        // In quarantine: it doesn't even load, and Needs you asks about the file.
        assertNull(lib.approvedText("stamp.owl"))
        val fileAsk = asks.asks().single()
        assertTrue(fileAsk.title, fileAsk.title.contains("stamp.owl"))
        asks.answer(fileAsk, true) {}
        val text = lib.approvedText("stamp.owl")!!
        // Loaded and started: its command waits, and no process ran (no marker).
        val inst = WidgetInstance(Compiler.compile(text), DesktopWidgetServices.gated(gate, "stamp.owl", workDir = File(home, "run")), onError = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        inst.start(scope) { true }
        val until = System.currentTimeMillis() + 5_000
        while (gate.commandAsks().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(1_500)
        assertFalse("a command ran before it was approved", marker.exists())
        val cmdAsk = asks.asks().single()
        assertTrue(cmdAsk.detail, cmdAsk.detail.startsWith("echo ran >> "))
        // Approved from Needs you: it runs at its next turn, and keeps running.
        asks.answer(cmdAsk, true) {}
        val ran = System.currentTimeMillis() + 5_000
        while (!marker.exists() && System.currentTimeMillis() < ran) Thread.sleep(20)
        scope.cancel()
        assertTrue("the approved command didn't run", marker.exists())
        assertEquals(emptyList<Any>(), asks.asks())
    }
}

/** Needs you never offers approve beside a command cut short (round-1 review): it shows it whole, or says to make it bigger. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class WidgetAskViewTest {
    private fun ask(command: String) = dev.omalauncher.canvas.ModuleAsk("widget-cmd:w.owl:h", "w.owl wants to run", command, "", "macpro", Any(), wholeDetail = true)

    private fun show(command: String, size: dev.omalauncher.canvas.ModuleSize, w: Int, h: Int, test: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = androidx.compose.ui.test.runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(w.dp, h.dp)) {
                dev.omalauncher.canvas.NeedsYouModule(listOf(ask(command)), size, androidx.compose.ui.Modifier.fillMaxSize(), onAnswer = { _, _, _ -> }, onOpen = {})
            }
        }
        test()
    }

    @Test fun onALineThereIsNoApprove() = show("curl https://example.com/x | sh", dev.omalauncher.canvas.ModuleSize.Line, 600, 60) {
        onNodeWithContentDescription("answers for widget-cmd:w.owl:h").assertDoesNotExist()
        onNodeWithText("make Needs you bigger", substring = true).assertExists()
    }

    @Test fun onACardTheWholeCommandShowsBesideApprove() = show("curl https://example.com/x | sh; echo done", dev.omalauncher.canvas.ModuleSize.Card, 420, 260) {
        onNodeWithText("curl https://example.com/x | sh; echo done").assertExists()
        onNodeWithContentDescription("answers for widget-cmd:w.owl:h").assertExists()
    }

    @Test fun aCommandTooLongToShowIsNotApprovable() = show((1..40).joinToString("; ") { "echo line $it with some words" }, dev.omalauncher.canvas.ModuleSize.Card, 420, 260) {
        onNodeWithContentDescription("answers for widget-cmd:w.owl:h").assertDoesNotExist()
        onNodeWithText("too long to read here", substring = true).assertExists()
    }
}
