package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockStarShareTest {
    @Test fun aCaptureIsNamedLikeALookAndNeverLikeAPath() {
        for (name in listOf("riso", "city-pop", "tiny-magazine", "mine2")) assertTrue(name, LockStarShare.valid(name))
        for (name in listOf("", "City Pop", "../x", "a/b", "-x", "x".repeat(41), "riso.json")) assertFalse(name, LockStarShare.valid(name))
    }

    @Test fun anEmptyCellHoldsNothingAndALayoutIsCounted() {
        // What LockStar answered on the Fold when no item was asked for by name.
        assertEquals(0, LockStarShare.summary("""{"category":"com.samsung.android.gts.category.DEFAULT","items":[],"version":1}""").getInt("items"))
        val told = LockStarShare.summary("""{"items":[{"key":"lock_star","value":"abcdef","tags":{"device_type":"Fold"},"embeddedItems":{"/a":{},"/b":{}}}]}""")
        assertEquals(1, told.getInt("items"))
        assertEquals(6, told.getInt("layoutChars"))
        assertEquals(2, told.getInt("files"))
        assertEquals("Fold", told.getString("device"))
    }
}
