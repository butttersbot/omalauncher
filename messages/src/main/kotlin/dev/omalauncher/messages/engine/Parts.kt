package dev.omalauncher.messages.engine

/**
 * A message split the way iMessage shows it: each link or attachment its own bubble, before the words. OpenBubbles'
 * notifications carry text only, so a photo arrives as its summary ("1 Photo", "2 Photos & 1 Video") and a shared
 * page as "Website: Title (domain)"; those become bubbles that open the conversation in OpenBubbles.
 */
sealed interface Part {
    /** What was written, links taken out. */
    data class Words(
        val text: String,
    ) : Part

    /** A web address in the words. */
    data class Link(
        val url: String,
    ) : Part {
        val host get() = Parts.host(url)
        val path get() = Parts.path(url)
    }

    /** A page OpenBubbles previewed, known by its title and domain only. */
    data class Website(
        val title: String,
        val host: String,
    ) : Part

    /** Photos, videos and the like, known by OpenBubbles' summary of them ("2 Photos"). */
    data class Attachment(
        val summary: String,
    ) : Part {
        /** What the first thing is: Photo, Video, GIF, PDF, Audio message, Sticker… */
        val kind get() = summary.substringAfter(' ', "").substringBefore(" & ").substringBefore(",").trim().removeSuffix("s").ifEmpty { "Attachment" }
    }
}

object Parts {
    private val url = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

    // OpenBubbles' attachment summaries: "1 Photo", "2 Photos & 1 Video", "1 Contact card, 1 Location, 2 PDFs", "Attachment".
    private val kinds = "Photo|Video|GIF|PDF|Audio message|Contact card|Contact|Location|Sticker|Link|File|Image|Application|Text"
    private val one = """\d+ (?:$kinds)s?"""
    private val summary = Regex("""^(?:Attachment|$one(?:(?: & |, )$one)*)$""")
    private val website = Regex("""^Website: (.+?)(?: \(([^()\s]+)\))?$""")

    /** [text] in the order iMessage shows it. */
    fun of(text: String): List<Part> {
        val t = text.trim()
        if (summary.matches(t)) return listOf(Part.Attachment(t))
        website.matchEntire(t)?.let { return listOf(Part.Website(it.groupValues[1], it.groupValues[2])) }
        val links = links(t)
        if (links.isEmpty()) return listOf(Part.Words(t))
        var words = t
        links.forEach { words = words.replace(it, " ") }
        // The punctuation that ended the address goes with it.
        words = words.replace(Regex("""[ \t]+[.,;:!?)]+(?=\s|$)"""), "").replace(Regex("""[ \t]{2,}"""), " ").lines().joinToString("\n") { it.trim() }.trim()
        return links.map { Part.Link(it) } + listOfNotNull(words.takeIf { it.isNotEmpty() }?.let { Part.Words(it) })
    }

    /** The web addresses in [text], in order, without trailing punctuation. */
    fun links(text: String): List<String> = url.findAll(text).map { it.value.trimEnd('.', ',', '!', '?', ')', ';', ':', '\'', '"') }.filter { it.length > 4 }.distinct().toList()

    /** [url]'s site, without www. */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').removePrefix("www.").lowercase()

    /** What follows the site, or "" for the site itself. */
    fun path(url: String): String = url.substringAfter("://").substringAfter('/', "").trimEnd('/')

    /** [url] ready to open: an address typed as www.… gets https://. */
    fun openable(url: String): String = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
}
