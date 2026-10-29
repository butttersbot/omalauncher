package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The look Soma reports to agents elsewhere (herd's `soma`): what it says, and where it lands. */
class LookReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun saysTheLookItsDigestAndTheBuild() {
        val r = LookReport.json("manga", "Manga", "[pack]\nname = \"Manga\"\n".toByteArray(), 176, 1_793_000_000_000)
        assertEquals("manga", r.getString("look"))
        assertEquals("Manga", r.getString("name"))
        assertEquals(176, r.getLong("build"))
        assertEquals(1_793_000_000, r.getLong("at"))
        assertEquals(16, r.getString("pack_digest").length)
        assertEquals(LookReport.sha256("[pack]\nname = \"Manga\"\n".toByteArray()).take(16), r.getString("pack_digest"))
    }

    @Test fun noPackFileNoDigest() {
        assertFalse(LookReport.json("console", "console", null, 1, 0).has("pack_digest"))
    }

    @Test fun writtenIntoTheGuestsCacheOnlyWhenLinuxIsThere() {
        val files = tmp.newFolder("files")
        LookReport.write(files, LookReport.json("manga", "Manga", null, 1, 0))
        assertFalse(File(files, LookReport.FILE).exists())
        File(files, "arch/root").mkdirs()
        LookReport.write(files, LookReport.json("manga", "Manga", null, 1, 0))
        assertTrue(File(files, LookReport.FILE).readText().contains("\"look\":\"manga\""))
    }
}
