package dev.omalauncher.desktop

import java.awt.TrayIcon

/** What Soma tells him when the window may not be in front: the tray's notifications (the Mac's Notification Center). */
class Notices {
    @Volatile var tray: TrayIcon? = null

    fun say(title: String, text: String) {
        val icon = tray
        // On Linux there is no Java tray: the desktop's own notifications say it (LinuxTray.notify).
        if (icon == null) LinuxTray.notify(title, text) else icon.displayMessage(title.take(80), text.take(240), TrayIcon.MessageType.NONE)
    }

    fun toast(text: String) = say("Soma", text)
}
