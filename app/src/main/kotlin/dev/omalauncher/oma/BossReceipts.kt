package dev.omalauncher.oma

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Where Boss's signed words wait for an agent to pass on (BossReceipt; `oma receipt`): Soma's files, which Linux
 * reads as /root/.local/share/omalauncher/boss-receipts. An agent can read them, and could write there too, but
 * can't sign: a file it made itself never verifies on Canvas. Off the main thread, best effort: his words go on
 * whether or not they could be signed.
 */
internal object BossReceipts {
    const val DIR = "boss-receipts"
    private const val KEEP = 200

    fun record(context: Context, words: BossWords) {
        if (!words.vouched) return
        val app = context.applicationContext
        Thread {
            runCatching {
                val signed = CanvasReceiptKeys.signBoss(words) ?: return@runCatching
                save(File(app.filesDir, DIR), signed)
            }
        }.apply { name = "boss-receipt"; isDaemon = true }.start()
    }

    /** Writes [signed] as `<nonce>.json` and `last.json`, each whole or not at all, and keeps the newest [KEEP]. */
    fun save(dir: File, signed: JSONObject): File {
        val receipt = JSONObject(signed.getString("receipt"))
        val id = receipt.getString("nonce").filter { it.isLetterOrDigit() || it == '-' }.take(64)
        require(id.isNotEmpty())
        val body = JSONObject().put("receipt", signed.getString("receipt")).put("sig", signed.getString("sig"))
            .put("kind", receipt.getString("kind")).put("messageId", receipt.getString("messageId"))
            .put("conversation", receipt.getString("conversation")).put("at", receipt.getLong("at")).toString()
        dir.mkdirs()
        fun write(name: String) = File(dir, "$name.tmp").apply { writeText(body) }.renameTo(File(dir, name))
        write("$id.json"); write("last.json")
        if (receipt.getString("kind") == "chat-message") write("last-chat.json") else write("last-answer.json")
        dir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("last") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)?.forEach { it.delete() }
        return File(dir, "$id.json")
    }
}

/**
 * Whether [event] is Boss's own finger (or pen) on the phone's touchscreen ([BossTouch]): a real, non-virtual input
 * device with a touchscreen. adb's `input` and accessibility gestures are injected through a virtual device or none.
 */
internal fun bossFinger(event: android.view.MotionEvent): Boolean {
    val device = event.device ?: return false
    if (device.isVirtual || !device.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return false
    if (event.source and android.view.InputDevice.SOURCE_TOUCHSCREEN != android.view.InputDevice.SOURCE_TOUCHSCREEN) return false
    return event.getToolType(0) in setOf(android.view.MotionEvent.TOOL_TYPE_FINGER, android.view.MotionEvent.TOOL_TYPE_STYLUS)
}

/** Each lifted touch says whose it was: called from the chat's and home's dispatchTouchEvent. */
internal fun noteTouch(event: android.view.MotionEvent) {
    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) BossTouch.touched(bossFinger(event))
}
