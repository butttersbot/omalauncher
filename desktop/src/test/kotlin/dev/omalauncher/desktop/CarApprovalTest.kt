package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.widgets.runtime.CommandGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Approvals from the car (Soma Canvas slice 9b): approve on the car's Needs you goes through the same answer the other
 * screens use (the ticket path), only from a tap made on the frame the car was shown; each is logged with the car's id.
 */
class CarApprovalTest {
    private val home = Files.createTempDirectory("soma-car-ok").toFile().also { System.setProperty("soma.home", it.path) }
    private val foldKey = "fold-key-" + "x".repeat(40)
    private val keys = File(home, "keys.json").apply {
        writeText(JSONObject().put("devices", JSONArray(listOf(JSONObject().put("name", "fold").put("digest", keyDigest(foldKey))))).toString())
    }

    /** The runner's asks as every screen reads them: one waits until it is answered. */
    private val ask = ModuleAsk("a1", "run the deploy", "deploy.sh", "mac:t1", "Mac Pro",
        dev.omalauncher.assist.MacPending("t1", "Deploy", dev.omalauncher.assist.MacApproval("ap1", "n1", "Bash", "deploy.sh", "deploys"), "ticket-1", null))
    /** A widget's command, gated, not ticketed: the car never answers it. */
    private val widgetAsk = ModuleAsk("w1", "stamp.owl wants to run", "date", "", "Mac Pro", Any(), wholeDetail = true)
    private val answers = ArrayList<Pair<String, Boolean>>()
    @Volatile private var waiting = true
    /** Whether an approved ask stays shown (an answer the runner hasn't taken yet): the frame doesn't change after it. */
    @Volatile private var stays = false
    private val source = object : ModuleSource {
        override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
        override fun asks() = if (waiting) listOf(ask) else emptyList()
        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) {
            synchronized(answers) { answers += ask.id to allow }
            if (allow && !stays) waiting = false
            said(null)
        }
    }
    private val service = SomaService(home, PairedDevices(keys) { "fold" }, source, CommandGate(File(home, "approved.json"), "macpro"), "macpro")

    private fun pair(): String {
        val code = service.car.startPairing("fold").first
        return service.car.pair(code)!!
    }

    @Test fun aStaleTapOnApproveIsDroppedAndACurrentOneApprovesForEveryScreenLoggedWithTheCar() {
        val car = pair()
        assertTrue(service.car.isCar(car))
        assertTrue(CanvasCommandsRun(service, "place needs card top-left"))
        // Where approve is on the car's frame: found as a finger would, tap by tap across Needs you, each on the current frame.
        val cell = service.screen(CAR_SCREEN)!!.page.pages[0].items.first { it.key == "module:needs" }.cell!!
        val gap = 8f
        val cw = (FRAME_WIDTH - gap * (CAR_COLUMNS + 1)) / CAR_COLUMNS
        val ch = (FRAME_HEIGHT - gap * (CAR_ROWS + 1)) / CAR_ROWS
        val left = gap + (cw + gap) * cell.x
        val top = gap + (ch + gap) * cell.y
        var approveAt: Pair<Float, Float>? = null
        search@ for (y in generateSequence(top + 4f) { it + 10f }.takeWhile { it < top + ch * cell.h }) {
            for (x in generateSequence(left + 4f) { it + 14f }.takeWhile { it < left + cw * cell.w }) {
                val (done, _) = service.car.tap(service.car.frame().first, x, y)
                assertTrue(done)
                if (synchronized(answers) { answers.any { it.second } }) { approveAt = x to y; break@search }
            }
        }
        assertNotNull("no approve on the car's Needs you", approveAt)
        val (x, y) = approveAt!!
        // The ask comes back; the car is shown it.
        synchronized(answers) { answers.clear() }
        waiting = true
        val shown = service.car.refresh()
        // Then the screen changes before the car's tap arrives: that tap is dropped and nothing is answered.
        assertTrue(CanvasCommandsRun(service, "place threads card bottom-right"))
        service.car.refresh()
        val (stale, current) = service.car.tap(shown, x, y)
        assertEquals(false, stale)
        assertTrue(current != shown)
        assertEquals(emptyList<Pair<String, Boolean>>(), synchronized(answers) { answers.toList() })
        // On the current frame it approves, through the same answer every screen uses: the ask is gone for all of them.
        val (done, after) = service.car.tap(current, x, y)
        assertTrue(done)
        assertEquals(listOf("a1" to true), synchronized(answers) { answers.toList() })
        assertTrue(source.asks().isEmpty())
        assertTrue("the car's frame shows it answered", after != current)
        // Logged with the car's id, never its key.
        val log = File(home, "car-approvals.log").readLines().map(::JSONObject)
        val line = log[log.size - 2]
        assertEquals(service.car.carId(), line.getString("car"))
        assertEquals("a1", line.getString("ask"))
        assertEquals(true, line.getBoolean("allow"))
        assertEquals(current, line.getLong("frame"))
        assertTrue(File(home, "car-approvals.log").readText().contains(car).not())
        // Two lines per answer: the tap, then what came of it; the car named by its id, never its key.
        assertFalse(line.has("said"))
        assertEquals("done", log.last().getString("said"))
        assertEquals(current, log.last().getLong("frame"))
        assertTrue(line.getString("car"), Regex("car-[0-9a-f]{12}").matches(line.getString("car")))
        // Unpaired, a tap answers nothing.
        waiting = true
        synchronized(answers) { answers.clear() }
        service.car.revoke()
        val (unpaired, _) = service.car.tap(service.car.refresh(), x, y)
        assertEquals(false, unpaired)
        assertEquals(emptyList<Pair<String, Boolean>>(), synchronized(answers) { answers.toList() })
    }

    /** Approve's place on the car's frame, found tap by tap across Needs you, each on the current frame. */
    private fun findApprove(): Pair<Float, Float> {
        val cell = service.screen(CAR_SCREEN)!!.page.pages[0].items.first { it.key == "module:needs" }.cell!!
        val gap = 8f
        val cw = (FRAME_WIDTH - gap * (CAR_COLUMNS + 1)) / CAR_COLUMNS
        val ch = (FRAME_HEIGHT - gap * (CAR_ROWS + 1)) / CAR_ROWS
        val left = gap + (cw + gap) * cell.x
        val top = gap + (ch + gap) * cell.y
        for (y in generateSequence(top + 4f) { it + 10f }.takeWhile { it < top + ch * cell.h })
            for (x in generateSequence(left + 4f) { it + 14f }.takeWhile { it < left + cw * cell.w }) {
                service.car.tap(service.car.frame().first, x, y)
                if (synchronized(answers) { answers.any { it.second } }) return x to y
            }
        error("no approve on the car's Needs you")
    }

    @Test fun aDoubleTapApprovesOnceAndOnlyTicketsAreAnsweredFromTheCarAndOnlyWhenRecorded() {
        pair()
        assertTrue(CanvasCommandsRun(service, "place needs card top-left"))
        val (x, y) = findApprove()
        synchronized(answers) { answers.clear() }
        waiting = true
        // Two taps made on the same frame: the first lands; the frame after it is always new (even when the picture is the
        // same, as when the ask stays shown), so the second is dropped.
        stays = true
        val shown = service.car.refresh()
        val results = listOf(java.util.concurrent.Executors.newFixedThreadPool(2).let { pool ->
            listOf(pool.submit<Pair<Boolean, Long>> { service.car.tap(shown, x, y) }, pool.submit<Pair<Boolean, Long>> { service.car.tap(shown, x, y) }).map { it.get() }
        }).flatten()
        assertEquals(results.toString(), 1, results.count { it.first })
        assertEquals(listOf("a1" to true), synchronized(answers) { answers.toList() })
        stays = false
        // Answered outside a car's tap: nothing.
        var said: String? = "unset"
        service.car.carSource.answer(ask, true) { said = it }
        assertEquals(null, said)
        assertEquals(1, synchronized(answers) { answers.size })
        // A widget's command is approved on a paired device, never from the car.
        val log = File(home, "car-approvals.log")
        log.delete()
        assertTrue(service.car.tapAs { service.car.carSource.answer(widgetAsk, true) { said = it } })
        assertEquals("Approve this from your phone or desktop", said)
        assertEquals(1, synchronized(answers) { answers.size })
        // If the car's answer can't be recorded, it isn't made.
        log.mkdirs()
        assertTrue(service.car.tapAs { service.car.carSource.answer(ask, false) { said = it } })
        assertTrue(said!!.startsWith("Soma couldn't record"))
        assertEquals(1, synchronized(answers) { answers.size })
    }

    @Test fun aTapOnAWidgetFromTheCarRunsNothing() {
        pair()
        val text = "widget \"knock\" {\n  size: 8x5\n  Column {\n    fill: true\n    Button { text: \"knock\"; onTap: run(\"date\") }\n    Text { text: \"knock\"; onTap: run(\"date\") }\n  }\n}\n"
        File(home, "widgets").mkdirs()
        File(home, "widgets/knock.owl").writeText(text)
        service.gate.approveFile("knock.owl", java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
        val screen = service.screen(CAR_SCREEN)!!
        assertEquals(null, screen.apply(dev.omalauncher.widgets.layout.CanvasVerb.Place("knock.owl", dev.omalauncher.widgets.layout.ItemKind.Widget, screen.page.pages.first().id, dev.omalauncher.widgets.Cell(0, 0, 8, 5))))
        service.car.refresh()
        assertTrue("the widget runs on the car", service.widget("knock.owl") != null)
        for (y in listOf(20f, 60f, 200f, 400f)) for (x in listOf(20f, 100f, 300f, 640f)) service.car.tap(service.car.refresh(), x, y)
        // A command a widget runs is asked about on the widgets' own thread: given time to, none is.
        Thread.sleep(2_000)
        service.car.refresh()
        assertTrue(service.gate.commandAsks().toString(), service.gate.commandAsks().isEmpty())
    }

    private fun CanvasCommandsRun(service: SomaService, words: String): Boolean =
        dev.omalauncher.canvas.CanvasCommands.run(words.split(' '), service.screen(CAR_SCREEN)!!, "test-${System.nanoTime()}", "agent:test").getBoolean("ok")
}
