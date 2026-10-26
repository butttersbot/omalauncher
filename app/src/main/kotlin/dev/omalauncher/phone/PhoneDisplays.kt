package dev.omalauncher.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import org.json.JSONObject

/** IDs come from a live, mutually authenticated helper, never from an agent-supplied number or a stale file. */
internal data class PhoneBackgroundDisplay(val id: Int, val width: Int, val height: Int)

internal object PhoneDisplays {
    private data class Verified(val info: JSONObject, val checkedAt: Long)
    @Volatile private var verified: Verified? = null
    @Volatile private var unavailable = "Checking the background display helper. Try again shortly."
    private var started = false
    /** Network I/O stays off Android's main thread. The live display is also checked before every action. */
    fun start(context: Context) {
        if (started) return
        started = true
        kotlin.concurrent.thread(name = "background-display-status", isDaemon = true) {
            while (true) {
                try { verified = Verified(call(context, "status"), android.os.SystemClock.elapsedRealtime()) }
                catch (error: Exception) { verified = null; unavailable = error.message ?: "Start the background display helper." }
                Thread.sleep(500)
            }
        }
    }
    private fun mac(key: ByteArray, text: String): String = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        java.util.Base64.getEncoder().encodeToString(doFinal(text.toByteArray()))
    }
    private fun same(a: String, b: String) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    private fun line(reader: java.io.BufferedReader): String {
        val text = StringBuilder()
        repeat(1024) {
            val ch = reader.read()
            check(ch >= 0) { "Background helper closed." }
            if (ch == 10) return text.toString()
            text.append(ch.toChar())
        }
        error("Oversized background reply")
    }
    private fun call(context: Context, command: String): JSONObject {
        val key = java.io.File(context.filesDir, "background-display.key").readBytes()
        check(key.size == 64) { "Set up the background display helper first." }
        return java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 18746), 500)
            socket.soTimeout = 700
            val reader = socket.getInputStream().bufferedReader()
            val random = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
            val nonce = java.util.Base64.getEncoder().encodeToString(random)
            socket.getOutputStream().write("$nonce\n".toByteArray())
            val hello = line(reader).split(" ", limit = 2)
            check(hello.size == 2 && same(hello[1], mac(key, "$nonce ${hello[0]} server"))) { "Background helper authentication failed." }
            val prefix = "$nonce ${hello[0]}"
            socket.getOutputStream().write("$command ${mac(key, "$prefix $command")}\n".toByteArray())
            val response = line(reader)
            val split = response.lastIndexOf(' ')
            check(split > 0 && same(response.substring(split + 1), mac(key, "$prefix ${response.substring(0, split)}"))) { "Invalid background response." }
            JSONObject(response.substring(0, split))
        }
    }

    fun background(context: Context): PhoneBackgroundDisplay {
        check(Build.VERSION.SDK_INT >= 33) { "Background display needs Android 13 or later." }
        val checked = verified ?: error(unavailable)
        check(android.os.SystemClock.elapsedRealtime() - checked.checkedAt <= 2000) { "Background helper stopped answering. Start it again." }
        val result = checked.info
        val id = result.getInt("id")
        check(id > 0 && result.getBoolean("alwaysUnlocked")) { "No unlocked background display." }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        check(display != null && display.isValid && display.name == "soma-agent") { "Background display has gone. Start the helper again." }
        return PhoneBackgroundDisplay(id, result.getInt("width"), result.getInt("height"))
    }

    /** The helper answered lately, with or without a display up (it makes one on demand). */
    fun helperUp(): Boolean = verified?.let { android.os.SystemClock.elapsedRealtime() - it.checkedAt <= 2000 } == true

    /**
     * The background display, made for this step if the helper keeps none up (Boss, 2026-10-26: "it should only flip on
     * when we're using it"; banking apps refuse to run beside any extra display). Each call keeps it up for the helper's
     * idle time (2 minutes) more. Not on the main thread: it talks to the helper. An older helper that always keeps its
     * display needs no asking.
     */
    fun ensure(context: Context): PhoneBackgroundDisplay {
        val checked = verified ?: error(unavailable)
        check(android.os.SystemClock.elapsedRealtime() - checked.checkedAt <= 2000) { "Background helper stopped answering. Start it again." }
        if (!checked.info.optBoolean("onDemand")) return background(context)
        verified = Verified(call(context, "open"), android.os.SystemClock.elapsedRealtime())
        // Android lists a new display a moment after it is made.
        repeat(20) { runCatching { return background(context) }; Thread.sleep(100) }
        return background(context)
    }

    /** Called only from the owner's Take over activity, after the phone is unlocked and control is paused. */
    fun takeOver(context: Context) { background(context); call(context, "takeover") }
}

/** Commands without an explicit display-scoped implementation must never fall back to the main screen. */
internal object PhoneBackgroundCommands {
    val allowed = setOf("status", "stop", "apps", "open", "url", "read", "screen", "press", "fill", "submit", "screenshot", "click", "type", "scroll", "tap", "swipe")
    fun requireAllowed(command: String) {
        require(command in allowed) { "$command is unavailable on the background display. No main-screen action was taken." }
    }
}
