package dev.omalauncher.desktop

import dev.omalauncher.assist.desktopFrontPrompt
import dev.omalauncher.assist.grokDesktopCommand
import dev.omalauncher.assist.writeGrokGuard
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.moduleKey
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * LiveFrontCanvasTest with Grok as the front agent (Boss, 2026-10-18): the command Soma runs, on the real Grok signed
 * in here, behind its command guard in a folder of its own, through the real `soma canvas`. Runs only when
 * SOMA_LIVE_GROK names Grok's CLI; it spends a little of his Grok plan and touches no Soma of his.
 */
class LiveGrokFrontCanvasTest {
    @Volatile private var lines: MutableList<String>? = null

    @Test fun makeMeAPageForAProjectThenUndo() {
        val grok = System.getenv("SOMA_LIVE_GROK")
        assumeTrue("set SOMA_LIVE_GROK to Grok's path to run this", grok != null && File(grok).canExecute())
        // Not under a temporary folder: Grok loads no folder's hooks there, so the guard would not be in front of it.
        val home = File(System.getProperty("user.home"), ".cache/soma-live-grok-" + System.nanoTime()).apply { mkdirs() }
        val folder = File(home, "front").apply { mkdirs() }
        assertTrue(writeGrokGuard(folder))
        System.setProperty("soma.home", home.path)
        val canvas = DesktopCanvas(SomaHome.canvas, "live").also { it.load() }
        val door = CanvasDoor { work -> synchronized(canvas) { work(canvas) } }
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        SomaHome.socket.writeText(server.localPort.toString())
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { c ->
                    val line = c.getInputStream().bufferedReader().readLine()
                    System.err.println("door <- " + line.replace(Regex("canvas [0-9a-f]{64}"), "canvas TOKEN"))
                    lines?.add(line)
                    c.getOutputStream().write((door.handle(line) + "\n").toByteArray())
                }
            }
        }
        val shim = CanvasDoor.shim(SomaHome.bin)!!
        // As Soma does, the next request follows up on the front conversation the last one had.
        var session: String? = null
        fun ask(words: String, request: String): String {
            val token = door.mint(request)
            val command = grokDesktopCommand(desktopFrontPrompt(null, words), resume = session, canvas = true)
            val child = ProcessBuilder("/bin/sh", "-c", command).apply {
                directory(folder)
                environment()["PATH"] = listOf(shim.parent, File(grok!!).parent, "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin").joinToString(":")
                environment()[CanvasDoor.TOKEN] = token
            }.redirectErrorStream(true).start()
            val out = child.inputStream.bufferedReader().readText()
            child.waitFor(180, TimeUnit.SECONDS)
            door.revoke(token)
            session = Regex("\"session_id\":\"([0-9a-f-]{36})\"").findAll(out).lastOrNull()?.groupValues?.get(1) ?: session
            return out
        }
        val made = ask("make me a page for the soma project", "front-1")
        File(home, "made.jsonl").writeText(made)
        val page = canvas.page.pages.drop(1).singleOrNull()
        assertTrue("no new page: ${canvas.page.pages.map { it.name }}\n" + made.takeLast(3000), page != null)
        assertTrue(page!!.items.any { dev.omalauncher.canvas.moduleRef(it.key)?.base == moduleKey(ModuleKind.Needs) })
        System.err.println("page " + page.name + ": " + page.items.map { it.key to it.cell })
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        lines = heard
        val undone = ask("undo that", "front-2")
        // Undone with the undo word, not by taking things away by hand.
        assertTrue(heard.toString(), heard.any { it.endsWith("[\"undo\"]") })
        File(home, "undone.jsonl").writeText(undone)
        assertEquals(listOf(DESKTOP_PAGE), canvas.page.pages.map { it.id })
        assertTrue(canvas.has(moduleKey(ModuleKind.Needs)!!))
        server.close()
        // Nothing but `soma canvas` ran: the guard turned down anything else it tried.
        System.err.println("refused by the guard: " + Regex("Hook denied: [^\"]*").findAll(made + undone).map { it.value }.toList())
        System.err.println("live transcripts in $home")
    }
}
