package dev.omalauncher.desktop

import dev.omalauncher.canvas.CanvasCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Templates and LAYOUT.md on the service's disk (slice 10): one file each, kept whole, read back by name whatever its case. */
class FileShelfTest {
    @Test fun aTemplateAndHisPreferencesAreKeptOnDiskAndUsedAcrossScreens() {
        val home = Files.createTempDirectory("soma-shelf").toFile()
        val shelf = FileShelf(home)
        val fold = ScreenCanvas(File(home, "screens/fold-q5.json"), "fold-q5", 6, 8, "macpro").also { it.load() }
        val desk = ScreenCanvas(File(home, "screens/desk-macbook.json"), "desk-macbook", 12, 8, "macpro").also { it.load() }
        assertTrue(CanvasCommands.run("place needs card top-left".split(' '), fold, "r1", "agent:t", shelf).getBoolean("ok"))
        assertTrue(CanvasCommands.run("template-save Morning Run".split(' '), fold, "r2", "agent:t", shelf).getBoolean("ok"))
        assertTrue(File(home, "templates/morning-run.json").isFile)
        assertEquals(listOf("Morning Run"), FileShelf(home).templates())
        val applied = CanvasCommands.run("template-apply morning run".split(' '), desk, "r3", "agent:t", shelf)
        assertTrue(applied.toString(), applied.getBoolean("ok"))
        assertTrue(desk.page.pages.any { p -> p.name.equals("morning run", ignoreCase = true) && p.items.any { it.key == "module:needs" } })
        CanvasCommands.run("remember Handoff is wide, never tall".split(' '), desk, "r4", "agent:t", shelf)
        assertEquals("# How Boss likes his canvases\n- Handoff is wide, never tall\n", File(home, "LAYOUT.md").readText())
        // Nothing outside the templates folder is ever read, and a hand-dropped file with a strange name isn't listed.
        File(home, "evil.json").writeText(org.json.JSONObject().put("name", "evil").put("items", org.json.JSONArray()).toString())
        assertEquals(null, runCatching { shelf.template("../evil") }.getOrNull())
        File(home, "templates/odd.json").writeText(org.json.JSONObject().put("name", "line\nbreak").put("items", org.json.JSONArray()).toString())
        assertEquals(listOf("Morning Run"), shelf.templates())
        // A dropped file whose name isn't its own file's isn't listed: everything listed reads back by its name.
        File(home, "templates/odd2.json").writeText(org.json.JSONObject().put("name", "Dropped").put("items", org.json.JSONArray()).toString())
        assertEquals(listOf("Morning Run"), shelf.templates())
        assertTrue(shelf.templates().all { shelf.template(it) != null })
    }

    @Test fun onlyShelfWordsLookForTheShelfAndNoneOfItHappensOnTheWindowsThread() {
        val home = Files.createTempDirectory("soma-shelf-door").toFile()
        val window = ScreenCanvas(File(home, "window.json"), "desk-macbook", 12, 8, "macbook").also { it.load() }
        val inner = FileShelf(home)
        var looked = 0
        var onWindowNow = false
        var calledOnWindow = 0
        val watched = object : dev.omalauncher.canvas.CanvasShelf {
            fun seen() { if (onWindowNow) calledOnWindow++ }
            override fun template(name: String) = inner.template(name).also { seen() }
            override fun keepTemplate(name: String, text: String) { seen(); inner.keepTemplate(name, text) }
            override fun templates() = inner.templates().also { seen() }
            override fun layout() = inner.layout().also { seen() }
            override fun remember(line: String) { seen(); inner.remember(line) }
        }
        val door = CanvasDoor(shelf = { looked++; watched }) { work -> onWindowNow = true; try { work(window) } finally { onWindowNow = false } }
        val token = door.mint("r1")
        fun say(vararg words: String) = org.json.JSONObject(door.handle("canvas $token " + org.json.JSONArray(words.toList())))
        for (w in listOf(listOf("board"), listOf("modules"), listOf("place", "needs", "card", "top-left"), listOf("undo"))) say(*w.toTypedArray())
        assertEquals("canvas words that aren't the shelf's never look for it", 0, looked)
        assertTrue(say("place", "needs", "card", "top-left").getBoolean("ok"))
        assertTrue(say("template-save", "Desk").getBoolean("ok"))
        assertTrue(say("remember", "Needs you top left").getBoolean("ok"))
        assertTrue(say("template-apply", "Desk", "--page", "Two").getBoolean("ok"))
        assertTrue(say("layout").getBoolean("ok"))
        assertEquals(4, looked)
        assertEquals("the shelf is read and written off the window's thread", 0, calledOnWindow)
        // A shelf that can't keep it says what wasn't kept.
        val broken = object : dev.omalauncher.canvas.CanvasShelf by watched {
            override fun keepTemplate(name: String, text: String) = throw dev.omalauncher.canvas.AskRefused("the service said 503")
            override fun remember(line: String) = throw dev.omalauncher.canvas.AskRefused("the service didn't answer")
        }
        val door2 = CanvasDoor(shelf = { broken }) { work -> work(window) }
        val t2 = door2.mint("r2")
        fun say2(vararg words: String) = org.json.JSONObject(door2.handle("canvas $t2 " + org.json.JSONArray(words.toList())))
        assertTrue(say2("template-save", "Other").getString("error").contains("the template wasn't kept"))
        assertTrue(say2("remember", "Something new").getString("error").contains("LAYOUT.md wasn't changed"))
    }

