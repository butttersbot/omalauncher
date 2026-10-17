package dev.omalauncher.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheepTalkTest {
    @Test
    fun theWordsComeAfterALineSayingWhereTheyWereSaid() {
        val prompt = sheepPrompt("  mark that, Rydia just learned Blizzard ", "me.magnum.melonds.dev")!!
        assertTrue(prompt.startsWith("[Said to the sheep while me.magnum.melonds.dev was in front:"))
        assertTrue(prompt.endsWith("\n\nmark that, Rydia just learned Blizzard"))
    }

    @Test
    fun withoutAnAppInFrontTheLineSaysJustTheSheep() =
        assertTrue(sheepPrompt("hi", null)!!.startsWith("[Said to the sheep: "))

    @Test
    fun nothingHeardSendsNothing() {
        assertNull(sheepPrompt("", "x"))
        assertNull(sheepPrompt("   \n", "x"))
    }

    @Test
    fun theHoldIsShorterThanAnAndroidLongPress() = assertEquals(true, TALK_HOLD_MS < 400L)
}
