package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

/** Soma's front agent arranging the window's canvas (slice 6): `soma canvas`, its token, pages, and undo from the log. */
class CanvasVerbsDesktopTest {
    private val needs = moduleKey(ModuleKind.Needs)!!

    private fun canvas() = DesktopCanvas(Files.createTempDirectory("soma-verbs").toFile().resolve("canvas.json"), "test").also { it.load() }

    private fun call(door: CanvasDoor, token: String, line: String) = JSONObject(door.handle(CanvasDoor.WORD + token + " " + JSONArray(line.split(' '))))

    @Test fun aPageForAProjectIsShownAndOneUndoTakesTheWholeRequestBack() {
        val canvas = canvas()
        val before = canvas.page
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        assertTrue(call(door, token, "page-make Soma project").getBoolean("ok"))
        assertTrue(call(door, token, "place needs card top-left --page Soma project").getBoolean("ok"))
        assertTrue(call(door, token, "place handoff:soma card below needs --page Soma project").getBoolean("ok"))
        // The window shows the new page, the chat still on it at its cells, and nothing under the chat.
        val made = canvas.page.pages.last()
        assertEquals(made.id, canvas.shown)
        val chat = canvas.items().first { it.key == CHAT_BLOCK }.cell!!
        assertEquals(Cell(0, 0, 5, DESKTOP_ROWS), chat)
        assertEquals(Cell(5, 0, 4, 3), made.items.first { it.key == "$needs#2" }.cell)
        assertTrue(made.items.none { it.cell!!.overlaps(chat) })
        // The first page keeps its own Needs you: the project page has a copy of its own.
        assertTrue(canvas.page.pages.first().items.any { it.key == needs })
        // Kept as verbs: a new Soma reading the file sees the same canvas.
        val again = DesktopCanvas(canvasFile(canvas), "test").also { it.load() }
        assertEquals(canvas.page, again.page)
        // All three calls were one request: one undo puts the canvas back as it was, and shows the first page.
        val undo = call(door, door.mint("front-2"), "undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        // The same blocks in the same cells on the same pages (an undone move puts a block back last in its page's list).
        fun cells(n: dev.omalauncher.widgets.layout.Notebook) = n.pages.map { p -> p.id to p.items.associate { it.key to it.cell } }
        assertEquals(cells(before), cells(canvas.page))
        assertEquals(DESKTOP_PAGE, canvas.shown)
        assertFalse(call(door, door.mint("front-3"), "undo").getBoolean("ok"))
    }

    @Test fun undoIsTheAgentsNeverWhatHeDidByHand() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        call(door, door.mint("front-1"), "resize threads line")
        canvas.apply(CanvasVerb.Remove(moduleKey(ModuleKind.Computers)!!))
        val undo = call(door, door.mint("front-2"), "undo")
        assertTrue(undo.getBoolean("ok"))
        assertFalse(canvas.has(moduleKey(ModuleKind.Computers)!!))
        assertEquals(Cell(9, 0, 3, 3), canvas.items().first { it.key == moduleKey(ModuleKind.Threads) }.cell)
    }

