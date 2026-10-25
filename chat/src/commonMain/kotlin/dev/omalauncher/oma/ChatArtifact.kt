package dev.omalauncher.oma

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** A reply's output, never a directory scan. Paths are read only after the owner opens one. */
data class ChatArtifact(
    val id: String,
    val name: String,
    val location: String,
    val thread: String,
    val kind: ArtifactKind,
    val inline: String? = null,
    /** For an agent-made artifact ([SOMA_ARTIFACT]), the newest version the chat has heard of; 0 for every other output. */
    val version: Int = 0,
) {
    /** Made on purpose with `soma-artifact`, kept with every version on its computer (SomaArtifacts.kt). */
    val soma get() = location.startsWith(SOMA_ARTIFACT)
}

enum class ArtifactKind { Html, Markdown, Code, Image, Web }

/** [v] and [latest] are an agent-made artifact's version numbers, as its computer said them; 0 for any other output. */
data class ArtifactDocument(val name: String, val mime: String, val bytes: ByteArray, val version: String, val v: Int = 0, val latest: Int = 0, val title: String = "") {
    val text: String get() = bytes.toString(Charsets.UTF_8)
    val readingText: String get() = text.let { if (it.length <= 128_000) it else it.take(128_000) + "\n\n… Preview limited to 128,000 characters." }
}

sealed interface ArtifactRead {
    data class Ready(val document: ArtifactDocument) : ArtifactRead
    data class Failed(val reason: String) : ArtifactRead
}

private val artifactExtensions = setOf("html", "htm", "md", "markdown", "txt", "json", "diff", "patch", "kt", "kts", "py", "js", "ts", "tsx", "jsx", "css", "xml", "yaml", "yml", "csv", "svg", "png", "jpg", "jpeg", "webp", "gif")

fun artifactPath(link: String): String? = runCatching {
    if (Markdown.isWebLink(link)) return null
    val plain = if (link.startsWith("file:")) {
        val uri = URI(link)
        if (!uri.host.isNullOrBlank()) return null
        uri.path
    } else {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) return null
        // URLDecoder is for form data: a literal '+' in a filename is not a space.
        URLDecoder.decode(link.substringBefore('#').replace("+", "%2B"), "UTF-8")
    }
    val path = plain.replace(Regex(":\\d+(?::\\d+)?$"), "")
    path.takeIf { it.isNotBlank() && '\u0000' !in it && it.substringAfterLast('.').lowercase() in artifactExtensions }
}.getOrNull()

fun artifactKind(name: String, mime: String = ""): ArtifactKind = when {
    mime.startsWith("image/") && mime != "image/svg+xml" -> ArtifactKind.Image
    name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") -> ArtifactKind.Image
    name.substringAfterLast('.').lowercase() in setOf("html", "htm") -> ArtifactKind.Html
    name.substringAfterLast('.').lowercase() in setOf("md", "markdown") -> ArtifactKind.Markdown
    else -> ArtifactKind.Code
}

/** The same stable id survives edits; selection follows the new version of the output. */
fun chatArtifacts(text: String, origin: String, thread: String): List<ChatArtifact> {
    val result = ArrayList<ChatArtifact>()
    var fence = 0
    fun spans(items: List<MdSpan>) {
        for (span in items) {
            val link = span.link ?: continue
            val path = artifactPath(link)
            if (path != null) result += ChatArtifact("$thread:file:$path", path.substringAfterLast('/'), path, thread, artifactKind(path))
            else if (Markdown.isWebLink(link) && runCatching { URI(link).host != null && URI(link).userInfo == null }.getOrDefault(false)) {
                result += ChatArtifact("$thread:web:$link", span.text.ifBlank { URI(link).host }, link, thread, ArtifactKind.Web)
            }
        }
    }
    fun blocks(items: List<MdBlock>) {
        for (block in items) when (block) {
            is MdBlock.Code -> {
                val language = block.language.trim().lowercase().substringBefore(' ')
                val ext = when (language) { "html", "htm" -> "html"; "markdown", "md" -> "md"; else -> language.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "txt" }
                val n = ++fence
                result += ChatArtifact("$origin:code:$n", "snippet-$n.$ext", "inline:$origin:$n", thread, artifactKind("x.$ext"), block.code)
            }
            is MdBlock.Paragraph -> spans(block.spans)
            is MdBlock.Heading -> spans(block.spans)
            is MdBlock.Bullet -> spans(block.spans)
            is MdBlock.Numbered -> spans(block.spans)
            is MdBlock.Quote -> blocks(block.blocks)
            is MdBlock.Table -> { block.header.forEach(::spans); block.rows.flatten().forEach(::spans) }
            MdBlock.Rule -> Unit
        }
    }
    blocks(Markdown.parse(text))
    return result.distinctBy { it.id }.take(40)
}

fun inlineArtifact(artifact: ChatArtifact): ArtifactRead.Ready {
    val bytes = artifact.inline.orEmpty().toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
    return ArtifactRead.Ready(ArtifactDocument(artifact.name, "text/plain", bytes, hash))
}

/** Keep runner failures legible without reflecting its request, headers or private filesystem paths. */
fun decodeArtifact(code: Int, body: String): ArtifactRead = runCatching {
    if (code != 200) return ArtifactRead.Failed(when (code) {
        401 -> "Reconnect this computer in Soma settings."
        403 -> "This file is outside the conversation’s project or is a private configuration file."
        404 -> "This file could not be found. The computer may need the artifact update."
        413 -> "This file is too large to preview (4 MB limit)."
        415 -> "This file type cannot be previewed yet."
        else -> "The computer could not read this artifact. Try again."
    })
    require(body.length <= 6_000_000)
    val json = JSONObject(body)
    require(json.optBoolean("ok"))
    val bytes = Base64.getDecoder().decode(json.getString("data"))
    require(bytes.size <= 4 * 1024 * 1024)
    ArtifactRead.Ready(ArtifactDocument(json.getString("name"), json.getString("mime"), bytes, json.getString("version"),
        json.optInt("v"), json.optInt("latest"), if (json.isNull("title")) "" else json.optString("title").take(80)))
}.getOrElse { ArtifactRead.Failed("The computer returned an unreadable artifact. Try again.") }
