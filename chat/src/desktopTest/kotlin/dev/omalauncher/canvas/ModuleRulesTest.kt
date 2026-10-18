package dev.omalauncher.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the modules (Soma Canvas slice 3a); the taps themselves are actuated in ChatScreenTapTest. */
class ModuleRulesTest {
    @Test fun sizesFollowFromCells() {
        assertEquals(ModuleSize.Dot, moduleSize(1, 1, 6))
        assertEquals(ModuleSize.Line, moduleSize(6, 1, 6))
        assertEquals(ModuleSize.Card, moduleSize(3, 2, 6))
        assertEquals(ModuleSize.Card, moduleSize(4, 3, 6))
        assertEquals(ModuleSize.Full, moduleSize(5, 3, 6))
        assertEquals(ModuleSize.Full, moduleSize(6, 4, 8))
        assertEquals(ModuleSize.Card, moduleSize(5, 3, 8))
        assertEquals(ModuleSize.Full, moduleSize(8, 3, 12))
        assertEquals(ModuleSize.Card, moduleSize(7, 3, 12))
        assertTrue(moduleSize(0, 0, 6) == ModuleSize.Dot)
    }
}
