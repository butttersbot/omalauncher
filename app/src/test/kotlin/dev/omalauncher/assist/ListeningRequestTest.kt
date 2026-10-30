package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val waitingForHim = ListeningRequest(ListeningPhase.NeedsOk, detail = "Oma is waiting for your answer")
        assertEquals(waitingForHim, waitingForHim.spoke("for nine"))
    }

    /** What he changed by hand is what goes, and what saying more adds to; cleared, Send does nothing. */
    @Test fun editedWordsAreTheOnesSent() {
        val waiting = ListeningRequest(ListeningPhase.Heard, "book a table for to").edited("book a table for two")
        assertEquals(ListeningStep(null, ListeningEffect.Send("book a table for two")), waiting.act(ListeningAct.Send))
        assertEquals(ListeningRequest(ListeningPhase.Listening, "book a table for two"), waiting.act(ListeningAct.Add).next)
        val cleared = waiting.edited("  ")
        assertEquals(ListeningStep(cleared, null), cleared.act(ListeningAct.Send))
        val listening = ListeningRequest(ListeningPhase.Listening, "book")
        assertEquals(listening, listening.edited("something else"))
    }

    @Test fun theOtherPhasesKeepTheirWaysOut() {
        assertEquals(ListeningStep(null, ListeningEffect.Leave), ListeningRequest(ListeningPhase.NeedsOk).act(ListeningAct.Close))
        assertEquals(ListeningStep(null, ListeningEffect.Review), ListeningRequest(ListeningPhase.NeedsOk).act(ListeningAct.Review))
        assertEquals(ListeningStep(null, ListeningEffect.Allow), ListeningRequest(ListeningPhase.NoMic).act(ListeningAct.Allow))
    }

    /**
     * Changed on purpose (Boss, 2026-10-13: "I shouldn't have to wait for a job to finish to be able to talk to
     * the AI"). There used to be a phase for a running task, which took no request and offered Stop and Close.
     * It is gone: no phase stands for a task that runs and no tap on this screen stops one. What waits for his
     * answer is as it was. Sending and Typed are the words on their way to a device, on the MG1 screen; no task either.
     */
    @Test fun aRunningTaskIsNoPhaseOfTheScreenAndAnApprovalStillIs() {
        assertEquals(
            listOf(ListeningPhase.Listening, ListeningPhase.Heard, ListeningPhase.Missed, ListeningPhase.Failed, ListeningPhase.NoMic, ListeningPhase.NeedsOk, ListeningPhase.Sending, ListeningPhase.Typed),
            ListeningPhase.entries.toList(),
        )
        assertTrue(ListeningAct.entries.none { it.label == "Stop" })
        // While an approval waits nothing is heard and nothing is sent: only Review and Close do anything.
        val waitingForHim = ListeningRequest(ListeningPhase.NeedsOk, heard = "do it anyway", detail = "Oma is waiting for your answer")
        for (act in listOf(ListeningAct.Send, ListeningAct.Add)) assertEquals(ListeningStep(waitingForHim, null), waitingForHim.act(act))
        assertEquals("Oma is waiting for your answer", waitingForHim.look().words)
    }
}
