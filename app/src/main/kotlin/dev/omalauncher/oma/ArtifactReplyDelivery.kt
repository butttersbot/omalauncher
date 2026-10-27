package dev.omalauncher.oma

import android.content.Context
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.macThreadId
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Persist before handoff: reloads/double taps cannot silently deliver an answer again. */
internal object ArtifactReplyDelivery {
    @Synchronized fun submit(context: Context, host: ChatHost, source: ArtifactReplySource, request: ArtifactReplyRequest, done: (ArtifactReplyResult) -> Unit) {
        val builder = BuilderId.entries.firstOrNull { request.recipient == "phone:${it.letter}" }
        val mac = host.threads().firstOrNull { it.key == request.recipient && it.mac && !it.unplaced }
        if (!ArtifactReply.knownRecipient(request.recipient, host.threads().filter { it.mac && !it.unplaced }.map { it.key }.toSet())) {
            done(ArtifactReplyResult(false, "Unknown recipient: ${request.recipient}. Nothing sent.")); return
        }
        val preferences = context.getSharedPreferences("artifact-submissions", Context.MODE_PRIVATE)
        val key = MessageDigest.getInstance("SHA-256").digest((source.key + "\u0000" + request.recipient + "\u0000" + (request.answers?.let { CanvasReceipt.canonical(JSONObject(it)) } ?: request.text)).toByteArray()).joinToString("") { "%02x".format(it) }
        preferences.getString(key, null)?.let { saved ->
            val old = runCatching { JSONObject(saved) }.getOrNull()
            val sent = old?.optString("state") == "sent"
            done(ArtifactReplyResult(sent, if (sent) "Sent to ${request.recipient}" else "Delivery already submitted; check ${request.recipient}'s chat for its status."))
            return
        }
        val id = UUID.randomUUID().toString()
        // This local id is for deduplication only, not a Canvas approval proof.
        val record = JSONObject().put("id", id).put("source", source.key).put("recipient", request.recipient).put("text", request.text).put("state", "sending")
        if (!preferences.edit().putString(key, record.toString()).commit()) {
            done(ArtifactReplyResult(false, "Could not save this submission. Nothing sent.")); return
        }
        fun finish(sent: Boolean) {
            synchronized(this) {
                record.put("state", if (sent) "sent" else "unconfirmed")
                preferences.edit().putString(key, record.toString()).commit()
            }
            done(ArtifactReplyResult(sent, if (sent) "Sent to ${request.recipient}" else "Delivery not confirmed. Check ${request.recipient}'s chat before sending again."))
        }
        val origin = host.canvasOrigin()
        kotlin.concurrent.thread(name = "artifact-submit-receipt", isDaemon = true) {
            val proof = runCatching { ArtifactCanvasReceipt.prepare(context, origin, source, request, id) }.getOrElse { JSONObject().put("receiptStatus", "unlinked") }
            val message = "Answers from artifact ${source.key}\n\n${request.text}\n\n[Soma submission $id]\n" + if (proof.length() > 0) proof.toString() else ""
            record.put("receiptStatus", proof.optString("receiptStatus"))
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (builder != null) {
                        val got = AgentTasks.followUp(context, message, builder = builder)
                        check(got == builder) { "Wrong recipient" }
                        finish(true)
                    } else MacThreads.artifactReply(context, message, macThreadId(mac!!.key)!!, mac.name, ::finish)
                } catch (_: Exception) { finish(false) }
            }
        }
    }
}
