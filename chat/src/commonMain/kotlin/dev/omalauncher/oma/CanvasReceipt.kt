package dev.omalauncher.oma

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Exact bytes shown, supplied by the native artifact reader, never by page JavaScript. */
data class ArtifactReplySource(val id: String, val name: String, val version: String, val sha256: String) {
    val key get() = "$id@$version:$sha256"
}

object CanvasReceipt {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun origin(url: String?): String? = runCatching {
        val uri = URI(url ?: return null)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        "${uri.scheme.lowercase()}://${uri.host.lowercase()}" + if (uri.port == -1) "" else ":${uri.port}"
    }.getOrNull()

    /** Python json.dumps(sort_keys=True, separators=(',', ':'), ensure_ascii=False), without locale or whitespace. */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sortedWith { a, b ->
            val aa = a.codePoints().toArray(); val bb = b.codePoints().toArray()
            var order = 0
            for (i in 0 until minOf(aa.size, bb.size)) { order = aa[i].compareTo(bb[i]); if (order != 0) break }
            if (order != 0) order else aa.size.compareTo(bb.size)
        }.joinToString(",", "{", "}") { quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported receipt value")
    }
    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) append(when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\b' -> "\\b"; '\u000c' -> "\\f"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c < ' ') "\\u%04x".format(c.code) else c.toString()
        })
        append('"')
    }
    fun approval(request: ArtifactReplyRequest): Boolean {
        val answers = runCatching { request.answers?.let(::JSONObject) }.getOrNull() ?: return request.text.trim().startsWith("Approve", ignoreCase = true)
        if (answers.optString("decision").lowercase() in setOf("changes", "reject", "deny")) return false
        return answers.optString("decision").equals("approve", ignoreCase = true) ||
            listOf("approve", "approved").any { answers.opt(it) == true } ||
            answers.keys().asSequence().any { key -> (answers.opt(key) as? String)?.trim()?.startsWith("Approve", ignoreCase = true) == true }
    }
    fun receipt(deviceId: String, nonce: String, at: Long, source: ArtifactReplySource, request: ArtifactReplyRequest): JSONObject {
        val answers = request.answers?.let(::JSONObject) ?: JSONObject().put("answer", request.text)
        val version = source.version.toIntOrNull() ?: error("No native artifact version")
        require(version > 0)
        return JSONObject().put("v", 1).put("deviceId", deviceId).put("nonce", nonce).put("at", at)
            .put("recipient", request.recipient).put("replyId", nonce)
            .put("artifact", JSONObject().put("name", source.name).put("version", version).put("sha256", source.sha256))
            .put("answers", answers)
    }
}
