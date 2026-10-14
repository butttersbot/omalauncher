package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningLookTest {
    @Test fun listeningShowsWhatIsHeardWithOneWayOut() {
        val look = listeningLook(ListeningPhase.Listening, heard = "tell Mina I'm late")
        assertEquals("Listening", look.caption)
        assertEquals("tell Mina I'm late", look.words)
        assertEquals(listOf(ListeningAct.Cancel), look.acts)
    }

    /** The words that wait keep their place, with sending first, then saying more, then the way out. */
    @Test fun whatWasHeardWaitsWithSendAddAndAWayOut() {
        val look = listeningLook(ListeningPhase.Heard, heard = "open my trip page", note = "with this screen")
        assertEquals("Send this?", look.caption)
        assertEquals("open my trip page", look.words)
        assertEquals(listOf(ListeningAct.Send, ListeningAct.Add, ListeningAct.Cancel), look.acts)
        assertEquals("with this screen", look.note)
    }

    /** Only the words that wait for his go can be changed by hand: while the microphone is open they are still coming. */
    @Test fun onlyTheWaitingWordsCanBeEdited() {
        assertEquals(true, listeningLook(ListeningPhase.Heard, heard = "open my trip page").editable)
        for (phase in ListeningPhase.entries.filter { it != ListeningPhase.Heard }) assertEquals(false, listeningLook(phase, heard = "x", detail = "y").editable)
    }

    @Test fun theScreenNoteShowsOnlyWhileARequestIsBeingTaken() {
        assertEquals("with this screen", listeningLook(ListeningPhase.Listening, note = "with this screen").note)
        assertEquals("with this screen", listeningLook(ListeningPhase.Missed, note = "with this screen").note)
        for (phase in listOf(ListeningPhase.NoMic, ListeningPhase.NeedsOk)) assertEquals("", listeningLook(phase, note = "with this screen").note)
    }

    @Test fun aProblemOffersAnotherTryAndAWayOut() {
        assertEquals(listOf(ListeningAct.Retry, ListeningAct.Cancel), listeningLook(ListeningPhase.Missed).acts)
        val failed = listeningLook(ListeningPhase.Failed, detail = "speech recognition needs a network connection")
        assertEquals("speech recognition needs a network connection", failed.words)
        assertEquals(listOf(ListeningAct.Retry, ListeningAct.Cancel), failed.acts)
        assertEquals(listOf(ListeningAct.Allow, ListeningAct.Cancel), listeningLook(ListeningPhase.NoMic).acts)
    }

    /**
     * Nothing new is taken while an approval waits: the screen says so and offers what fits. Changed on purpose
     * (Boss, 2026-10-13): a task that runs no longer does the same, so the look it had ("Working", Stop, Close) is gone.
     */
    @Test fun aWaitingApprovalTakesNoRequest() {
        val waiting = listeningLook(ListeningPhase.NeedsOk, heard = "ignored", detail = "Oma is waiting for your answer")
        assertEquals("Needs your OK", waiting.caption)
        assertEquals("Oma is waiting for your answer", waiting.words)
        assertEquals(listOf(ListeningAct.Review, ListeningAct.Close), waiting.acts)
    }

    @Test fun theMarginNoteIsAFewLowerCaseWordsThatTrailOff() {
        assertEquals("working…", marginNote("Claude is working"))
        assertEquals("working…", marginNote("Agent is working"))
        assertEquals("working…", marginNote(""))
        assertEquals("using Bash…", marginNote("Using Bash"))
        assertEquals("stopping…", marginNote("Stopping…"))
        assertEquals("needs your OK", marginNote("Approval needed"))
    }
}