    @Test fun theChatsPageAndTheChatStayAndNothingGoesUnderIt() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        for (line in listOf("page-remove Soma", "remove chat", "move chat top-right", "resize chat full")) assertFalse(line, call(door, token, line).getBoolean("ok"))
        call(door, token, "page-make More")
        val more = canvas.page.pages.last().id
        // By hand too: a block on another page can't be put under the chat, and the chat can't be moved over one.
        assertFalse(canvas.apply(CanvasVerb.Place(moduleKey(ModuleKind.Pocket)!!, dev.omalauncher.widgets.layout.ItemKind.Module, more, Cell(2, 0, 3, 2))).applied)
        assertTrue(canvas.apply(CanvasVerb.Place(moduleKey(ModuleKind.Pocket)!!, dev.omalauncher.widgets.layout.ItemKind.Module, more, Cell(8, 4, 3, 2))).applied)
        assertFalse(canvas.apply(CanvasVerb.Move(CHAT_BLOCK, DESKTOP_PAGE, Cell(6, 0, 5, DESKTOP_ROWS))).applied)
    }

    @Test fun aTokenReachesTheCanvasOnlyWhileItsRequestRuns() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        for (bad in listOf("", "nottoken", token.dropLast(1), token + "0", token.uppercase())) {
            val r = JSONObject(door.handle(CanvasDoor.WORD + bad + " " + JSONArray(listOf("page-make", "X"))))
            assertFalse(bad, r.getBoolean("ok"))
        }
        assertFalse(JSONObject(door.handle(CanvasDoor.WORD + token + " not json")).getBoolean("ok"))
        door.revoke(token)
        assertFalse(call(door, token, "page-make X").getBoolean("ok"))
        assertEquals(1, canvas.page.pages.size)
        // Two requests' tokens differ, and each names its own request.
        assertNotEquals(door.mint("a"), door.mint("a"))
    }

    @Test fun theClientHandsItsWordsToTheRunningSomaAndSaysItsAnswer() {
        val home = Files.createTempDirectory("soma-client").toFile()
        System.setProperty("soma.home", home.path)
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        SomaHome.socket.writeText(server.localPort.toString())
        thread(isDaemon = true) {
            repeat(2) {
                server.accept().use { c ->
                    val line = c.getInputStream().bufferedReader().readLine()
                    c.getOutputStream().write((door.handle(line) + "\n").toByteArray())
                }
            }
        }
        val said = ArrayList<String>()
        assertEquals(0, CanvasDoor.client(listOf("page-make", "My", "--page", "Desk"), mapOf(CanvasDoor.TOKEN to token), said::add))
        assertEquals("My --page Desk", canvas.page.pages.last().name)
        assertEquals(1, CanvasDoor.client(listOf("remove", "chat"), mapOf(CanvasDoor.TOKEN to token), said::add))
        assertTrue(said.last(), said.last().contains("stays"))
        // No token: nothing is sent at all.
        assertEquals(1, CanvasDoor.client(listOf("board"), emptyMap(), said::add))
        server.close()
    }

    @Test fun aRequestThatArrangesUndoesAndArrangesAgainIsStillUndone() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        call(door, token, "page-make One")
        assertTrue(call(door, token, "undo").getBoolean("ok"))
        call(door, token, "page-make Two")
        assertEquals(listOf("Soma", "Two"), canvas.page.pages.map { it.name })
        assertTrue(call(door, door.mint("front-2"), "undo").getBoolean("ok"))
        assertEquals(listOf("Soma"), canvas.page.pages.map { it.name })
    }

    @Test fun aFullModuleIsAsWideAsThePageBesideTheChat() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        call(door, token, "page-make Wide")
        call(door, token, "place timeline full --page Wide")
        call(door, token, "place pocket full --page Wide")
        // Both on the page asked for, each the page's width beside the chat: no page made for either.
        assertEquals(listOf("Soma", "Wide"), canvas.page.pages.map { it.name })
        val wide = canvas.page.pages.last().items
        assertEquals(listOf(Cell(5, 0, 7, 3), Cell(5, 3, 7, 3)), wide.map { it.cell })
        assertEquals(dev.omalauncher.canvas.ModuleSize.Full, dev.omalauncher.canvas.moduleSize(7, 3, canvas.rules.span(canvas.page)))
    }

    @Test fun theFrontAgentRunsInAnEmptyFolderWithATokenGoneWhenItEnds() {
        val home = Files.createTempDirectory("soma-front").toFile()
        System.setProperty("soma.home", home.path)
        val seen = java.io.File(home, "seen.txt")
        // A stand-in for Claude Code: says where it runs and whether it has a token, then answers.
        val bin = java.io.File(home, "bin-claude").apply { mkdirs() }
        val claude = java.io.File(bin, "claude").apply {
            writeText("#!/bin/sh\n{ pwd; ls -A | wc -l; [ -n \"\$SOMA_CANVAS_TOKEN\" ] && echo token; } > '${seen.path}'\n" +
                "echo '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"Done.\",\"session_id\":\"00000000-0000-0000-0000-000000000000\"}'\n")
            setExecutable(true)
        }
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = dev.omalauncher.assist.MacThreadsEngine(platform)
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val empty = java.io.File(home, "front-empty")
        val front = DesktopFrontAgent(engine, platform, notices, places = listOf(claude.path), door = door, shim = CanvasDoor.shim(SomaHome.bin), emptyFolder = { empty.apply { mkdirs() } })
        front.ask("put needs you top right")
        val until = System.currentTimeMillis() + 15_000
        while ((front.working || !seen.exists()) && System.currentTimeMillis() < until) Thread.sleep(50)
        Thread.sleep(200)
        assertEquals(listOf(empty.canonicalPath, "0", "token"), seen.readLines().map { it.trim() })
        assertEquals(0, door.open)
    }

    @Test fun whenAnAgentRemovesThePageShownTheWindowShowsTheChatsAndHandDragsWork() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        call(door, token, "page-make Gone")
        call(door, token, "place pocket card --page Gone")
        assertEquals(canvas.page.pages.last().id, canvas.shown)
        call(door, door.mint("front-2"), "page-remove Gone")
        assertEquals(DESKTOP_PAGE, canvas.shown)
        // A hand move as the window makes one, on the page shown: it lands.
        val threads = canvas.items().first { it.key == moduleKey(ModuleKind.Threads) }.cell!!
        assertTrue(canvas.apply(CanvasVerb.Move(moduleKey(ModuleKind.Threads)!!, canvas.shown, threads.copy(h = 2))).applied)
    }

    @Test fun theBoardShowsTheChatOnEveryPageAndAMoveIntoItSaysWhy() {
        val canvas = canvas()
        val door = CanvasDoor { work -> work(canvas) }
        val token = door.mint("front-1")
        call(door, token, "page-make Two")
        call(door, token, "place pocket card top-left --page Two")
        val two = call(door, token, "board Two").getJSONArray("pages").getJSONObject(0).getJSONArray("items")
        assertEquals("chat", two.getJSONObject(0).getString("kind"))
        assertEquals(5, two.getJSONObject(0).getJSONObject("cells").getInt("w"))
        val stay = call(door, token, "move pocket top-left --page Two")
        assertTrue(stay.getString("did"), stay.getString("did").contains("as near there as this page has room"))
    }

    @Test fun theShimRunsThisSomaAgainAndOnlyItsOwnerCanRunIt() {
        val dir = Files.createTempDirectory("soma-bin").toFile()
        val shim = CanvasDoor.shim(dir)!!
        assertTrue(shim.canExecute())
        val text = shim.readText()
        assertTrue(text.startsWith("#!/bin/sh\nexec "))
        assertTrue(text.contains("dev.omalauncher.desktop.MainKt") || !text.contains("-cp"))
        val mode = Files.getPosixFilePermissions(shim.toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"), mode)
    }

    private fun canvasFile(canvas: DesktopCanvas): java.io.File =
        DesktopCanvas::class.java.getDeclaredField("file").apply { isAccessible = true }.get(canvas) as java.io.File
}
