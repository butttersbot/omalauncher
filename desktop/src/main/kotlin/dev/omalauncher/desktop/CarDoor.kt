package dev.omalauncher.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * The car's door (Soma Canvas slice 9a, PRD §3 and §6). The car's browser can't join the tailnet, so this is the one public
 * door: a port on the loopback that Tailscale Funnel publishes (enabling Funnel is Boss's to do). It serves the car page and
 * nothing else: frames out, taps in. The car is paired once, with a one-time code shown on one of his paired devices and
 * typed into the car page; that gives the car a key of its own (a cookie), kept here as a digest, replaced at each pairing
 * and revocable from any of his devices. A car key opens nothing on the tailnet door, and nothing of the tailnet door's
 * opens anything here. A tap carries the frame it was made on: one on a frame that is no longer the current one is
 * dropped, and the current frame is sent instead, so a re-render can never move a tap onto something else.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CarDoor(
    private val service: SomaService,
    private val home: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    /** The pairing code on show, the device showing it, and until when; one at a time. */
    private data class Code(val code: String, val by: String, val until: Long, var wrong: Int = 0)
    @Volatile private var code: Code? = null

    private val keysFile get() = File(home, "car-keys.json")

    /** A code to show on [device] for ten minutes; a new one replaces the last. Letters and digits nobody mistakes. */
    @Synchronized fun startPairing(device: String): Pair<String, Long> {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val c = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val until = now() + 10 * 60_000
        code = Code(c, device, until)
        return c to until
    }

    /**
     * The car's key for the code it typed: once, while the code is shown, and only the code shown. Five wrong tries void it.
     * The new key replaces any older car's key (one car, rotated at each pairing).
     */
    @Synchronized fun pair(typed: String): String? {
        val c = code ?: return null
        if (now() > c.until) { code = null; return null }
        if (!MessageDigest.isEqual(c.code.toByteArray(), typed.trim().uppercase().toByteArray())) {
            if (++c.wrong >= 5) code = null
            return null
        }
        code = null
        val key = "soma-car-" + ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        writeKeys(JSONObject().put("car", JSONObject().put("digest", keyDigest(key)).put("paired", now()).put("by", c.by)))
        return key
    }

    /** The car's key forgotten: the car page shows the pairing form again. */
    @Synchronized fun revoke() {
        writeKeys(JSONObject())
        code = null
        // Its open stream ends now: the page comes back, is turned away, and shows the form within a second or two.
        stream = null
    }

    fun paired(): Boolean = runCatching { JSONObject(keysFile.readText()).optJSONObject("car") != null }.getOrDefault(false)

    private fun writeKeys(o: JSONObject) {
        home.mkdirs()
        val fresh = File(home, ".car-keys.json.new")
        fresh.writeText(o.toString())
        fresh.setReadable(false, false); fresh.setReadable(true, true); fresh.setWritable(false, false); fresh.setWritable(true, true)
        if (!fresh.renameTo(keysFile)) { keysFile.delete(); fresh.renameTo(keysFile) }
    }

    /** Whether [key] is the paired car's. */
    fun isCar(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        val digest = runCatching { JSONObject(keysFile.readText()).getJSONObject("car").getString("digest") }.getOrNull() ?: return false
        return MessageDigest.isEqual(digest.toByteArray(), keyDigest(key).toByteArray())
    }

    // Frames: one scene for the car's screen, kept, so a tap lands where the car saw it.

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "soma-car").apply { isDaemon = true } }
    private var scene: ImageComposeScene? = null
    @Volatile var frameId = 0L
        private set
    @Volatile private var png: ByteArray = ByteArray(0)
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val shownVersion = androidx.compose.runtime.mutableLongStateOf(0L)
    /**
     * When the scene was last drawn, read by the modules: what they show (threads, asks, computers) is read again at each
     * drawing, so a thread that starts or needs him shows on the car without anything being moved (round-1 review).
     */
    private val shownAt = androidx.compose.runtime.mutableLongStateOf(0L)

    /** Draws the car's screen again; a picture that changed is a new frame, and whoever listens is told. On the car's thread. */
    fun refresh(): Long = thread.submit<Long> { draw() }.get(30, TimeUnit.SECONDS)

    /** The drawing itself, on the car's thread; [anew] makes it a new frame even when the picture is the same. */
    private fun draw(anew: Boolean = false): Long {
        val screen = service.screen(CAR_SCREEN)!!
        // The screen's layout isn't state the scene sees change: its log's version is, so a change to it draws the screen anew.
        shownVersion.longValue = screen.log.version
        shownAt.longValue = System.currentTimeMillis()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val s = scene ?: ImageComposeScene(FRAME_WIDTH, FRAME_HEIGHT, Density(1f)) {
            androidx.compose.runtime.key(shownVersion.longValue) { ScreenView(service, screen, null, shownAt.longValue) }
        }.also { scene = it }
        val bytes = s.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        if (anew || !bytes.contentEquals(png)) {
            png = bytes
            frameId++
            listeners.forEach { runCatching { it(frameId) } }
        }
        return frameId
    }

    fun frame(): Pair<Long, ByteArray> { if (frameId == 0L) refresh(); return frameId to png }

    /**
     * A tap at ([x], [y]) on frame [made]: dropped when that frame is no longer the current one (the answer says the
     * current frame, to show instead); otherwise a press and a release there, as a finger would, and the new frame.
     */
    fun tap(made: Long, x: Float, y: Float): Pair<Boolean, Long> {
        if (x !in 0f..FRAME_WIDTH.toFloat() || y !in 0f..FRAME_HEIGHT.toFloat()) return false to frameId
        // Checked, played and drawn in one step on the car's thread, where frames are made: no new frame can come between
        // the check and the tap, and the frame after a tap is always a new one, so taps made together on one frame land
        // once (round-1 review: six landed).
        return thread.submit<Pair<Boolean, Long>> {
            val s = scene ?: return@submit false to frameId
            if (made != frameId) return@submit false to frameId
            s.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            s.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            // The finger lifts away: nothing stays looking pressed or hovered (round-2 review).
            s.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f))
            true to draw(anew = true)
        }.get(30, TimeUnit.SECONDS)
    }

    /** Looks again every half second while a car is watching, so a change shows on the car within about a second. */
    private val watching = ConcurrentHashMap.newKeySet<Any>()
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "soma-car-tick").apply { isDaemon = true } }.apply {
        scheduleAtFixedRate({ if (watching.isNotEmpty()) runCatching { refresh() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    // The public door itself.

    /** Recent tries by address (Funnel says the caller in X-Forwarded-For): pairing guesses and floods are turned away. */
    private val tries = ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun limited(address: String, per: Int): Boolean {
        val t = now()
        // Addresses not heard from in a minute are forgotten, so a scanner's spray doesn't pile up here.
        if (tries.size > 1000) tries.entries.removeIf { t - it.value.second > 60_000 }
        val (n, since) = tries.compute(address) { _, was -> if (was == null || t - was.second > 60_000) 1 to t else (was.first + 1) to was.second }!!
        return n > per && t - since <= 60_000
    }

    fun open(address: String, port: Int): HttpServer = HttpServer.create(InetSocketAddress(address, port), 32).apply {
        executor = Executors.newFixedThreadPool(16) { Thread(it, "soma-car-http").apply { isDaemon = true } }
        createContext("/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { text(ex, 500, "text/plain", "") } } }
        start()
    }

    private fun text(ex: HttpExchange, code: Int, type: String, body: String, headers: Map<String, String> = emptyMap()) = bytes(ex, code, type, body.toByteArray(), headers)

    private fun bytes(ex: HttpExchange, code: Int, type: String, data: ByteArray, headers: Map<String, String> = emptyMap()) {
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
        ex.responseHeaders.set("Content-Security-Policy", "default-src 'self'; img-src 'self' blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'")
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(code, if (data.isEmpty()) -1 else data.size.toLong())
        if (data.isNotEmpty()) ex.responseBody.use { it.write(data) } else ex.close()
    }

    private fun cookie(ex: HttpExchange): String? = ex.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }.map { it.trim() }
        .firstOrNull { it.startsWith("soma_car=") }?.removePrefix("soma_car=")

    private fun caller(ex: HttpExchange) = ex.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim()?.ifEmpty { null } ?: ex.remoteAddress.address.hostAddress

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val who = caller(ex)
        // The paired car's own frames, stream and taps aren't held to the door-wide cap (a busy screen fetches a frame
        // twice a second; round-2 review: the car went blank under it). Everyone else is.
        val carsOwn = path.startsWith("/car/") && path != "/car/pair" && isCar(cookie(ex))
        if (!carsOwn && limited(who, 240)) return text(ex, 429, "text/plain", "slow down")
        when {
            // Funnel's liveness: the service asks its own public address for this.
            ex.requestMethod == "GET" && path == "/car/health" -> text(ex, 200, "text/plain", "ok")
            ex.requestMethod == "GET" && path == "/favicon.ico" -> text(ex, 204, "text/plain", "")
            ex.requestMethod == "GET" && path == "/" -> text(ex, 200, "text/html; charset=utf-8", if (isCar(cookie(ex))) CAR_PAGE else PAIR_PAGE)
            ex.requestMethod == "POST" && path == "/car/pair" -> {
                if (limited("pair:$who", 10) || limited("pair", 60)) return text(ex, 429, "text/plain", "too many tries; wait a minute")
                val typed = String(ex.requestBody.readNBytes(256)).substringAfter("code=").substringBefore('&').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
                val key = pair(typed) ?: return text(ex, 303, "text/plain", "", mapOf("Location" to "/?wrong=1"))
                text(ex, 303, "text/plain", "", mapOf("Location" to "/", "Set-Cookie" to "soma_car=$key; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=31536000"))
            }
            // Everything below is the paired car's alone.
            !isCar(cookie(ex)) -> text(ex, 401, "text/plain", "")
            ex.requestMethod == "GET" && path == "/car/frame" -> {
                val (id, data) = frame()
                bytes(ex, 200, "image/png", data, mapOf("X-Frame" to id.toString()))
            }
            ex.requestMethod == "GET" && path == "/car/events" -> events(ex)
            ex.requestMethod == "POST" && path == "/car/tap" -> {
                val b = runCatching { JSONObject(String(ex.requestBody.readNBytes(1024))) }.getOrNull() ?: return text(ex, 400, "text/plain", "")
                val (done, current) = tap(b.optLong("frame", -1), b.optDouble("x", -1.0).toFloat(), b.optDouble("y", -1.0).toFloat())
                text(ex, if (done) 200 else 409, "application/json", JSONObject().put("ok", done).put("frame", current).toString())
            }
            else -> text(ex, 404, "text/plain", "")
        }
    }

    /** Server-sent events: the current frame's id now and at each change, a comment every 15 s so the car's browser keeps it open. */
    /** The one stream open now: a new one ends the one before (one car; a stream left by a lost connection never piles up). */
    @Volatile private var stream: Any? = null

    private fun events(ex: HttpExchange) {
        ex.responseHeaders.set("Content-Type", "text/event-stream")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val queue = java.util.concurrent.LinkedBlockingQueue<Long>()
        val listener: (Long) -> Unit = { queue.offer(it) }
        val token = Any()
        stream = token
        listeners += listener
        watching += token
        try {
            // The browser comes back in a second when a stream ends; each ends before the server's 60 s limit would cut it.
            out.write("retry: 1000\n\n".toByteArray())
            queue.offer(frame().first)
            val until = now() + STREAM_MS
            var quiet = 0L
            while (stream === token && now() < until) {
                val id = queue.poll(1, TimeUnit.SECONDS)
                if (id == null && now() - quiet < 15_000) continue
                quiet = now()
                out.write((if (id == null) ": still here\n\n" else "data: $id\n\n").toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            listeners -= listener
            watching -= token
            runCatching { out.close() }
        }
    }

    companion object {
        /** The public door's port on the loopback, for Funnel to publish. */
        const val CAR_PORT = 8482

        /** How long one event stream lasts: under the door's 60 s response limit (s8a), so it ends cleanly. */
        const val STREAM_MS = 50_000L

        private val PAIR_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Soma</title><style>body{background:#f6f2ea;color:#1d2a5c;font:28px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
            form{text-align:center}input{font:40px ui-monospace,monospace;letter-spacing:6px;width:9ch;text-align:center;padding:12px;border:3px solid #1d2a5c;border-radius:10px}
            button{font:28px system-ui;margin-top:24px;padding:12px 36px;border:0;border-radius:10px;background:#1d2a5c;color:#fff}</style></head>
            <body><form method="post" action="/car/pair"><p>Pair this car with Soma</p><p style="font-size:20px">Ask Soma for a pairing code on your phone or desk, then type it here.</p><p id="w" style="font-size:20px;color:#c33d41"></p><script>if(location.search.indexOf('wrong=1')>=0)document.getElementById('w').textContent='That code is not right or has expired. Ask for a new one.'</script>
            <input name="code" autocomplete="off" autocapitalize="characters" maxlength="8"><br><button>Pair</button></form></body></html>
        """.trimIndent()

        private val CAR_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Soma</title><style>html,body{margin:0;height:100%;background:#f6f2ea}img{width:100%;height:100%;object-fit:contain;touch-action:none}</style></head>
            <body><img id="f" alt=""><script>
            const img=document.getElementById('f');let shown=0,wait=0;
            function show(id){fetch('/car/frame',{cache:'no-store'}).then(r=>{if(r.status===401){location.reload();return}if(r.status!==200)return;const n=+r.headers.get('X-Frame');return r.blob().then(b=>{const old=img.src;img.src=URL.createObjectURL(b);shown=n;if(old.startsWith('blob:'))URL.revokeObjectURL(old)})}).catch(()=>{})}
            const es=new EventSource('/car/events');es.onmessage=e=>{if(+e.data!==shown)show(+e.data)};es.onopen=()=>show(0);es.onerror=()=>{if(es.readyState===2&&!wait)wait=setInterval(()=>fetch('/car/frame',{cache:'no-store'}).then(r=>{if(r.status===200||r.status===401)location.reload()}).catch(()=>{}),3000)};
            img.addEventListener('pointerup',e=>{const r=img.getBoundingClientRect(),s=Math.min(r.width/$FRAME_WIDTH,r.height/$FRAME_HEIGHT);
              const x=(e.clientX-r.left-(r.width-$FRAME_WIDTH*s)/2)/s,y=(e.clientY-r.top-(r.height-$FRAME_HEIGHT*s)/2)/s;
              fetch('/car/tap',{method:'POST',body:JSON.stringify({frame:shown,x:x,y:y})}).then(r=>{if(r.status===401){location.reload();return null}return r.json()}).then(a=>{if(a&&a.frame!==shown)show(a.frame)}).catch(()=>{})});
            </script></body></html>
        """.trimIndent()
    }
}

/**
 * Whether the public door is reachable from outside, as Funnel can stop answering without a word: its own public address
 * asked every minute. Up, down, and since when, for the tailnet door's /health to say.
 */
class PublicLiveness(private val url: String?, private val get: (String) -> Int = { u ->
    runCatching { (java.net.URI(u).toURL().openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }.responseCode }.getOrDefault(-1)
}) {
    @Volatile var up: Boolean? = null
        private set
    @Volatile var since: Long = 0
        private set

    fun check() {
        val now = url?.let { get(it.trimEnd('/') + "/car/health") == 200 } ?: return
        if (now != up) { up = now; since = System.currentTimeMillis() }
    }

    fun json(): JSONObject = JSONObject().put("url", url ?: JSONObject.NULL).put("up", up ?: JSONObject.NULL).put("since", since)
}

/**
 * Pairing the car from Soma's tray (slice 9a): the code comes from the canvas service one of his runners advertises,
 * asked with this desktop's key for that runner, and is shown here for him to type into the car's page. What to say.
 */
object CarPairing {
    private fun service(machines: List<dev.omalauncher.assist.MacMachine>) =
        machines.firstNotNullOfOrNull { m -> dev.omalauncher.canvas.advertisedService(m.config.url, m.config.key)?.let { it to m.config.key } }

    fun start(machines: List<dev.omalauncher.assist.MacMachine>): String {
        val (url, key) = service(machines) ?: return "No canvas service answered, so the car can't be paired now."
        val (code, answer) = dev.omalauncher.canvas.somaPost(url.trimEnd('/') + "/car/pairing", key, "{}")
        val got = runCatching { JSONObject(answer) }.getOrNull()
        if (code != 200 || got == null) return "The canvas service didn't give a code (${got?.optString("error")?.ifEmpty { null } ?: "HTTP $code"})."
        val minutes = ((got.optLong("until") - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        return "Type ${got.getString("code")} into the car's page within $minutes minutes. It works once; pairing the car again replaces it."
    }

    fun end(machines: List<dev.omalauncher.assist.MacMachine>): String {
        val (url, key) = service(machines) ?: return "No canvas service answered, so the car wasn't unpaired."
        val code = runCatching {
            val c = java.net.URI(url.trimEnd('/') + "/car/pairing").toURL().openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 10000; c.requestMethod = "DELETE"
            c.setRequestProperty("Authorization", "Bearer $key")
            c.responseCode
        }.getOrDefault(-1)
        return if (code == 200) "The car is unpaired: its page shows nothing until it is paired again." else "The car couldn't be unpaired (HTTP $code)."
    }
}
