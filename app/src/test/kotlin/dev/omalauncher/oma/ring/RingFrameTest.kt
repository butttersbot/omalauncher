package dev.omalauncher.oma.ring

import dev.omalauncher.theme.pack.ConsolePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingFrameTest {
    private val ring = ConsolePack.pack.ring

    /** A state that has been showing for [since] ms, on a ring lit long enough to be fully up. */
    private fun steady(state: RingState, since: Long = 10_000) = RingMoment(state, since, since + FADE_MILLIS)

    @Test fun offDrawsNothing() {
        assertNull(ringFrame(ring, RingMoment(RingState.Off, 0, 0), 3f))
        assertNull(ring.lookOf(RingState.Off))
    }

    @Test fun eachStateDrawsInThePacksLook() {
        for ((state, look) in listOf(RingState.Listening to ring.listening, RingState.Working to ring.working, RingState.NeedsYou to ring.needsYou)) {
            val frame = ringFrame(ring, steady(state), 1f)!!
            assertEquals(look.color1.argb, frame.color1)
            assertEquals(look.color2.argb, frame.color2)
            assertEquals(look.opacity, frame.alpha, 0.001f)
            assertEquals(ring.glowOpacity, frame.glowAlpha, 0.001f)
        }
    }

    @Test fun widthAndGlowAreThePacksDpOnTheScreensDensity() {
        val frame = ringFrame(ring, steady(RingState.Listening), 3f)!!
        assertEquals(ring.width * 3f, frame.width, 0.001f)
        assertEquals(ring.glow * 3f, frame.glow, 0.001f)
        assertNull(ringFrame(ring, steady(RingState.Listening), 0f))
        assertNull(ringFrame(ring.copy(width = 0f), steady(RingState.Listening), 3f))
    }

    @Test fun theLightGoesOnceAroundInItsPeriod() {
        assertEquals(0f, ringAngle(4200, 0), 0.001f)
        assertEquals(90f, ringAngle(4200, 1050), 0.001f)
        assertEquals(180f, ringAngle(4200, 2100), 0.001f)
        assertEquals(0f, ringAngle(4200, 4200), 0.001f)
        assertEquals(90f, ringAngle(4200, 4200 * 7 + 1050L), 0.001f)
        // Working turns slower than listening, as the pack says.
        assertTrue(ringAngle(ring.working.periodMs, 1000) < ringAngle(ring.listening.periodMs, 1000))
        assertEquals(0f, ringAngle(0, 500), 0f)
        assertEquals(0f, ringAngle(4200, -5), 0f)
        for (t in 0L..20_000L step 137) assertTrue(ringAngle(4200, t) in 0f..360f)
    }

    @Test fun itFadesInFromDarkOnlyWhenItFirstLights() {
        assertNull(ringFrame(ring, RingMoment(RingState.Listening, 0, 0), 1f))
        assertEquals(0.5f, ringFrame(ring, RingMoment(RingState.Listening, FADE_MILLIS / 2, FADE_MILLIS / 2), 1f)!!.alpha, 0.001f)
        assertEquals(1f, ringFrame(ring, RingMoment(RingState.Listening, FADE_MILLIS, FADE_MILLIS), 1f)!!.alpha, 0.001f)
        // Going from one state to the next doesn't blink: the ring was already lit.
        assertEquals(ring.needsYou.opacity, ringFrame(ring, RingMoment(RingState.NeedsYou, 0, 5000), 1f)!!.alpha, 0.001f)
    }

    @Test fun aSteadyLightDoesNotBreathe() {
        for (t in 0L..10_000L step 250) assertEquals(1f, breath(0, t), 0f)
    }

    @Test fun doneBreathesItsPulsesAndThenIsDark() {
        val pulses = ring.done.pulses
        assertTrue("Console's done light breathes", pulses > 0)
        assertEquals(1f, breath(pulses, 0), 0.001f)
        assertEquals(BREATH_LOW, breath(pulses, BREATH_MILLIS / 2), 0.001f)
        assertEquals(1f, breath(pulses, BREATH_MILLIS), 0.001f)
        assertEquals(0f, breath(pulses, pulses * BREATH_MILLIS), 0f)
        assertEquals(0f, breath(pulses, pulses * BREATH_MILLIS + 5000), 0f)
        // It goes out over the end of the last breath rather than cutting off.
        val nearEnd = breath(pulses, pulses * BREATH_MILLIS - FADE_MILLIS / 4)
        assertTrue(nearEnd > 0f && nearEnd < 0.3f)
        for (t in 0L..pulses * BREATH_MILLIS step 50) assertTrue(breath(pulses, t) in 0f..1f)
    }

    @Test fun doneLastsExactlyAsLongAsItsBreaths() {
        val window = doneMillis(ring.done)
        assertEquals(ring.done.pulses * BREATH_MILLIS, window)
        assertNotNull(ringFrame(ring, steady(RingState.Done, window - 400), 1f))
        assertNull(ringFrame(ring, steady(RingState.Done, window), 1f))
        // A pack whose done light is steady still goes off.
        assertEquals(DONE_MILLIS, doneMillis(ring.done.copy(pulses = 0)))
    }

    @Test fun theTimelineCountsFromEachChangeAndFromFirstLight() {
        val timeline = RingTimeline()
        assertEquals(RingMoment(RingState.Off, 100, 100), timeline.at(RingState.Off, 100))
        assertEquals(RingMoment(RingState.Listening, 0, 0), timeline.at(RingState.Listening, 1000))
        assertEquals(RingMoment(RingState.Listening, 250, 250), timeline.at(RingState.Listening, 1250))
        assertEquals(RingMoment(RingState.Working, 0, 1000), timeline.at(RingState.Working, 2000))
        assertEquals(RingMoment(RingState.NeedsYou, 0, 2000), timeline.at(RingState.NeedsYou, 3000))
        assertEquals(RingState.Off, timeline.at(RingState.Off, 4000).state)
        // Lit again later: it fades in again.
        assertEquals(RingMoment(RingState.Done, 0, 0), timeline.at(RingState.Done, 9000))
    }

    @Test fun theGlowIsWiderFainterStrokesUnderTheLine() {
        val frame = ringFrame(ring, steady(RingState.Listening), 2f)!!
        val strokes = ringStrokes(frame)
        assertEquals(GLOW_STEPS + 1, strokes.size)
        assertEquals(RingStroke(frame.width, frame.alpha), strokes.last())
        assertEquals(frame.width + 2 * frame.glow, strokes.first().width, 0.001f)
        for ((wider, narrower) in strokes.zipWithNext()) assertTrue(wider.width > narrower.width)
        // Lying on one another they add up to the glow's opacity at the line, and no more.
        assertEquals(frame.alpha * frame.glowAlpha, strokes.dropLast(1).sumOf { it.alpha.toDouble() }.toFloat(), 0.001f)
        assertEquals(listOf(RingStroke(frame.width, frame.alpha)), ringStrokes(frame.copy(glow = 0f)))
        assertEquals(listOf(RingStroke(frame.width, frame.alpha)), ringStrokes(frame.copy(glowAlpha = 0f)))
    }

    @Test fun theSweepIsTwoCometsWithClearGaps() {
        val c1 = 0xFF7AA2F7.toInt()
        val c2 = 0xFFC0CAF5.toInt()
        val (colors, positions) = ringStops(c1, c2)
        assertEquals(colors.size, positions.size)
        assertEquals(0f, positions.first(), 0f)
        assertEquals(1f, positions.last(), 0f)
        for ((a, b) in positions.toList().zipWithNext()) assertTrue(a < b)
        // The gaps keep the colors and lose only their opacity.
        val clear = colors.filter { it ushr 24 == 0 }
        assertEquals(4, clear.size)
        assertTrue(clear.all { it == (c1 and 0xFFFFFF) || it == (c2 and 0xFFFFFF) })
        assertEquals(setOf(c1, c2), colors.filter { it ushr 24 != 0 }.toSet())
    }
}
