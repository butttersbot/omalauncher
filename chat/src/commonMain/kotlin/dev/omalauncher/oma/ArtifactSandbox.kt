package dev.omalauncher.oma

/**
 * What every renderer of a generated HTML artifact shares, the Fold's WebView and the desktop's Chromium alike: the page is
 * served at one fake origin that reaches nothing, and gets no frames or plugins.
 */
internal const val ARTIFACT_ORIGIN = "https://soma-artifact.invalid/"

internal fun isolatedArtifactHtml(html: String): String {
    val policy = """<meta http-equiv="Content-Security-Policy" content="frame-src 'none'; object-src 'none'">"""
    val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)
    val offset = head?.range?.last?.plus(1) ?: Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)?.range?.last?.plus(1) ?: 0
    return html.substring(0, offset) + policy + html.substring(offset)
}
