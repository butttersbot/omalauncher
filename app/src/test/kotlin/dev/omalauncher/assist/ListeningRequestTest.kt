package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningRequestTest {
    /** The screen as its host drives it: the request while it is up, and everything that went to the agent. */
    private class Screen {
        var request: ListeningRequest? = ListeningRequest(ListeningPhase.Listening)
        val sent = mutableListOf<String>()
        var listens = 0

        fun tap(act: ListeningAct) {
            val step = request?.act(act) ?: return
            request = step.next
            when (val effect = step.effect) {
                is ListeningEffect.Send -> sent += effect.text
                ListeningEffect.Listen -> listens++
                else -> {}
            }
        }

        fun spoke(said: String, error: String? = null) {
            request = request?.spoke(said, error)
        }
    }

    @Test fun cancelWhileListeningSendsNothing() {
        val screen = Screen()
        screen.tap(ListeningAct.Cancel)
        assertNull(screen.request)
        assertEquals(emptyList<String>(), screen.sent)
        assertEquals(ListeningStep(null, ListeningEffect.Leave), ListeningRequest(ListeningPhase.Listening, "tell Mina").act(ListeningAct.Cancel))
    }

    @Test fun cancelWhileTheWordsWaitSendsNothing() {
        val screen = Screen()
        screen.spoke("tell Mina I'm late")
        screen.tap(ListeningAct.Cancel)
        assertNull(screen.request)
        assertEquals(emptyList<String>(), screen.sent)
        // Words that came after the way out are from a screen that has gone.
        screen.spoke("and send it")
        screen.tap(ListeningAct.Send)
        assertEquals(emptyList<String>(), screen.sent)
    }

    /** The microphone closing only moves the words to waiting: no effect comes of it at all. */
    @Test fun theEndOfSpeechSendsNothing() {
        val screen = Screen()
        screen.spoke("open my trip page")
        assertEquals(ListeningRequest(ListeningPhase.Heard, "open my trip page"), screen.request)
        assertEquals(emptyList<String>(), screen.sent)
    }

    @Test fun sendWhileTheWordsWaitSendsThemOnce() {
        val screen = Screen()
        screen.spoke(" open my trip page ")
        screen.tap(ListeningAct.Send)
        assertEquals(listOf("open my trip page"), screen.sent)
        assertNull(screen.request)
        // A second tap as the screen goes finds nothing to send.
        screen.tap(ListeningAct.Send)
        assertEquals(listOf("open my trip page"), screen.sent)
    }

    /** Send is only ever offered with the waiting words; anywhere else it does nothing. */
    @Test fun sendDoesNothingUnlessWordsAreWaiting() {
        for (phase in ListeningPhase.entries.filter { it != ListeningPhase.Heard }) {
            val request = ListeningRequest(phase, heard = "tell Mina", detail = "using Bash…")
            assertEquals(ListeningStep(request, null), request.act(ListeningAct.Send))
            assertEquals(ListeningStep(request, null), request.act(ListeningAct.Add))
        }
        val empty = ListeningRequest(ListeningPhase.Heard)
        assertEquals(ListeningStep(empty, null), empty.act(ListeningAct.Send))
    }

    @Test fun addingAppendsWithOneSpace() {
        val screen = Screen()
        screen.spoke("book a table ")
        screen.tap(ListeningAct.Add)
        assertEquals(1, screen.listens)
        assertEquals(ListeningRequest(ListeningPhase.Listening, "book a table"), screen.request)
        // The earlier words stay written while the new ones come in after them.
        assertEquals("book a table", screen.request?.look()?.words)
        assertEquals("book a table for", screen.request?.look(hearing = "for")?.words)
        screen.spoke(" for two")
        assertEquals(ListeningRequest(ListeningPhase.Heard, "book a table for two"), screen.request)
        assertEquals(emptyList<String>(), screen.sent)
        screen.tap(ListeningAct.Send)
        assertEquals(listOf("book a table for two"), screen.sent)
    }

    /** Silence, or a recognizer that fails, while adding costs him nothing he had already said. */
    @Test fun addingThatHearsNothingKeepsTheEarlierWords() {
        val screen = Screen()
        screen.spoke("book a table")
        screen.tap(ListeningAct.Add)
        screen.spoke("")
        assertEquals(ListeningRequest(ListeningPhase.Heard, "book a table"), screen.request)
        screen.tap(ListeningAct.Add)
        screen.spoke("  ", error = "speech recognition failed (5)")
        assertEquals(ListeningRequest(ListeningPhase.Heard, "book a table"), screen.request)
        screen.tap(ListeningAct.Send)
        assertEquals(listOf("book a table"), screen.sent)
    }

    @Test fun aFirstHearingOfNothingIsMissedOrFailed() {
        assertEquals(ListeningRequest(ListeningPhase.Missed), ListeningRequest(ListeningPhase.Listening).spoke(""))
        assertEquals(
            ListeningRequest(ListeningPhase.Failed, detail = "microphone permission needed"),
            ListeningRequest(ListeningPhase.Listening).spoke("", "microphone permission needed"),
        )
    }

    /** Try again starts over; a late answer from the recognizer changes nothing once the microphone has closed. */
    @Test fun tryAgainStartsOverAndALateAnswerIsDropped() {
        assertEquals(ListeningStep(ListeningRequest(ListeningPhase.Listening), ListeningEffect.Listen), ListeningRequest(ListeningPhase.Failed, detail = "no network").act(ListeningAct.Retry))
        val waiting = ListeningRequest(ListeningPhase.Heard, "book a table")
        assertEquals(waiting, waiting.spoke("for nine"))
        val busy = ListeningRequest(ListeningPhase.Busy, detail = "using Bash…")
        assertEquals(busy, busy.spoke("for nine"))
    }

    @Test fun theOtherPhasesKeepTheirWaysOut() {
        assertEquals(ListeningStep(null, ListeningEffect.Leave), ListeningRequest(ListeningPhase.Busy).act(ListeningAct.Close))
        assertEquals(ListeningStep(null, ListeningEffect.Stop), ListeningRequest(ListeningPhase.Busy).act(ListeningAct.Stop))
        assertEquals(ListeningStep(null, ListeningEffect.Review), ListeningRequest(ListeningPhase.NeedsOk).act(ListeningAct.Review))
        assertEquals(ListeningStep(null, ListeningEffect.Allow), ListeningRequest(ListeningPhase.NoMic).act(ListeningAct.Allow))
    }
}
