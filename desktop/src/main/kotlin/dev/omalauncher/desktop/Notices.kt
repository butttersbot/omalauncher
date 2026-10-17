package dev.omalauncher.desktop

import java.awt.TrayIcon

/** What Soma tells him when the window may not be in front: the tray's notifications (the Mac's Notification Center). */
class Notices {
    @Volatile var tray: TrayIcon? = null

    fun say(title: String, text: String) {
        tray?.displayMessage(title.take(80), text.take(240), TrayIcon.MessageType.NONE)
    }

    fun toast(text: String) = say("Soma", text)
}
