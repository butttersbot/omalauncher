package dev.omalauncher.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheepNoteTest {
    @Test
    fun shortWordsAreSaidAsTheyAre() = assertEquals("Sent to Soma", sheepNoteText("Sent to Soma"))

    @Test
    fun markdownComesOffAndLinesJoin() =
        assertEquals("Fixed: Tellah's card says Tellah. See the chat", sheepNoteText("**Fixed:** Tellah's card says Tellah.\n\nSee [the chat](soma://chat)"))

    @Test
    fun aLongAnswerIsCutAtAWord() {
        val note = sheepNoteText("word ".repeat(100))
        assertTrue(note.length <= SHEEP_NOTE_CHARS + 1)
        assertTrue(note.endsWith("word…"))
    }
}
