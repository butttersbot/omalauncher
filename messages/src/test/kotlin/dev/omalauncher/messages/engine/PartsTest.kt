package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PartsTest {
    @Test fun plainWordsStayOneBubble() {
        assertEquals(listOf(Part.Words("are we still on for friday?")), Parts.of("are we still on for friday?"))
    }

    @Test fun aLinkBecomesItsOwnBubbleBeforeTheWords() {
        assertEquals(
            listOf(Part.Link("https://kiriko.jp/menu"), Part.Words("this is the place")),
            Parts.of("this is the place https://kiriko.jp/menu."),
        )
    }

    @Test fun aLinkAloneLeavesNoEmptyWords() {
        assertEquals(listOf(Part.Link("www.youtube.com/watch?v=abc")), Parts.of("www.youtube.com/watch?v=abc"))
    }

    @Test fun openBubblesAttachmentSummariesAreAttachments() {
        listOf("1 Photo", "2 Photos & 1 Video", "1 Contact card, 1 Location, 2 PDFs", "Attachment", "1 Sticker").forEach {
            assertEquals(it, listOf(Part.Attachment(it)), Parts.of(it))
        }
        assertEquals("Photo", Part.Attachment("2 Photos & 1 Video").kind)
        assertEquals("Audio message", Part.Attachment("1 Audio message").kind)
    }

    @Test fun wordsThatOnlyLookLikeASummaryStayWords() {
        assertEquals(listOf(Part.Words("2 photos of the counter")), Parts.of("2 photos of the counter"))
        assertEquals(listOf(Part.Words("1 Photo please")), Parts.of("1 Photo please"))
    }

    @Test fun aWebsitePreviewKeepsItsTitleAndDomain() {
        assertEquals(listOf(Part.Website("Kiriko — Omakase", "kiriko.jp")), Parts.of("Website: Kiriko — Omakase (kiriko.jp)"))
    }

    @Test fun hostsAndPaths() {
        assertEquals("youtube.com", Parts.host("https://www.YouTube.com/watch?v=1"))
        assertEquals("watch?v=1", Parts.path("https://www.youtube.com/watch?v=1"))
        assertEquals("", Parts.path("https://kiriko.jp/"))
        assertEquals("https://www.x.com", Parts.openable("www.x.com"))
    }
}
