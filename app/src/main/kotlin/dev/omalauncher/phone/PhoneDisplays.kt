package dev.omalauncher.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import org.json.JSONObject

/** IDs come from a live, mutually authenticated helper, never from an agent-supplied number or a stale file. */
internal data class PhoneBackgroundDisplay(val id: Int, val width: Int, val height: Int)

internal object PhoneDisplays {
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
        val result = call(context, "status")
        val id = result.getInt("id")
        check(id > 0 && result.getBoolean("alwaysUnlocked")) { "No unlocked background display." }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        check(display != null && display.isValid && display.name == "soma-agent") { "Background display has gone. Start the helper again." }
        return PhoneBackgroundDisplay(id, result.getInt("width"), result.getInt("height"))
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
