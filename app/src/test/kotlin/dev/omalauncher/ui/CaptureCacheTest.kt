package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The overview's page stills are kept in memory only, and only so many of them. */
class CaptureCacheTest {
    /** Stills stood in for by their size in bytes. */
    private fun cache(
        count: Int = 3,
        bytes: Long = 100,
    ) = CaptureCache<Long>(count, bytes) { it }

    @Test fun keepsOneStillForEachPage() {
        val cache = cache()
        cache.put("p1", 10)
        cache.put("p2", 20)
        assertEquals(10L, cache["p1"])
        assertEquals(20L, cache["p2"])
        assertNull(cache["p3"])
        assertEquals(30, cache.held)
        // A newer still of a page replaces the one before it.
        cache.put("p1", 15)
        assertEquals(15L, cache["p1"])
        assertEquals(listOf("p2", "p1"), cache.pages)
        assertEquals(35, cache.held)
    }

    @Test fun theOldestGoWhenThereAreTooMany() {
        val cache = cache(count = 3)
        for (i in 1..5) cache.put("p$i", 1)
        assertEquals(listOf("p3", "p4", "p5"), cache.pages)
        assertNull(cache["p1"])
        assertNull(cache["p2"])
        assertEquals(3, cache.held)
    }

    @Test fun theOldestGoWhenTheyTakeTooMuch() {
        val cache = cache(count = 10, bytes = 100)
        cache.put("p1", 40)
        cache.put("p2", 40)
        cache.put("p3", 40)
        assertEquals(listOf("p2", "p3"), cache.pages)
        assertEquals(80, cache.held)
        // Exactly at the limit is within it.
        cache.put("p4", 20)
        assertEquals(listOf("p2", "p3", "p4"), cache.pages)
        assertEquals(100, cache.held)
        // One that fills it alone leaves only itself, and one too big for it is not kept, nor is what it would replace.
        cache.put("p5", 100)
        assertEquals(listOf("p5"), cache.pages)
        cache.put("p5", 101)
        assertEquals(emptyList<String>(), cache.pages)
        assertEquals(0, cache.held)
    }

    @Test fun aDeletedPagesStillIsDropped() {
        val cache = cache()
        cache.put("p1", 10)
        cache.put("p2", 20)
        cache.drop("p1")
        cache.drop("nowhere")
        assertNull(cache["p1"])
        assertEquals(listOf("p2"), cache.pages)
        assertEquals(20, cache.held)
    }

    @Test fun everythingGoesWhenMemoryIsWanted() {
        val cache = cache()
        cache.put("p1", 10)
        cache.put("p2", 20)
        cache.clear()
        assertNull(cache["p1"])
        assertNull(cache["p2"])
        assertEquals(emptyList<String>(), cache.pages)
        assertEquals(0, cache.held)
        // And it keeps stills again afterwards.
        cache.put("p1", 5)
        assertEquals(5L, cache["p1"])
        assertEquals(5, cache.held)
    }

    @Test fun aCacheForNothingKeepsNothing() {
        val cache = cache(count = 0)
        cache.put("p1", 1)
        assertEquals(emptyList<String>(), cache.pages)
        assertEquals(0, cache.held)
    }
}
