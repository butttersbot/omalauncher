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
        // The shape of a real capture from the Fold (2026-10-12), cut down: the layout is JSON inside a string.
        val told = LockStarShare.summary("""{"items":[{"item_key":"lock_star","item_value":"{\"items\":[{},{},{}]}","item_tags":{"device_type":"FOLD"},"item_embedded_items":{"/a":{},"/b":{}}}]}""")
        assertEquals(1, told.getInt("items"))
        assertEquals(3, told.getInt("layoutItems"))
        assertEquals(2, told.getInt("files"))
        assertEquals("FOLD", told.getString("device"))
    }
}
