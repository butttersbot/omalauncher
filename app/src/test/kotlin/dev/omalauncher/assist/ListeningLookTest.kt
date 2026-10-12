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

    /** The beat before the words go looks no different, and Cancel still stops them. */
    @Test fun theBeatBeforeSendingLooksLikeListening() {
        assertEquals(listeningLook(ListeningPhase.Listening, heard = "open my trip page"), listeningLook(ListeningPhase.Sending, heard = "open my trip page"))
    }

    @Test fun theScreenNoteShowsOnlyWhileARequestIsBeingTaken() {
        assertEquals("with this screen", listeningLook(ListeningPhase.Listening, note = "with this screen").note)
        assertEquals("with this screen", listeningLook(ListeningPhase.Missed, note = "with this screen").note)
        for (phase in listOf(ListeningPhase.NoMic, ListeningPhase.Busy, ListeningPhase.NeedsOk)) assertEquals("", listeningLook(phase, note = "with this screen").note)
    }

    @Test fun aProblemOffersAnotherTryAndAWayOut() {
        assertEquals(listOf(ListeningAct.Retry, ListeningAct.Cancel), listeningLook(ListeningPhase.Missed).acts)
        val failed = listeningLook(ListeningPhase.Failed, detail = "speech recognition needs a network connection")
        assertEquals("speech recognition needs a network connection", failed.words)
        assertEquals(listOf(ListeningAct.Retry, ListeningAct.Cancel), failed.acts)
        assertEquals(listOf(ListeningAct.Allow, ListeningAct.Cancel), listeningLook(ListeningPhase.NoMic).acts)
    }

    /** Nothing new is taken while a task runs or an approval waits: the screen says so and offers what fits. */
    @Test fun aRunningTaskOrAWaitingApprovalTakesNoRequest() {
        val busy = listeningLook(ListeningPhase.Busy, heard = "ignored", detail = "using Bash…")
        assertEquals("using Bash…", busy.words)
        assertEquals(listOf(ListeningAct.Stop, ListeningAct.Close), busy.acts)
        assertEquals(listOf(ListeningAct.Review, ListeningAct.Close), listeningLook(ListeningPhase.NeedsOk).acts)
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
