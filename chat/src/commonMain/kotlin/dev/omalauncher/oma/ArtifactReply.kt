package dev.omalauncher.oma

import java.net.URLDecoder
import org.json.JSONObject

/** Static recipient; the main-frame owner Submit gesture authorizes one delivery. */
data class ArtifactReplyRequest(val recipient: String, val text: String, val answers: String? = null)
data class ArtifactReplyResult(val sent: Boolean, val message: String, val receipt: String? = null)
typealias ArtifactAnswerHandler = (ArtifactReplyRequest, (ArtifactReplyResult) -> Unit) -> Unit

object ArtifactReply {
    const val MAX_TEXT = 4096
    fun knownRecipient(recipient: String, macThreads: Set<String>): Boolean =
        recipient in setOf("phone:A", "phone:B", "phone:C") || (recipient.startsWith("mac:") && recipient in macThreads)
    private val target = Regex("[A-Za-z0-9:._-]{1,160}")
    private val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([\\w-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
    /** Static declaration only; scripts/frames cannot select or change the recipient. Duplicates fail closed. */
    private fun declaration(html: String, name: String): String? {
        val clean = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
        val head = Regex("<head\\b[^>]*>([\\s\\S]*?)</head\\s*>", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1) ?: return null
        val declarations = meta.findAll(head).mapNotNull { tag ->
            val attrs = attribute.findAll(tag.value).map { it.groupValues[1].lowercase() to it.groupValues[3] }.toList()
            if (attrs.count { it.first == "name" } != 1 || attrs.firstOrNull { it.first == "name" }?.second?.lowercase() != name) null
            else attrs.filter { it.first == "content" }.singleOrNull()?.second ?: ""
        }.toList()
        return declarations.singleOrNull()
    }
    fun recipient(html: String): String? = declaration(html, "soma-reply-to")?.takeIf(target::matches)

    internal fun canvasDeclaration(html: String): String? = declaration(html, "soma-canvas-cards")

    /** Called only for a main-frame navigation carrying a user gesture. A callback opens native review, never sends. */
    fun request(html: String, url: String, mainFrame: Boolean, userGesture: Boolean): ArtifactReplyRequest? {
        if (!mainFrame || !userGesture || !url.startsWith("soma-answer:") || url.length > MAX_TEXT * 12 + 20) return null
        val to = recipient(html) ?: return null
        val text = runCatching { URLDecoder.decode(url.removePrefix("soma-answer:"), "UTF-8") }.getOrNull() ?: return null
        if (text.isBlank() || text.length > MAX_TEXT || text.any { it == '\u0000' || it == '\u007f' || (it < ' ' && it !in "\n\r\t") }) return null
        if (text.startsWith("soma-json:")) {
            val answers = runCatching { JSONObject(text.removePrefix("soma-json:")) }.getOrNull() ?: return null
            val keys = answers.keys().asSequence().toList()
            if (keys.size !in 1..20 || keys.any { it.isBlank() || it.length > 160 || it.any { c -> c < ' ' } }) return null
            if (keys.any { key -> val v = answers.get(key); v !is String && v !is Boolean && (v !is org.json.JSONArray || (0 until v.length()).any { v.get(it) !is String }) }) return null
            val strings = keys.flatMap { key -> val v = answers.get(key); if (v is String || v is Boolean) listOf(v.toString()) else (0 until (v as org.json.JSONArray).length()).map { v.getString(it) } }
            if (strings.any { value -> value.any { it == '\u007f' || (it < ' ' && it !in "\n\r\t") } }) return null
            return ArtifactReplyRequest(to, keys.joinToString("\n") { key -> "$key: ${answers.get(key)}" }, answers.toString())
        }
        return ArtifactReplyRequest(to, text)
    }
}

/** A request to review a fixed set of proposed screens; never Canvas authorization. */
data class ArtifactCanvasRequest(val recipient: String, val app: String, val cards: List<String>, val version: String, val note: String?)

object ArtifactCanvasApproval {
    private val id = Regex("[a-z0-9][a-z0-9_-]{0,99}")
    private fun manifest(raw: String): Triple<String, List<String>, String>? = runCatching {
        val json = JSONObject(raw)
        if (json.keys().asSequence().toSet() != setOf("app", "cards", "version")) return null
        val app = json.get("app") as? String ?: return null
        val version = json.get("version") as? String ?: return null
        val array = json.getJSONArray("cards")
        val cards = (0 until array.length()).map { array.get(it) as? String ?: return null }
        if (!id.matches(app) || version.isBlank() || version.length > 80 || version.any { it < ' ' } ||
            cards.size !in 1..20 || cards.distinct().size != cards.size || cards.any { !id.matches(it) }) return null
        Triple(app, cards, version)
    }.getOrNull()

    fun declared(html: String): Boolean = ArtifactReply.recipient(html) != null &&
        ArtifactReply.canvasDeclaration(html)?.let(::manifest) != null

    fun request(html: String, url: String, mainFrame: Boolean, userGesture: Boolean): ArtifactCanvasRequest? {
        if (!mainFrame || !userGesture || !url.startsWith("soma-approve:") || url.length > 60000) return null
        val recipient = ArtifactReply.recipient(html) ?: return null
        val declared = ArtifactReply.canvasDeclaration(html)?.let(::manifest) ?: return null
        return runCatching {
            val json = JSONObject(URLDecoder.decode(url.removePrefix("soma-approve:"), "UTF-8"))
            if (json.keys().asSequence().any { it !in setOf("app", "cards", "version", "note") }) return null
            val fixed = JSONObject().put("app", json.get("app")).put("cards", json.getJSONArray("cards")).put("version", json.get("version"))
            if (manifest(fixed.toString()) != declared) return null
            val note = if (json.has("note")) json.get("note") as? String ?: return null else null
            if (note != null && (note.length > ArtifactReply.MAX_TEXT || note.any { it == '\u007f' || (it < ' ' && it !in "\n\r\t") })) return null
            ArtifactCanvasRequest(recipient, declared.first, declared.second, declared.third, note)
        }.getOrNull()
    }
}
