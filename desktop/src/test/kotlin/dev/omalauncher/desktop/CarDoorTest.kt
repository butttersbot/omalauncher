package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.widgets.runtime.CommandGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The car's door (Soma Canvas slice 9a): from outside the tailnet, an unpaired browser gets nothing; a car paired with a
 * code shown on one of his devices gets the car's screen and sees a change within about a second; a tap made on a frame
 * that is no longer the current one is dropped.
 */
class CarDoorTest {
    private val home = Files.createTempDirectory("soma-car").toFile().also { System.setProperty("soma.home", it.path) }
    private val foldKey = "fold-key-" + "x".repeat(40)
    private val keys = File(home, "keys.json").apply {
        writeText(JSONObject().put("devices", JSONArray(listOf(JSONObject().put("name", "fold").put("digest", keyDigest(foldKey))))).toString())
    }
    @Volatile private var threads = emptyList<dev.omalauncher.assist.AgentThread>()
    private val source = object : ModuleSource {
        override fun threads() = this@CarDoorTest.threads
        override fun asks() = emptyList<ModuleAsk>()
        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
    }
    private val service = SomaService(home, PairedDevices(keys) { "fold" }, source, CommandGate(File(home, "approved.json"), "macpro"), "macpro")
    private val tailnet = service.open("127.0.0.1", 0)
    private val carDoor = service.car.open("127.0.0.1", 0)
    private val inside = "http://127.0.0.1:${tailnet.address.port}"
    private val outside = "http://127.0.0.1:${carDoor.address.port}"

    /** An answer, its headers found by name whatever their case (the JDK server writes "Set-cookie"). */
    private class Answer(val code: Int, val body: ByteArray, private val raw: Map<String, List<String>>) {
        val headers: Map<String, List<String>> get() = raw.mapKeys { it.key }.let { m -> object : AbstractMap<String, List<String>>() {
            override val entries get() = m.entries
            override fun get(key: String) = m.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        } }
    }

