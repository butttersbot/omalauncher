package dev.omalauncher.oma

import java.net.URLDecoder

/** Untrusted artifact content may propose exactly one reply. This is never authorization to send. */
data class ArtifactReplyRequest(val recipient: String, val text: String)

object ArtifactReply {
    const val MAX_TEXT = 4096
    private val target = Regex("[A-Za-z0-9:._-]{1,160}")
    private val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([\\w-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
    /** Static declaration only; scripts/frames cannot select or change the recipient. Duplicates fail closed. */
    fun recipient(html: String): String? {
        val clean = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
        val head = Regex("<head\\b[^>]*>([\\s\\S]*?)</head\\s*>", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1) ?: return null
        val declarations = meta.findAll(head).mapNotNull { tag ->
            val attrs = attribute.findAll(tag.value).map { it.groupValues[1].lowercase() to it.groupValues[3] }.toList()
            if (attrs.count { it.first == "name" } != 1 || attrs.firstOrNull { it.first == "name" }?.second?.lowercase() != "soma-reply-to") null
            else attrs.filter { it.first == "content" }.singleOrNull()?.second ?: ""
        }.toList()
        return declarations.singleOrNull()?.takeIf(target::matches)
    }
    /** Called only for a main-frame navigation carrying a user gesture. A callback opens native review, never sends. */
    fun request(html: String, url: String, mainFrame: Boolean, userGesture: Boolean): ArtifactReplyRequest? {
        if (!mainFrame || !userGesture || !url.startsWith("soma-answer:") || url.length > MAX_TEXT * 12 + 20) return null
        val to = recipient(html) ?: return null
        val text = runCatching { URLDecoder.decode(url.removePrefix("soma-answer:"), "UTF-8") }.getOrNull() ?: return null
        if (text.isBlank() || text.length > MAX_TEXT || text.any { it == '\u0000' || it == '\u007f' || (it < ' ' && it !in "\n\r\t") }) return null
        return ArtifactReplyRequest(to, text)
    }
}
