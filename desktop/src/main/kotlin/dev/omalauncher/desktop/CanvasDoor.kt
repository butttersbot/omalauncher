package dev.omalauncher.desktop

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.linux.shellQuote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/*
 * How Soma's own front agent arranges the window's canvas (Soma Canvas slice 6, PRD §5): `soma canvas …`, a second Soma
 * started with those words, hands them to the running one over its loopback port with a token the running one minted for
 * that one request of his. The token is in the agent's environment alone, never in a file; it names the request, so every
 * call the agent makes for it is one restore point, and it is gone when the request ends. It reaches this screen's canvas
 * and nothing else: no approvals, no keys, no other screen.
 */

/** What a token allows: verbs on this screen, as part of [request], from [actor]. */
private data class Grant(val request: String, val actor: String)

class CanvasDoor(
    /**
     * Where templates and LAYOUT.md are (slice 10): the canvas service's shelf when a runner advertises one, so every desktop
     * shares his; this Soma's own folder when none does.
     */
    private val shelf: () -> dev.omalauncher.canvas.CanvasShelf = { FileShelf(SomaHome.dir) },
    /** Runs a call against the canvas where its state lives (the window's event thread), and answers. */
    private val onCanvas: ((CanvasPad) -> JSONObject) -> JSONObject,
) {
    private val grants = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()

    /** A new token for [request] from [actor], good until [revoke]. */
    fun mint(request: String, actor: String = "agent:front"): String {
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        grants[token] = Grant(request, actor)
        return token
    }

    fun revoke(token: String) {
        grants.remove(token)
    }

    /** How many tokens are good now: one per request being answered, none between them. */
    val open: Int get() = grants.size

    /**
     * One call as it came over the port: `canvas TOKEN ["word", …]`. Answers a line of JSON: the canvas's answer, or why it
     * was refused. A token not minted here, or revoked, reaches nothing.
     */
    fun handle(line: String): String {
        fun no(why: String) = JSONObject().put("ok", false).put("error", why).toString()
        val parts = line.removePrefix(WORD).split(' ', limit = 2)
        if (!line.startsWith(WORD) || parts.size < 2) return no("Usage: soma ${CanvasCommands.USAGE}")
        val said = parts[0].toByteArray()
        val grant = grants.entries.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), said) }?.value
            ?: return no("soma canvas is for Soma's own agent while it answers a request; this token isn't one.")
        val words = runCatching { JSONArray(parts[1]).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
            ?: return no("Usage: soma ${CanvasCommands.USAGE}")
        // The shelf is read here, off the window's thread, and written after: the window never waits on the network.
        val prepared = try { PreparedShelf.of(shelf, words) } catch (e: dev.omalauncher.canvas.AskRefused) { return no(e.message ?: "the shelf can't be reached") }
        val answer = onCanvas { pad -> CanvasCommands.run(words, pad, grant.request, grant.actor, prepared) }
        prepared?.flush()?.let { why ->
            val what = if (words.firstOrNull() == "remember") "LAYOUT.md wasn't changed" else "the template wasn't kept"
            return JSONObject().put("ok", false).put("error", "Soma's canvas service couldn't keep it, so $what: $why").toString()
        }
        return answer.toString()
    }

    companion object {
        /** What a call starts with on the port, beside "show", "toggle" and "open". */
        const val WORD = "canvas "

        /** The variable the agent's token is in. */
        const val TOKEN = "SOMA_CANVAS_TOKEN"

        /**
         * `soma canvas [words]`, as a second Soma: hands [words] to the running one with the token in [env] and prints its
         * answer. 0 when it did what was asked, 1 otherwise, with the reason printed either way.
         */
        fun client(words: List<String>, env: Map<String, String> = System.getenv(), out: (String) -> Unit = ::println): Int {
            val token = env[TOKEN]
            if (token.isNullOrBlank()) { out("""{"ok":false,"error":"soma canvas is for Soma's own agent while it answers a request: no token here."}"""); return 1 }
            if (words.firstOrNull() in listOf(null, "help", "--help")) { out("soma " + CanvasCommands.USAGE); return 0 }
            val port = runCatching { SomaHome.socket.readText().trim().toInt() }.getOrNull()
            val answer = port?.let {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), it), 1000)
                        socket.soTimeout = 15_000
                        socket.getOutputStream().write((WORD + token + " " + JSONArray(words).toString() + "\n").toByteArray())
                        socket.getInputStream().bufferedReader().readLine()
                    }
                }.getOrNull()
            }
            if (answer == null) { out("""{"ok":false,"error":"Soma isn't running here, or didn't answer."}"""); return 1 }
            out(answer)
            return if (runCatching { JSONObject(answer).optBoolean("ok") }.getOrDefault(false)) 0 else 1
        }

        /**
         * A `soma` the front agent can run, in [dir]: this very Soma started again with the words it is given, so `soma
         * canvas` reaches the one running. The app's own launcher when it has one, else the Java and class path it runs
         * on. Readable and runnable by this user alone. Null when how this Soma was started can't be told.
         */
        fun shim(dir: File): File? {
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            val home = System.getProperty("soma.home")
            val line = if (File(command).name == "java") {
                listOfNotNull(command, home?.let { "-Dsoma.home=$it" }, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), "dev.omalauncher.desktop.MainKt")
            } else {
                listOf(command)
            }
            return runCatching {
                dir.mkdirs()
                File(dir, "soma").apply {
                    writeText("#!/bin/sh\nexec " + line.joinToString(" ") { shellQuote(it) } + " \"\$@\"\n")
                    setReadable(false, false); setReadable(true, true)
                    setWritable(false, false); setWritable(true, true)
                    setExecutable(false, false); setExecutable(true, true)
                }
            }.getOrNull()
        }
    }
}
