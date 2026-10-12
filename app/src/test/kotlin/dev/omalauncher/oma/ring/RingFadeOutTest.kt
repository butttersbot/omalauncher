package dev.omalauncher.oma.ring

import dev.omalauncher.theme.pack.ConsolePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring going off: it goes out over a short fade for a host that asks for one, and still cuts for one that doesn't. */
class RingFadeOutTest {
    private val ring = ConsolePack.pack.ring
    private val fade = 150L

    /** Lit and listening long enough to be fully up, then off at 10 s. */
    private fun wentOff(): RingTimeline =
        RingTimeline().also {
            it.at(RingState.Listening, 1000)
            it.at(RingState.Listening, 9000)
            it.at(RingState.Off, 10_000)
        }

    @Test fun theTimelineRemembersWhatItWentOffFrom() {
        val timeline = wentOff()
        assertEquals(RingMoment(RingState.Off, 0, 9000, RingState.Listening, 9000), timeline.at(RingState.Off, 10_000))
        assertEquals(RingMoment(RingState.Off, 100, 9100, RingState.Listening, 9000), timeline.at(RingState.Off, 10_100))
        // Lit again, that is forgotten: a lit moment is as it always was.
        assertEquals(RingMoment(RingState.Working, 0, 0), timeline.at(RingState.Working, 20_000))
        // And a ring that was never lit went off from nothing.
        assertEquals(RingMoment(RingState.Off, 50, 50), RingTimeline().at(RingState.Off, 50))
    }

    @Test fun offGoesOutInsteadOfCutting() {
        val timeline = wentOff()
        val lit = ringFrame(ring, RingMoment(RingState.Listening, 9000, 9000), 1f)!!
        // As it goes off it is exactly what it was: nothing jumps.
        val first = ringFrame(ring, timeline.at(RingState.Off, 10_000), 1f, fade)!!
        assertEquals(lit.alpha, first.alpha, 0.001f)
        assertEquals(lit.color1, first.color1)
        assertEquals(lit.width, first.width, 0f)
        // Then only dimmer, to nothing.
        var last = first.alpha
        for (t in 10L..140L step 10L) {
            val frame = ringFrame(ring, timeline.at(RingState.Off, 10_000 + t), 1f, fade)!!
            assertTrue("at $t", frame.alpha < last)
            last = frame.alpha
        }
        assertEquals(lit.alpha / 2f, ringFrame(ring, timeline.at(RingState.Off, 10_075), 1f, fade)!!.alpha, 0.001f)
        assertNull(ringFrame(ring, timeline.at(RingState.Off, 10_150), 1f, fade))
        assertNull(ringFrame(ring, timeline.at(RingState.Off, 60_000), 1f, fade))
    }

    @Test fun theLightRunsOnAsItGoesOut() {
        val timeline = wentOff()
        val period = ring.listening.periodMs
        if (period <= 0) return
        val frame = ringFrame(ring, timeline.at(RingState.Off, 10_050), 1f, fade)!!
        assertEquals(ringAngle(period, 9050), frame.angle, 0.001f)
    }

    @Test fun aHostThatAsksForNoFadeStillCuts() {
        val timeline = wentOff()
        assertNull(ringFrame(ring, timeline.at(RingState.Off, 10_000), 1f))
        assertNull(ringFrame(ring, timeline.at(RingState.Off, 10_000), 1f, 0))
        assertEquals(0f, fadeOut(0, 0), 0f)
    }

    @Test fun framesStopOnceItIsDark() {
        val timeline = wentOff()
        assertFalse(timeline.at(RingState.Off, 10_000).dark(fade))
        assertFalse(timeline.at(RingState.Off, 10_149).dark(fade))
        assertTrue(timeline.at(RingState.Off, 10_150).dark(fade))
        // With no fade, or never lit, it is dark at once: nothing keeps drawing.
        assertTrue(timeline.at(RingState.Off, 10_000).dark(0))
        assertTrue(RingTimeline().at(RingState.Off, 5).dark(fade))
        // Lit is never dark.
        assertFalse(RingMoment(RingState.Working, 0, 0).dark(fade))
        assertNotNull(ringFrame(ring, RingMoment(RingState.Working, 5000, 5000), 1f, fade))
    }

    @Test fun whatIsLeftOfTheLight() {
        assertEquals(1f, fadeOut(0, fade), 0f)
        assertEquals(0.5f, fadeOut(75, fade), 0.001f)
        assertEquals(0f, fadeOut(150, fade), 0f)
        assertEquals(0f, fadeOut(10_000, fade), 0f)
        assertEquals(1f, fadeOut(-20, fade), 0f)
    }
}