    private fun call(url: String, method: String = "GET", key: String? = null, cookie: String? = null, body: String? = null, form: Boolean = false): Answer {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        key?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        cookie?.let { c.setRequestProperty("Cookie", "soma_car=$it") }
        if (body != null) {
            c.doOutput = true
            if (form) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val data = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Answer(code, data, c.headerFields.filterKeys { it != null })
    }

    private fun pairCar(): String {
        val code = JSONObject(String(call("$inside/car/pairing", "POST", foldKey, body = "{}").body)).getString("code")
        val paired = call("$outside/car/pair", "POST", body = "code=$code", form = true)
        assertEquals(303, paired.code)
        return paired.headers["Set-Cookie"]!!.single().substringAfter("soma_car=").substringBefore(';')
    }

    @Test fun anUnpairedBrowserGetsNothingButTheFormToPair() {
        val page = call("$outside/")
        assertEquals(200, page.code)
        assertTrue(String(page.body).contains("action=\"/car/pair\""))
        for ((m, p) in listOf("GET" to "/car/frame", "GET" to "/car/events", "POST" to "/car/tap", "GET" to "/health", "GET" to "/screens")) assertEquals("$m $p", 401, call("$outside$p", m, body = if (m == "POST") "{}" else null).code)
        // The tailnet door's key opens nothing here, and the car's door serves nothing of the tailnet's.
        assertEquals(401, call("$outside/car/frame", key = foldKey).code)
        assertEquals(401, call("$outside/car/frame", cookie = foldKey).code)
        stop()
    }

    @Test fun aCodeShownOnHisDevicePairsTheCarOnceAndWrongCodesGetNowhere() {
        // No code on show: nothing pairs.
        assertEquals("/?wrong=1", call("$outside/car/pair", "POST", body = "code=ABCDEFGH", form = true).headers["Location"]!!.single())
        val code = JSONObject(String(call("$inside/car/pairing", "POST", foldKey, body = "{}").body)).getString("code")
        assertEquals(8, code.length)
        assertEquals("/?wrong=1", call("$outside/car/pair", "POST", body = "code=WRONGONE", form = true).headers["Location"]!!.single())
        val paired = call("$outside/car/pair", "POST", body = "code=${code.lowercase()}", form = true)
        val cookie = paired.headers["Set-Cookie"]!!.single()
        assertTrue(cookie, cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Strict"))
        // Once only.
        assertEquals("/?wrong=1", call("$outside/car/pair", "POST", body = "code=$code", form = true).headers["Location"]!!.single())
        // Kept as a digest only.
        val key = cookie.substringAfter("soma_car=").substringBefore(';')
        assertFalse(File(home, "car-keys.json").readText().contains(key))
        // A car key opens nothing on the tailnet door.
        assertEquals(401, call("$inside/health", key = key).code)
        stop()
    }

    @Test fun guessingCodesIsCutShort() {
        call("$inside/car/pairing", "POST", foldKey, body = "{}")
        val answers = (1..15).map { call("$outside/car/pair", "POST", body = "code=GUESS$it", form = true).code }
        assertTrue(answers.toString(), answers.take(10).all { it == 303 } && answers.drop(10).all { it == 429 })
        stop()
    }

    @Test fun aPairedCarSeesTheCarsScreenAndAChangeWithinASecond() {
        val car = pairCar()
        assertTrue(String(call("$outside/", cookie = car).body).contains("EventSource('/car/events')"))
        val first = call("$outside/car/frame", cookie = car)
        assertEquals(200, first.code)
        val image = ImageIO.read(first.body.inputStream())
        assertEquals(FRAME_WIDTH to FRAME_HEIGHT, image.width to image.height)
        // The event stream says the frame now, and again when the screen changes.
        val events = (URI("$outside/car/events").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Cookie", "soma_car=$car"); readTimeout = 5000 }
        val reader = events.inputStream.bufferedReader()
        val now = generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ").toLong()
        val asked = System.currentTimeMillis()
        assertTrue(JSONObject(String(call("$inside/screens/car/canvas", "POST", foldKey, body = JSONObject().put("words", JSONArray(listOf("place", "needs", "card", "top-left"))).toString()).body)).getBoolean("ok"))
        val next = generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ").toLong()
        val took = System.currentTimeMillis() - asked
        assertTrue("$next after $now", next > now)
        assertTrue("the car saw the change after $took ms", took < 1_500)
        events.disconnect()
        stop()
    }

    @Test fun aTapOnAFrameThatIsNoLongerShownIsDropped() {
        val car = pairCar()
        val shown = call("$outside/car/frame", cookie = car).headers["X-Frame"]!!.single().toLong()
        call("$inside/screens/car/canvas", "POST", foldKey, body = JSONObject().put("words", JSONArray(listOf("place", "threads", "card"))).toString())
        service.car.refresh()
        val stale = call("$outside/car/tap", "POST", cookie = car, body = JSONObject().put("frame", shown).put("x", 100).put("y", 100).toString())
        assertEquals(409, stale.code)
        val current = JSONObject(String(stale.body)).getLong("frame")
        assertNotEquals(shown, current)
        val fresh = call("$outside/car/tap", "POST", cookie = car, body = JSONObject().put("frame", current).put("x", 100).put("y", 100).toString())
        assertEquals(200, fresh.code)
        // Off the frame: dropped too.
        assertEquals(409, call("$outside/car/tap", "POST", cookie = car, body = JSONObject().put("frame", JSONObject(String(fresh.body)).getLong("frame")).put("x", -5).put("y", 100).toString()).code)
        stop()
    }

    @Test fun aRevokedCarGetsNothingAndANewPairingReplacesTheOld() {
        val first = pairCar()
        val second = pairCar()
        assertEquals(401, call("$outside/car/frame", cookie = first).code)
        assertEquals(200, call("$outside/car/frame", cookie = second).code)
        // Unpairing ends a watching car's stream at once.
        val watching = (URI("$outside/car/events").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Cookie", "soma_car=$second"); readTimeout = 5000 }.inputStream.bufferedReader()
        watching.readLine()
        val unpaired = System.currentTimeMillis()
        assertEquals(200, call("$inside/car/pairing", "DELETE", foldKey).code)
        generateSequence { watching.readLine() }.count()
        assertTrue(System.currentTimeMillis() - unpaired < 3_000)
        assertEquals(401, call("$outside/car/frame", cookie = second).code)
        assertFalse(JSONObject(String(call("$inside/car", key = foldKey).body)).getBoolean("paired"))
        stop()
    }

    @Test fun hisDesktopsTrayGivesTheCodeTheCarTypesAndUnpairsIt() {
        // A runner on the loopback that says where the canvas service is, as his runners do.
        val runner = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { ex ->
                val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $foldKey"
                val body = (if (ok) JSONObject().put("ok", true).put("soma", inside) else JSONObject().put("ok", false)).toString().toByteArray()
                ex.sendResponseHeaders(if (ok) 200 else 401, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val machines = listOf(dev.omalauncher.assist.MacMachine("m", "Mac Pro", dev.omalauncher.assist.MacRunnerConfig("http://127.0.0.1:${runner.address.port}", foldKey)))
        val said = CarPairing.start(machines)
        val code = Regex("Type ([A-Z0-9]{8}) into").find(said)?.groupValues?.get(1)
        assertTrue(said, code != null)
        val paired = call("$outside/car/pair", "POST", body = "code=$code", form = true)
        val car = paired.headers["Set-Cookie"]!!.single().substringAfter("soma_car=").substringBefore(';')
        assertEquals(200, call("$outside/car/frame", cookie = car).code)
        assertTrue(CarPairing.end(machines).startsWith("The car is unpaired"))
        assertEquals(401, call("$outside/car/frame", cookie = car).code)
        // No runner that says where a service is: said, nothing paired.
        assertTrue(CarPairing.start(emptyList()).startsWith("No canvas service"))
        runner.stop(0)
        stop()
    }

    @Test fun theCarPageCanShowItsFramesAndKeepsItsStreamWithoutReloading() {
        val car = pairCar()
        val page = call("$outside/", cookie = car)
        val csp = page.headers["Content-Security-Policy"]!!.single()
        // The page shows frames as blob: images: its policy must allow them (round-1 review: Chromium showed nothing).
        assertTrue(String(page.body).contains("URL.createObjectURL") && csp.contains("img-src 'self' blob:"))
        // It never reloads on a blip; only when the door has closed its stream for good, or turns it away (unpaired).
        val js = String(page.body)
        // A stream closed for good (the door down behind Funnel, or unpaired) waits until the door answers again, then reloads:
        // never into a proxy's error page (round-3 review: a restart left the car on "Bad Gateway").
        assertTrue(js.contains("if(r.status===200||r.status===401)location.reload()") && !js.contains("readyState===2)location.reload()") && !js.contains("setTimeout(()=>location.reload()"))
        assertTrue(js.contains("if(r.status!==200)return;") && js.contains("if(r.status===401){location.reload();return null}"))
        // A paired car's own requests aren't stopped by the door-wide cap; anyone else's are, from the same address too.
        repeat(250) { call("$outside/car/frame", cookie = car) }
        assertEquals(200, call("$outside/car/frame", cookie = car).code)
        // A wrong code is said.
        assertTrue(String(call("$outside/?wrong=1").body).contains("That code is not right"))
        // One stream at a time: a new one ends the one before.
        fun open() = (URI("$outside/car/events").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Cookie", "soma_car=$car"); readTimeout = 5000 }
        val first = open().inputStream.bufferedReader()
        assertEquals("retry: 1000", first.readLine())
        first.readLine(); assertTrue(first.readLine().startsWith("data: "))
        val second = open().inputStream.bufferedReader()
        assertEquals("retry: 1000", second.readLine())
        val started = System.currentTimeMillis()
        // The first stream ends (whatever it was still saying), within a couple of seconds.
        generateSequence { first.readLine() }.count()
        assertTrue(System.currentTimeMillis() - started < 4_000)
        // A code that isn't even well-formed is a wrong code.
        assertEquals("/?wrong=1", call("$outside/car/pair", "POST", body = "code=%ZZ", form = true).headers["Location"]!!.single())
        assertEquals(204, call("$outside/favicon.ico").code)
        // Anyone else's requests are held to the cap, even from the car's own address; the car's still go through.
        repeat(250) { call("$outside/car/frame") }
        assertEquals(429, call("$outside/car/frame").code)
        assertEquals(200, call("$outside/car/frame", cookie = car).code)
        stop()
    }

    @Test fun whatAModuleShowsReachesTheCarWithoutAnythingMovingAndTapsMadeTogetherLandOnce() {
        val car = pairCar()
        assertTrue(JSONObject(String(call("$inside/screens/car/canvas", "POST", foldKey, body = JSONObject().put("words", JSONArray(listOf("place", "threads", "card", "top-left"))).toString()).body)).getBoolean("ok"))
        val before = service.car.refresh()
        threads = listOf(dev.omalauncher.assist.AgentThread("mac:t1", "Deploy the site", dev.omalauncher.assist.ThreadState.Working, "building", "working"))
        val after = service.car.refresh()
        assertTrue("the car's frame changed when a thread started ($before → $after)", after > before)
        // Six taps made on the same frame at once: one lands, the others are dropped.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val codes = (1..6).map { pool.submit<Int> { call("$outside/car/tap", "POST", cookie = car, body = JSONObject().put("frame", after).put("x", 900).put("y", 700).toString()).code } }.map { it.get() }
        assertEquals(codes.toString(), 1, codes.count { it == 200 })
        stop()
    }

    @Test fun theServiceSaysWhenItsPublicDoorStopsAnswering() {
        var answering = 200
        val live = PublicLiveness("https://macpro.example.ts.net") { answering }
        live.check()
        assertEquals(true, live.up)
        answering = 502
        live.check()
        assertEquals(false, live.up)
        service.liveness = live
        assertEquals(false, JSONObject(String(call("$inside/health", key = foldKey).body)).getJSONObject("public").getBoolean("up"))
        stop()
    }

    private fun stop() {
        tailnet.stop(0)
        carDoor.stop(0)
    }
}
