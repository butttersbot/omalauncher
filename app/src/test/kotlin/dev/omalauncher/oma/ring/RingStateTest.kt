package dev.omalauncher.oma.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingStateTest {
    private val idle = RingSignals()

    @Test fun nothingHappeningIsOff() {
        assertEquals(RingState.Off, ringState(idle, now = 0))
        assertEquals(RingState.Off, ringState(idle, now = 1_000_000))
    }

    @Test fun eachSignalAloneGivesItsState() {
        assertEquals(RingState.Listening, ringState(idle.copy(listening = true), 0))
        assertEquals(RingState.Working, ringState(idle.copy(working = true), 0))
        assertEquals(RingState.NeedsYou, ringState(idle.copy(working = true, pending = 1), 0))
        assertEquals(RingState.Done, ringState(idle.copy(doneAt = 0), 10))
    }

    @Test fun needsYouBeatsWorkingBeatsListeningBeatsDone() {
        val everything = RingSignals(listening = true, working = true, pending = 2, doneAt = 0)
        assertEquals(RingState.NeedsYou, ringState(everything, 1))
        assertEquals(RingState.Working, ringState(everything.copy(pending = 0), 1))
        assertEquals(RingState.Listening, ringState(everything.copy(pending = 0, working = false), 1))
        assertEquals(RingState.Done, ringState(everything.copy(pending = 0, working = false, listening = false), 1))
    }

    @Test fun doneShowsForItsTimeAndThenGoesOff() {
        val done = idle.copy(doneAt = 1000)
        assertEquals(RingState.Done, ringState(done, 1000, doneFor = 3000))
        assertEquals(RingState.Done, ringState(done, 3999, doneFor = 3000))
        assertEquals(RingState.Off, ringState(done, 4000, doneFor = 3000))
        // A clock that reads earlier than the finish (it never should) shows nothing rather than done for ever.
        assertEquals(RingState.Off, ringState(done, 999, doneFor = 3000))
    }

    @Test fun aTaskThatEndsWellIsDoneFromThatMoment() {
        val running = idle.afterTask(working = true, pending = 0, succeeded = false, now = 10)
        assertEquals(RingState.Working, ringState(running, 20))
        val ended = running.afterTask(working = false, pending = 0, succeeded = true, now = 500)
        assertEquals(500L, ended.doneAt)
        assertEquals(RingState.Done, ringState(ended, 600))
    }

    @Test fun aTaskThatWasStoppedOrFailedJustGoesDark() {
        val ended = idle.copy(working = true).afterTask(working = false, pending = 0, succeeded = false, now = 500)
        assertNull(ended.doneAt)
        assertEquals(RingState.Off, ringState(ended, 501))
    }

    @Test fun doneStartsOnlyWhenARunningTaskEnds() {
        // Told again that nothing runs (a new thread, a tile refresh): no done out of nowhere, and a showing done isn't restarted.
        assertNull(idle.afterTask(working = false, pending = 0, succeeded = true, now = 500).doneAt)
        val ended = idle.copy(doneAt = 100)
        assertEquals(100L, ended.afterTask(working = false, pending = 0, succeeded = true, now = 900).doneAt)
    }

    @Test fun aNewTaskOrANewRequestPutsDoneAway() {
        val ended = idle.copy(doneAt = 100)
        assertNull(ended.afterTask(working = true, pending = 0, succeeded = false, now = 200).doneAt)
        val heard = ended.afterHearing(true)
        assertNull(heard.doneAt)
        // Once he stops talking the ring is off, not back to an old done.
        assertEquals(RingState.Off, ringState(heard.afterHearing(false), 150))
    }

    @Test fun approvalsComeAndGoWithinATask() {
        val running = idle.afterTask(working = true, pending = 0, succeeded = false, now = 0)
        val asked = running.afterTask(working = true, pending = 1, succeeded = false, now = 5)
        assertEquals(RingState.NeedsYou, ringState(asked, 6))
        val answered = asked.afterTask(working = true, pending = 0, succeeded = false, now = 9)
        assertEquals(RingState.Working, ringState(answered, 10))
        // An approval still waiting when the task ends goes with it.
        assertEquals(RingState.Off, ringState(asked.afterTask(working = false, pending = 0, succeeded = false, now = 11), 12))
        assertEquals(0, running.afterTask(working = true, pending = -3, succeeded = false, now = 5).pending)
    }

    @Test fun theSharedSignalsFollowWhatIsReported() {
        // The one holder every host watches; its clock is passed in here.
        var seen = 0
        val unwatch = OmaRing.watch { seen++ }
        try {
            OmaRing.task(working = true, pending = 0, succeeded = false, now = 10)
            assertEquals(RingState.Working, ringState(OmaRing.signals, 11))
            OmaRing.task(working = true, pending = 1, succeeded = false, now = 12)
            assertEquals(RingState.NeedsYou, ringState(OmaRing.signals, 13))
            // The same thing reported twice tells nobody.
            val before = seen
            OmaRing.task(working = true, pending = 1, succeeded = false, now = 14)
            assertEquals(before, seen)
            OmaRing.task(working = false, pending = 0, succeeded = true, now = 20)
            assertEquals(RingState.Done, ringState(OmaRing.signals, 21))
            val a = Any()
            val b = Any()
            OmaRing.hearing(a, true)
            OmaRing.hearing(b, true)
            OmaRing.hearing(a, false)
            assertEquals(RingState.Listening, ringState(OmaRing.signals, 22))
            OmaRing.hearing(b, false)
            assertEquals(RingState.Off, ringState(OmaRing.signals, 23))
            assertEquals(true, seen >= 4)
        } finally {
            unwatch()
        }
        val after = seen
        OmaRing.assistant(true)
        OmaRing.assistant(false)
        assertEquals(after, seen)
    }
}
