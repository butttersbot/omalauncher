package dev.omalauncher.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whisper voice input's plain parts (Boss, 2026-10-14: Whisper, with the words showing as he speaks). */
class WhisperTextTest {
    @Test fun readsTheBridgesSegmentsAndDropsWhatIsNotSpeech() {
        val raw = "0 240  Promote the launcher [BLANK_AUDIO]\n240 400  (music) after the build.\nnot a line\n400 500  [BLANK_AUDIO]\n"
        assertEquals(
            listOf(Heard(0, 240, "Promote the launcher"), Heard(240, 400, "after the build.")),
            parseHeard(raw),
        )
        assertEquals("ok go", cleanHeard(" *laughs* ok  (inaudible) go "))
    }

    @Test fun onlyWordsTwoReadingsAgreeOnAreSettled() {
        var live = LiveText()
        live = live.read("Turn on")
        assertEquals("", live.settled)
        live = live.read("Turn on tail")
        assertEquals("Turn on", live.settled)
        assertEquals("Turn on tail", live.text)
        // A comma or capital added later doesn't unsettle a word; a changed word does.
        live = live.read("turn on, Tailscale please")
        assertEquals("turn on,", live.settled)
        live = live.keep("Turn on Tailscale please.")
        assertEquals("Turn on Tailscale please.", live.settled)
        live = live.read("Then promote")
        assertEquals("Turn on Tailscale please. Then promote", live.text)
        assertEquals("Turn on Tailscale please.", live.settled)
    }

    @Test fun thePromptCarriesOmasWordsAndHisOwn() {
        val p = voicePrompt(listOf("Riso", "# a comment", " ", "Oma"))
        assertTrue(p.startsWith("Oma, Claude, Codex"))
        assertTrue(p.contains("Riso"))
        assertFalse(p.contains("comment"))
        assertEquals(1, Regex("Oma,").findAll(p).count())
    }

    @Test fun aPauseEndsOnlyWhatWasSpoken() {
        val quiet = List(20) { 0.01f }
        assertFalse("silence alone is no pause after speech", pauseEnded(quiet, 100))
        val spoke = List(5) { 0.5f } + List(13) { 0.02f }
        assertTrue(pauseEnded(spoke, 100))
        assertFalse(pauseEnded(List(5) { 0.5f } + List(6) { 0.02f }, 100))
    }
}
