package dev.omalauncher.phone

import org.json.JSONObject

/** A launch can hide Samsung's main IME even with independent display focus. Never restore it by tapping. */
object PhoneLaunchPolicy {
    fun waits(background: Boolean, command: String, mainImeVisible: Boolean) =
        background && command in setOf("open", "url") && mainImeVisible

    fun waiting(): JSONObject = JSONObject().put("ok", false).put("waiting", true)
        .put("reason", "boss-typing").put("actionMayHaveRun", false)
        .put("error", "Waiting: Boss is typing. Background activity launch deferred; nothing was opened. Retry after the main keyboard closes.")
}

internal class PhoneLaunchDeferred : IllegalStateException("Waiting: Boss is typing.")
