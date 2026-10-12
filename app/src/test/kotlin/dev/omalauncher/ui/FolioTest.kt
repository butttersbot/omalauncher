package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolioTest {
    @Test fun onePageShowsItsNameAndPlace() = assertEquals("Today  1/5", folio(listOf("Today"), 0, 5))

    @Test fun aSpreadJoinsBothNamesAndCountsFromTheFirst() = assertEquals("Trip + Notes  3/5", folio(listOf("Trip", "Notes"), 2, 5))

    /** The last spread of an odd notebook has one page and an empty half. */
    @Test fun anEmptyHalfAddsNothing() = assertEquals("Notes  5/5", folio(listOf("Notes", null), 4, 5))

    @Test fun aNotebookOfOnePageHasNoCount() = assertEquals("Today", folio(listOf("Today"), 0, 1))

    @Test fun nothingBeforeTheNotebookIsRead() = assertNull(folio(listOf(null, null), 0, 4))
}
