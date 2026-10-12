package dev.omalauncher.theme.pack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackFontsTest {
    private fun font(path: String) = moduleFile("src/main/assets/packs/$path").readBytes()

    /** The fonts every look shares come with all their weights, so nothing bold is a thickened regular. */
    @Test fun theSharedFontsAreVariable() {
        for (name in listOf("SchibstedGrotesk", "Caveat", "JetBrainsMono")) assertTrue(name, fontHasVariations(font("riso/fonts/$name.ttf")))
    }

    @Test fun aSingleFaceIsNot() = assertFalse(fontHasVariations(font("riso/fonts/DelaGothicOne-Regular.ttf")))

    @Test fun whatIsNotAFontIsNot() {
        assertFalse(fontHasVariations(ByteArray(0)))
        assertFalse(fontHasVariations(ByteArray(11)))
        assertFalse(fontHasVariations("fvar is only a word here".toByteArray()))
    }
}
