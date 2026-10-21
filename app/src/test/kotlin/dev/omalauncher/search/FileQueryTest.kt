package dev.omalauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File search's plain parts: names cut into words, queries for the index, and the order files show in. */
class FileQueryTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun aNameIsCutIntoTheWordsHeWouldType() {
        assertEquals("lease agreement 2026 pdf", FileQuery.nameWords("Lease_agreement-2026.pdf"))
        assertEquals("my invoice oct pdf", FileQuery.nameWords("myInvoiceOct.pdf"))
        assertEquals("img 2041 jpg", FileQuery.nameWords("IMG2041.jpg"))
        assertEquals("cafe menu txt", FileQuery.nameWords("Café menu.txt"))
        assertEquals("flight confirmation hnl pdf", FileQuery.nameWords("flight-confirmation-HNL.pdf"))
    }

    @Test fun aQueryAsksForEveryWordToBeginAWord() {
        assertEquals("name:lease* name:agr*", FileQuery.match("Lease agr", "name"))
        assertEquals("body:rent*", FileQuery.match("  rent ", "body"))
        // Nothing an operator could be made of reaches the index: OR is folded to a plain word.
        assertEquals("name:a* name:or* name:b*", FileQuery.match("a OR-b\"", "name"))
        assertNull(FileQuery.match("  -- ", "name"))
    }

    @Test fun theFileHeMeantComesFirst() {
        val hits =
            listOf(
                FileQuery.Hit("/s/Download/lease-old-2019.pdf", "lease-old-2019.pdf", now - 900 * day, 1),
                FileQuery.Hit("/s/Documents/Lease agreement 2026.pdf", "Lease agreement 2026.pdf", now - 40 * day, 1),
                FileQuery.Hit("/s/Documents/release notes.txt", "release notes.txt", now - day, 1),
                FileQuery.Hit("/s/Documents/lease.pdf", "lease.pdf", now - 400 * day, 1),
            )
        // The very name first, then one it begins (the newer before the older), and a word inside another last.
        assertEquals(
            listOf("lease.pdf", "Lease agreement 2026.pdf", "lease-old-2019.pdf", "release notes.txt"),
            FileQuery.rankNames(hits, "lease", now).map { it.name },
        )
        // One he keeps opening rises over a newer one of the same standing.
        val opened = FileQuery.rankNames(hits.filter { it.name != "lease.pdf" }, "lease", now) { if (it.endsWith("lease-old-2019.pdf")) 500.0 else 1.0 }
        assertEquals("lease-old-2019.pdf", opened.first().name)
        // And words further in the name: "lease a" is the agreement.
        assertEquals("Lease agreement 2026.pdf", FileQuery.rankNames(hits, "lease a", now).first().name)
    }

    @Test fun whereAFileIsSaysItPlainly() {
        assertEquals("Download", FileQuery.folder("/storage/emulated/0/Download/a.pdf", "/storage/emulated/0", "/data/x/files/arch/root"))
        assertEquals("Phone", FileQuery.folder("/storage/emulated/0/a.pdf", "/storage/emulated/0", "/data/x/files/arch/root"))
        assertEquals("Linux · ~/notes", FileQuery.folder("/data/x/files/arch/root/notes/a.md", "/storage/emulated/0", "/data/x/files/arch/root"))
    }

    @Test fun officeTextKeepsItsWordsApart() {
        val xml = "<w:document><w:p><w:r><w:t>Rent is</w:t></w:r><w:r><w:t>due</w:t></w:r></w:p><w:p><w:t>Tom &amp; Jerry</w:t></w:p></w:document>"
        val text = FileQuery.xmlText(xml)
        assertTrue(text, "Rent is" in text && "due" in text && "Tom & Jerry" in text)
        assertTrue(text, !text.contains("Rent isdue"))
    }

    @Test fun hiddenAndBuildFoldersAreLeftOut() {
        assertTrue(FileQuery.skipped(".git") && FileQuery.skipped("node_modules") && FileQuery.skipped(".thumbnails"))
        assertTrue(!FileQuery.skipped("Documents") && !FileQuery.skipped("data"))
    }

    @Test fun typosAreCountedAsTheyHappen() {
        assertEquals(0, FileQuery.distance("invoice", "invoice"))
        assertEquals(1, FileQuery.distance("invioce", "invoice"))
        assertEquals(1, FileQuery.distance("recipt", "receipt"))
        assertEquals(1, FileQuery.distance("leasse", "lease"))
        assertEquals(2, FileQuery.distance("aggrement", "agreement"))
        // Stops early once it is past the most it may be.
        assertTrue(FileQuery.distance("passport", "invoice", most = 2) > 2)
    }

    @Test fun aMisspeltWordIsReadAsTheNameWordMeant() {
        val terms = listOf("invoice" to 12, "invoices" to 2, "involve" to 1, "lease" to 4, "receipt" to 9, "recipe" to 3)
        assertEquals("invoice", FileQuery.corrected("invioce", terms, last = false))
        assertEquals("receipt", FileQuery.corrected("reciept", terms, last = false))
        // A word still being typed matches the start of a longer one.
        assertEquals("invoice", FileQuery.corrected("invoic", terms, last = true))
        assertEquals("receipt", FileQuery.corrected("reciep", terms, last = true))
        // A word the index has is left alone; a short one is never guessed at; nothing near is nothing.
        assertNull(FileQuery.corrected("lease", terms, last = false))
        assertNull(FileQuery.corrected("lea", terms, last = true))
        assertNull(FileQuery.corrected("passport", terms, last = false))
    }

    @Test fun theMacsReadingReordersButAnExactNameStaysFirst() {
        val a = FileQuery.Hit("/s/Lease.pdf", "Lease.pdf", now, 1)
        val b = FileQuery.Hit("/s/notes.txt", "notes.txt", now, 1, "groceries for the week")
        val c = FileQuery.Hit("/s/contract.txt", "contract.txt", now, 1, "the lease agreement for the flat")
        // Text matches, newest first from the index; the one about a lease is nearest in meaning.
        assertEquals(listOf(c, b), FileQuery.blend(listOf(b, c), floatArrayOf(0.58f, 0.74f), lexical = 0.3, query = "lease"))
        // An exact name first stays first however the Mac reads it.
        assertEquals(a, FileQuery.blend(listOf(a, b, c), floatArrayOf(0.55f, 0.6f, 0.8f), lexical = 0.6, query = "lease").first())
        // Nothing to go by: as they were.
        assertEquals(listOf(b, c), FileQuery.blend(listOf(b, c), floatArrayOf(0.7f), lexical = 0.3, query = "x"))
        assertEquals("contract · Documents: the lease agreement for the flat", FileQuery.meaning(c, "Documents"))
    }
}
