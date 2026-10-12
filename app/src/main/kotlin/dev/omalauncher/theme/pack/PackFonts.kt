package dev.omalauncher.theme.pack

/** The weights a variable font is asked for, so each is drawn from the font's own outlines. */
val FONT_WEIGHTS = (100..900 step 100).toList()

/**
 * Whether the font file in [bytes] is a variable font: it has an `fvar` table in its table directory.
 * Anything too short or not laid out like a font is not.
 */
fun fontHasVariations(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val tables = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
    for (i in 0 until tables) {
        val at = 12 + 16 * i
        if (at + 4 > bytes.size) return false
        if (bytes[at] == 'f'.code.toByte() && bytes[at + 1] == 'v'.code.toByte() && bytes[at + 2] == 'a'.code.toByte() && bytes[at + 3] == 'r'.code.toByte()) return true
    }
    return false
}
