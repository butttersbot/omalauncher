package dev.omalauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModeTest {
    @Test fun aNewSearchOpensOnQuick() {
        assertEquals(SearchMode.Quick, SearchMode.opening)
    }

    @Test fun quickLooksThroughNeitherFilesNorPhotos() {
        val q = SearchMode.Quick
        assertTrue(q.quick)
        assertFalse(q.files)
        assertFalse(q.photos)
        assertFalse(q.photosShown(wordsNameSomething = false))
    }

    @Test fun filesLooksThroughFilesOnly() {
        val f = SearchMode.Files
        assertTrue(f.files)
        assertFalse(f.photos)
        assertFalse(f.quick)
    }

    @Test fun photosLooksThroughPhotosOnlyAndIgnoresTheNameGuess() {
        val p = SearchMode.Photos
        assertTrue(p.photos)
        assertFalse(p.files)
        assertFalse(p.quick)
        // "Apple Store" names an app: in Photos he asked for photos all the same.
        assertTrue(p.photosShown(wordsNameSomething = true))
        assertTrue(p.photosShown(wordsNameSomething = false))
    }

    @Test fun theRowReadsQuickFilesPhotos() {
        assertEquals(listOf("Quick", "Files", "Photos"), SearchMode.entries.map { it.label })
    }

    @Test fun photosStartAfterTwoLetters() {
        assertEquals(2, SearchMode.Photos.minimumLetters)
        assertEquals(1, SearchMode.Files.minimumLetters)
    }
}
