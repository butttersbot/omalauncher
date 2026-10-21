package dev.omalauncher.search

import org.junit.Assert.assertEquals
import org.junit.Test

class MacFilesTest {
    @Test fun whereOnTheMacSaidFromHome() {
        assertEquals("~/Documents/Taxes", MacFiles.where("/Users/jackkim/Documents/Taxes/2025.pdf"))
        assertEquals("~", MacFiles.where("/Users/jackkim/notes.md"))
        assertEquals("/Volumes/Backup", MacFiles.where("/Volumes/Backup/a.txt"))
    }
}
