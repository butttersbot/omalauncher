package dev.omalauncher.desktop

import java.io.File

/**
 * Soma starting when he logs in: a LaunchAgent on the Mac, an autostart entry on Linux (Hyprland's own exec-once can
 * run `soma` too). `soma --start-at-login` sets it up for the Soma that ran it; `--no-start-at-login` takes it away.
 */
object Login {
    private val home = System.getProperty("user.home")
    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val agent = File(home, "Library/LaunchAgents/dev.omalauncher.soma.plist")
    private val autostart = File(home, ".config/autostart/soma.desktop")

    /**
     * [arg] for a desktop entry's Exec line, by the spec: string-escaped first (each `\` doubled), then quoted, so inside
     * the quotes a literal `\` is four backslashes and `$`, `"`, `` ` `` take two; a literal `%` is `%%`. (glib still
     * refuses a launcher path with `%` in it, since it checks argv[0] before expanding field codes.)
     */
    private fun execArg(arg: String) = "\"" + arg.replace("\\", "\\\\\\\\").replace("\"", "\\\\\"").replace("`", "\\\\`").replace("$", "\\\\$").replace("%", "%%") + "\""

    /** The command that starts this Soma: the app bundle's launcher, or the java and jar it runs from. */
    private fun command(): List<String> {
        val launcher = ProcessHandle.current().info().command().orElse("")
        val jar = File(Login::class.java.protectionDomain.codeSource.location.toURI()).path
        return if (launcher.endsWith("/Soma")) listOf(launcher) else listOf(launcher, "-jar", jar)
    }

    fun install(): String {
        val run = command()
        return if (mac) {
            agent.parentFile.mkdirs()
            agent.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>dev.omalauncher.soma</string>
<key>ProgramArguments</key><array>${run.joinToString("") { "<string>" + it.replace("&", "&amp;").replace("<", "&lt;") + "</string>" }}</array>
<key>RunAtLoad</key><true/>
</dict></plist>
""")
            "Soma starts at login now (${agent.path})."
        } else {
            autostart.parentFile.mkdirs()
            autostart.writeText("[Desktop Entry]\nType=Application\nName=Soma\nExec=${run.joinToString(" ") { execArg(it) }}\nX-GNOME-Autostart-enabled=true\n")
            "Soma starts at login now (${autostart.path}); on Hyprland, `exec-once = soma` does the same."
        }
    }

    fun remove(): String {
        val gone = if (mac) agent.delete() else autostart.delete()
        return if (gone) "Soma no longer starts at login." else "Soma wasn't set to start at login."
    }

    /**
     * `soma --register-links`, Linux only: soma:// links open this Soma (an application entry with the scheme, made the
     * default by xdg-mime). On the Mac the app bundle registers the scheme itself (its Info.plist), so nothing is needed.
     */
    fun registerLinks(): String {
        if (mac) return "On the Mac, Soma.app registers soma:// itself: open it once from Applications."
        val entry = File(home, ".local/share/applications/soma.desktop")
        entry.parentFile.mkdirs()
        entry.writeText("[Desktop Entry]\nType=Application\nName=Soma\nExec=${command().joinToString(" ") { execArg(it) }} %u\nMimeType=x-scheme-handler/soma;\nNoDisplay=true\n")
        val set = runCatching { ProcessBuilder("xdg-mime", "default", "soma.desktop", "x-scheme-handler/soma").inheritIO().start().waitFor() }.getOrDefault(-1)
        return if (set == 0) "soma:// links open Soma now (${entry.path})." else "Wrote ${entry.path}, but xdg-mime didn't take it; run: xdg-mime default soma.desktop x-scheme-handler/soma"
    }
}
