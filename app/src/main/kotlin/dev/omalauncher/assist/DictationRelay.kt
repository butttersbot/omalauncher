package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.openHttp
import org.json.JSONObject

/*
 * Dictation relay (Boss, 2026-10-30; ClaudeSync projects/tailshare/docs/dictation-relay.md): what the lock button's
 * listening screen heard can be typed at the cursor on another device instead of going to the agent. The hearing is
 * unchanged, only where the words go. The target is remembered until he changes it. Nothing here ever falls back to
 * the agent: an offline, locked or failed device is said so, and the caller keeps his words.
 */

/** Where the listening screen's words go. [base] is the device's receiver, null for the phone's agent. */
data class RelayTarget(val id: String, val label: String, val base: String?)

object RelayTargets {
    val Agent = RelayTarget("agent", "Phone agent", null)

    // Its Tailscale address, as mac-runner-mg1.json has it: the receiver listens only there, and the name
    // doesn't resolve everywhere on this phone (Linux's DNS doesn't know it, 2026-10-30).
    val Mg1 = RelayTarget("mg1", "MG1", "http://100.77.138.16:8481")

    /** The short list, the agent first. */
    val all = listOf(Agent, Mg1)

    private const val PREFS = "dictation-relay"
    private const val TARGET = "target"

    /** The target saved as [id]; the agent for none, or for one no longer on the list. */
    fun named(id: String?): RelayTarget = all.firstOrNull { it.id == id } ?: Agent

    fun current(context: Context): RelayTarget =
        named(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TARGET, null))

    fun pick(context: Context, target: RelayTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TARGET, target.id).apply()
    }
}

/** How a relay ended. Only [Typed] means the words have left his hands; on the rest he keeps them. */
sealed interface RelayOutcome {
    data class Typed(val app: String) : RelayOutcome

    /** It could not be reached: nothing was typed. */
    data object Offline : RelayOutcome

    data object Locked : RelayOutcome

    data object NoWindow : RelayOutcome

    data object TooLong : RelayOutcome

    data object NoText : RelayOutcome

    /** Sent, and no answer came: it may have typed them, so sending again may type them twice. */
    data object NoAnswer : RelayOutcome

    /** Any other refusal, such as this phone not being among its senders. */
    data class Refused(val code: Int) : RelayOutcome
}

/** The receiver's limit, checked here too so that words too long never leave the phone. */
const val RELAY_MAX_CHARS = 8000

/** What a receiver's answer means. */
fun relayOutcome(code: Int, body: String): RelayOutcome {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val error = json?.optString("error").orEmpty()
    return when {
        code == 200 -> RelayOutcome.Typed(json?.optString("app").orEmpty())
        code == 409 && error == "locked" -> RelayOutcome.Locked
        code == 409 && error == "no window" -> RelayOutcome.NoWindow
        code == 400 && error == "too long" -> RelayOutcome.TooLong
        code == 400 && error == "no text" -> RelayOutcome.NoText
        else -> RelayOutcome.Refused(code)
    }
}

/** What the screen says of [outcome] on the device called [label]. */
fun relayWords(outcome: RelayOutcome, label: String): String =
    when (outcome) {
        is RelayOutcome.Typed -> if (outcome.app.isBlank()) "Typed on $label" else "Typed into ${outcome.app} on $label"
        RelayOutcome.Offline -> "$label is offline. Not typed."
        RelayOutcome.Locked -> "$label is locked. Not typed."
        RelayOutcome.NoWindow -> "No window to type in on $label. Not typed."
        RelayOutcome.TooLong -> "Too long for $label. Not typed."
        RelayOutcome.NoText -> "Nothing to type on $label."
        RelayOutcome.NoAnswer -> "No answer from $label. It may have typed this."
        is RelayOutcome.Refused -> "$label refused this. Not typed."
    }

/** Posts [text] to the receiver at [base] and says how it went; never throws. Blocking. */
fun relayText(
    base: String,
    text: String,
    connectTimeout: Int = 4_000,
    readTimeout: Int = 10_000,
): RelayOutcome {
    if (text.isBlank()) return RelayOutcome.NoText
    if (text.length > RELAY_MAX_CHARS) return RelayOutcome.TooLong
    var sent = false
    val c = runCatching { openHttp("$base/v1/type", connectTimeout, readTimeout) }.getOrElse { return RelayOutcome.Offline }
    return try {
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("text", text).toString().toByteArray()
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        sent = true
        val code = c.responseCode
        val answer = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        relayOutcome(code, answer)
    } catch (e: Exception) {
        if (sent) RelayOutcome.NoAnswer else RelayOutcome.Offline
    } finally {
        c.disconnect()
    }
}
