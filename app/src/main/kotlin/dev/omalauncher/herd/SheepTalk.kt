package dev.omalauncher.herd

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.phone.PhoneService
import dev.omalauncher.ui.Speech

/** The most of what was heard that the "sent" card repeats. */
private const val SENT_SHOWN = 140

/**
 * What goes to the builder for words said to the sheep: what was [heard], with a line before it saying it came through
 * the sheep while [inFront] was in front, so the answer is kept to what a card over that app can hold. Null when nothing
 * was heard.
 */
fun sheepPrompt(
    heard: String,
    inFront: String?,
): String? {
    val words = heard.trim().takeIf { it.isNotEmpty() } ?: return null
    val over = inFront?.takeIf { it.isNotBlank() }?.let { " while $it was in front" }.orEmpty()
    return "[Said to the sheep$over: Boss is in that app, maybe mid-game, and your answer shows as a short card over it. " +
        "Keep it to a few lines; the full answer is in the chat.]\n\n$words"
}

/**
 * Talking to phone builder A through the floating sheep (Boss, 2026-10-17: "we need to be able to communicate while I'm
 * playing the game, I don't want to have to keep switching the screen back and forth"). Holding the sheep still starts
 * listening, letting go sends what was heard; the answer comes back as the answer card over whatever is in front, as
 * every finished task's does ([dev.omalauncher.assist.AgentNotices.answered]). Soma keeps the microphone while
 * another app is in front through its own foreground service. Main thread.
 */
class SheepTalk(
    private val service: AccessibilityService,
) {
    private val speech by lazy { Speech(service) }
    private var inFront: String? = null

    fun start() {
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return say("Soma can't hear you", "Allow the microphone for Soma, then hold the sheep again.")
        }
        inFront = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        say("Listening", "Let go of the sheep to send.")
        // The ring listens along the screen's edge for as long as the sheep does.
        OmaRing.hearing(this, true)
        speech.start(hold = true) { heard ->
            OmaRing.hearing(this, false)
            send(heard)
        }
        speech.error?.let {
            OmaRing.hearing(this, false)
            say("Soma can't hear you", it)
        }
    }

    /** The finger lifted: what was heard so far is sent. */
    fun finish() {
        if (speech.listening) speech.stop()
    }

    private fun send(heard: String) {
        val prompt = sheepPrompt(heard, inFront) ?: return say("Didn't catch that", "Hold the sheep and speak again.")
        AgentTasks.direct(service, prompt, builder = BuilderId.A)
        say("Sent to Soma", heard.trim().take(SENT_SHOWN))
    }

    private fun say(
        caption: String,
        text: String,
    ) {
        (service as? PhoneService)?.showToast(caption, text)
    }
}
