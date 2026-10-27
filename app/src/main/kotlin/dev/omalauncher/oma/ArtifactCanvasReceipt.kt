package dev.omalauncher.oma

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Blocking, best effort. No owner key: a linked device's Keystore signature authenticates the receipt. */
internal object ArtifactCanvasReceipt {
    fun prepare(context: Context, origin: String?, source: ArtifactReplySource, request: ArtifactReplyRequest, submissionId: String): JSONObject {
        fun unlinked() = JSONObject().put("receiptStatus", "unlinked")
        if (!CanvasReceipt.approval(request)) return JSONObject()
        val base = CanvasReceipt.origin(origin) ?: return unlinked()
        val signed = runCatching { CanvasReceiptKeys.sign(source, request, submissionId) }.getOrNull() ?: return unlinked()
        val device = JSONObject(signed.getString("receipt")).getString("deviceId")
        val preferences = context.getSharedPreferences("canvas-linked-receipts", Context.MODE_PRIVATE)
        val cacheKey = CanvasReceipt.sha256((base + ":" + device).toByteArray())
        var known = preferences.getBoolean(cacheKey, false)
        var lookup: HttpURLConnection? = null
        try {
            lookup = URL("$base/api/devices/$device").openConnection() as HttpURLConnection
            lookup.instanceFollowRedirects = false; lookup.connectTimeout = 1000; lookup.readTimeout = 1500
            if (lookup.responseCode == 200) {
                val state = JSONObject(lookup.inputStream.bufferedReader().use { it.readText().take(8192) })
                known = state.optString("deviceId") == device && state.optBoolean("linked") && !state.optBoolean("revoked")
                preferences.edit().putBoolean(cacheKey, known).commit()
                if (!known) return unlinked()
            }
        } catch (_: Exception) { /* A prior confirmed link can still yield an offline receipt. */ }
        finally { lookup?.disconnect() }
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$base/api/receipts").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(signed.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val reply = JSONObject(connection.inputStream.bufferedReader().use { it.readText().take(8192) })
                val id = reply.optString("id").takeIf { it.startsWith("rc_") && it.length <= 160 } ?: return unlinked()
                preferences.edit().putBoolean(cacheKey, true).commit()
                return JSONObject().put("receiptStatus", "registered").put("canvasReceipt", signed).put("canvasReceiptId", id)
            }
            if (code in 400..499) {
                preferences.edit().putBoolean(cacheKey, false).commit()
                return unlinked() // Unknown/revoked keys and invalid signatures never produce approval proof.
            }
        } catch (_: Exception) {
            // Answers still go to their recipient, whether or not Canvas answered.
        } finally { connection?.disconnect() }
        return if (known) JSONObject().put("receiptStatus", "signed-offline").put("canvasReceipt", signed) else unlinked()
    }
}