    @Test fun theCarsOwnScreenTakesATemplateOntoTheOnePageItShows() {
        val home = Files.createTempDirectory("soma-shelf-car").toFile()
        val shelf = FileShelf(home)
        val fold = ScreenCanvas(File(home, "screens/fold-q5.json"), "fold-q5", 6, 8, "macpro").also { it.load() }
        assertTrue(CanvasCommands.run("place needs card top-left".split(' '), fold, "r1", "agent:t", shelf).getBoolean("ok"))
        assertTrue(CanvasCommands.run("template-save Drive".split(' '), fold, "r2", "agent:t", shelf).getBoolean("ok"))
        val car = ScreenCanvas(File(home, "screens/car.json"), CAR_SCREEN, CAR_COLUMNS, CAR_ROWS, "macpro").also { it.load() }
        val pages = car.page.pages.map { it.name }
        assertTrue(CanvasCommands.run("template-apply Drive".split(' '), car, "r3", "agent:t", shelf).getBoolean("ok"))
        assertEquals(pages, car.page.pages.map { it.name })
        assertTrue(car.page.pages[0].items.any { it.key.startsWith("module:needs") })
    }

    @Test fun theServicesShelfRoutesTakeOnlyNamesTemplatesAndVisibleLinesAndSayWhoChangedIt() {
        val home = Files.createTempDirectory("soma-shelf-routes").toFile().also { System.setProperty("soma.home", it.path) }
        val key = "macbook-key-" + "y".repeat(40)
        val keys = File(home, "keys.json").apply { writeText(org.json.JSONObject().put("devices", org.json.JSONArray(listOf(org.json.JSONObject().put("name", "macbook").put("digest", keyDigest(key))))).toString()) }
        val source = object : dev.omalauncher.canvas.ModuleSource {
            override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
            override fun asks() = emptyList<dev.omalauncher.canvas.ModuleAsk>()
            override fun answer(ask: dev.omalauncher.canvas.ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
        }
        val service = SomaService(home, PairedDevices(keys) { "macbook" }, source, dev.omalauncher.widgets.runtime.CommandGate(File(home, "approved.json"), "macpro"), "macpro")
        val server = service.open("127.0.0.1", 0)
        fun call(method: String, path: String, body: org.json.JSONObject?): Int {
            val c = (java.net.URI("http://127.0.0.1:${server.address.port}$path").toURL().openConnection() as java.net.HttpURLConnection)
            c.requestMethod = method; c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            return c.responseCode
        }
        val good = org.json.JSONObject().put("name", "Desk").put("items", org.json.JSONArray()).toString()
        assertTrue(call("PUT", "/shelf/templates/a%2F..%2Fb", org.json.JSONObject().put("text", good)) in setOf(400, 404))
        assertEquals(400, call("PUT", "/shelf/templates/a..b", org.json.JSONObject().put("text", good)))
        assertEquals(400, call("PUT", "/shelf/templates/Desk", org.json.JSONObject().put("text", "not json")))
        assertEquals(400, call("PUT", "/shelf/templates/Desk", org.json.JSONObject().put("text", org.json.JSONObject().put("name", "Other").put("items", org.json.JSONArray()).toString())))
        assertEquals(200, call("PUT", "/shelf/templates/Desk", org.json.JSONObject().put("text", good)))
        for (bad in listOf("a\u2028b", "a\u200Bb", "x".repeat(201), "")) assertEquals(bad, 400, call("POST", "/shelf/layout", org.json.JSONObject().put("line", bad)))
        assertEquals(200, call("POST", "/shelf/layout", org.json.JSONObject().put("line", "Handoff is wide")))
        // A preference already there changes nothing and isn't logged again.
        assertEquals(200, call("POST", "/shelf/layout", org.json.JSONObject().put("line", "Handoff is wide")))
        val log = File(home, "shelf.log").readLines().map { org.json.JSONObject(it) }
        assertEquals(listOf("macbook", "macbook"), log.map { it.getString("actor") })
        assertEquals(listOf("template-save", "remember"), log.map { it.getJSONArray("words").getString(0) })
        // The canvas words through the door are logged with who said them; a repeat isn't.
        fun canvas(vararg words: String) = call("POST", "/screens/car/canvas", org.json.JSONObject().put("words", org.json.JSONArray(words.toList())))
        assertEquals(200, canvas("place", "needs", "card"))
        assertEquals(200, canvas("template-save", "Car page"))
        assertEquals(200, canvas("remember", "Pocket is a line"))
        assertEquals(200, canvas("remember", "Pocket is a line"))
        val more = File(home, "shelf.log").readLines().drop(2).map { org.json.JSONObject(it) }
        assertEquals(listOf("agent:macbook" to "template-save", "agent:macbook" to "remember"), more.map { it.getString("actor") to it.getJSONArray("words").getString(0) })
        assertEquals(400, call("POST", "/shelf/layout", org.json.JSONObject().put("line", "zz" + String(Character.toChars(0xE0041)) + "yy")))
        server.stop(0)
    }

    @Test fun anotherDesktopsSomaUsesTheServicesShelfAndTheWindowNeverWaitsOnIt() {
        val home = Files.createTempDirectory("soma-shelf-remote").toFile().also { System.setProperty("soma.home", it.path) }
        val key = "macbook-key-" + "y".repeat(40)
        val keys = File(home, "keys.json").apply { writeText(org.json.JSONObject().put("devices", org.json.JSONArray(listOf(org.json.JSONObject().put("name", "macbook").put("digest", keyDigest(key))))).toString()) }
        val source = object : dev.omalauncher.canvas.ModuleSource {
            override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
            override fun asks() = emptyList<dev.omalauncher.canvas.ModuleAsk>()
            override fun answer(ask: dev.omalauncher.canvas.ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
        }
        val service = SomaService(home, PairedDevices(keys) { "macbook" }, source, dev.omalauncher.widgets.runtime.CommandGate(File(home, "approved.json"), "macpro"), "macpro")
        val server = service.open("127.0.0.1", 0)
        val remote = RemoteShelf("http://127.0.0.1:${server.address.port}", key)
        // The MacBook's window, its front agent's door handing words over with the shelf on the Mac Pro.
        val window = ScreenCanvas(File(home, "window.json"), "desk-macbook", 12, 8, "macbook").also { it.load() }
        var onWindow = 0
        val door = CanvasDoor(shelf = { remote }) { work -> onWindow++; work(window) }
        val token = door.mint("r1")
        fun say(vararg words: String) = org.json.JSONObject(door.handle("canvas $token " + org.json.JSONArray(words.toList())))
        assertTrue(say("place", "needs", "card", "top-left").getBoolean("ok"))
        assertTrue(say("template-save", "From the MacBook").toString(), say("template-save", "From the MacBook", "--replace").getBoolean("ok"))
        assertEquals(listOf("From the MacBook"), service.shelf.templates())
        assertTrue(say("remember", "Needs you top left").getBoolean("ok"))
        assertTrue(service.shelf.layout().contains("- Needs you top left"))
        assertTrue(say("template-apply", "From the MacBook", "--page", "Again").getBoolean("ok"))
        assertTrue(window.page.pages.any { it.name == "Again" })
        // A wrong key, or a name that isn't one, gets nothing from the service.
        assertEquals(null, runCatching { RemoteShelf("http://127.0.0.1:${server.address.port}", "nope-" + "z".repeat(40)).templates() }.getOrNull())
        val probe = (java.net.URI("http://127.0.0.1:${server.address.port}/shelf/templates/..%2Fkeys").toURL().openConnection() as java.net.HttpURLConnection).apply { setRequestProperty("Authorization", "Bearer $key") }
        assertTrue(probe.responseCode in setOf(400, 404))
        assertFalse((probe.errorStream?.use { String(it.readBytes()) }).orEmpty().contains("digest"))
        // The service gone: the agent is told, the window untouched.
        server.stop(0)
        val before = window.log.version
        val gone = say("layout")
        assertFalse(gone.getBoolean("ok"))
        assertTrue(gone.getString("error").contains("didn't answer"))
        assertEquals(before, window.log.version)
    }
}
