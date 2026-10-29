package dev.omalauncher.desktop

import java.io.File
import java.security.SecureRandom

/*
 * Soma's tray icon on Linux (Boss, 2026-10-29: "Yes, add a Waybar icon"). Java's tray speaks XEmbed, which Waybar doesn't
 * show (SystemTray.isSupported() is false on Omarchy), so a small helper speaks StatusNotifierItem over D-Bus instead
 * (resources/linux/soma-tray.py, Python with GLib, as Omarchy has). Its menu is the Mac tray's. Each item is said to this
 * Soma over its hand-off port as "tray TOKEN WORD", with a token made for this run: no other program can drive Soma
 * through it. The helper ends when Soma does.
 */

/** The words the tray may say, beside its token: the Mac tray's items. */
private val TRAY_WORDS = setOf("show", "dashboard", "settings", "car pair", "car unpair", "grok connect", "grok disconnect", "quit")

/** The word in [said] when it is the tray's, with [token]; null for anything else (no token, a wrong one, a word it doesn't have). */
internal fun trayWord(said: String, token: String?): String? {
    if (token.isNullOrEmpty() || !said.startsWith("tray ")) return null
    val rest = said.removePrefix("tray ")
    val given = rest.substringBefore(' ')
    if (!java.security.MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return null
    val word = rest.substringAfter(' ', "").trim()
    return word.takeIf { it in TRAY_WORDS || (it.startsWith("look ") && it.removePrefix("look ") in Looks.ids) }
}

object LinuxTray {
    /** This run's token: what the helper says with every item. */
    var token: String? = null
        private set

    private var helper: Process? = null

    /** Whether this is Linux, where the tray is the helper's. */
    val linux = System.getProperty("os.name").lowercase().contains("linux")

    /** Starts the helper beside this Soma, whose hand-off port is [port]; false when it can't (no python3, no session bus). */
    fun start(port: Int): Boolean {
        if (!linux) return false
        val script = File(SomaHome.dir, "bin/soma-tray.py")
        val code = LinuxTray::class.java.classLoader.getResourceAsStream("linux/soma-tray.py")?.use { it.readBytes() } ?: return false
        script.parentFile.mkdirs()
        script.writeBytes(code)
        script.setExecutable(true, true)
        val made = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        token = made
        helper = runCatching {
            ProcessBuilder("python3", script.path).apply {
                environment()["SOMA_TRAY_PORT"] = port.toString()
                environment()["SOMA_TRAY_TOKEN"] = made
                environment()["SOMA_TRAY_LOOKS"] = Looks.ids.joinToString(",")
                environment()["SOMA_TRAY_PARENT"] = ProcessHandle.current().pid().toString()
                redirectErrorStream(true)
                redirectOutput(File(SomaHome.dir, "tray.log"))
            }.start()
        }.getOrNull()
        return helper != null
    }

    fun stop() {
        helper?.destroy()
        helper = null
    }

    /** A notice on Linux, where there is no Java tray to carry it: the desktop's own notifications (mako on Omarchy). */
    fun notify(title: String, text: String) {
        if (!linux) return
        runCatching { ProcessBuilder("notify-send", "-a", "Soma", title.take(80), text.take(240)).start() }
    }
}
