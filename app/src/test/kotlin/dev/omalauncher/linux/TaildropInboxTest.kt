package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Taildrop reaching Linux through Oma, with Wireless debugging off (oma drop get / list without adb). */
class TaildropInboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun phone(): File {
        val storage = tmp.newFolder("storage")
        File(storage, "Download/Taildrop").mkdirs()
        File(storage, "Taildrop").mkdirs()
        File(storage, "Download/Taildrop/soma-abc-900.apk").writeText("apk")
        File(storage, "Taildrop/mail.json").writeText("{}")
        File(storage, "Download/Taildrop/arriving.zip.partial").writeText("half")
        return storage
    }

    @Test fun listsWholeFilesOfBothFoldersAndMovesNothing() {
        val storage = phone()
        val result = TaildropInbox.take(TaildropInbox.folders(storage), null, keep = false, listOnly = true) { it.path }
        val files = result.getJSONArray("files")
        assertEquals(setOf("soma-abc-900.apk", "mail.json"), (0 until files.length()).map { files.getJSONObject(it).getString("name") }.toSet())
        assertTrue(File(storage, "Download/Taildrop/soma-abc-900.apk").exists())
    }

    @Test fun movesIntoLinuxNeverOverAFileThereAndLeavesWhatIsArriving() {
        val storage = phone()
        val inbox = tmp.newFolder("rootfs", "root", "Inbox")
        File(inbox, "soma-abc-900.apk").writeText("older")
        val result = TaildropInbox.take(TaildropInbox.folders(storage), inbox, keep = false, listOnly = false) { "/root/Inbox/" + it.name }
        val files = result.getJSONArray("files")
        val apk = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("name") == "soma-abc-900.apk" }
        assertEquals("moved", apk.getString("state"))
        assertEquals("/root/Inbox/soma-abc-900 (2).apk", apk.getString("path"))
        assertEquals("older", File(inbox, "soma-abc-900.apk").readText())
        assertEquals("apk", File(inbox, "soma-abc-900 (2).apk").readText())
        assertEquals("{}", File(inbox, "mail.json").readText())
        assertFalse(File(storage, "Download/Taildrop/soma-abc-900.apk").exists())
        assertTrue(File(storage, "Download/Taildrop/arriving.zip.partial").exists())
    }

    @Test fun keepCopiesAndLeavesThePhonesOwn() {
        val storage = phone()
        val inbox = tmp.newFolder("kept")
        TaildropInbox.take(TaildropInbox.folders(storage), inbox, keep = true, listOnly = false) { it.path }
        assertEquals("apk", File(inbox, "soma-abc-900.apk").readText())
        assertTrue(File(storage, "Download/Taildrop/soma-abc-900.apk").exists())
    }

    @Test fun noTaildropFolderYetIsNothingWaiting() {
        val result = TaildropInbox.take(TaildropInbox.folders(tmp.newFolder("empty")), tmp.newFolder("in"), keep = false, listOnly = false) { it.path }
        assertEquals(0, result.getJSONArray("files").length())
    }

    @Test fun agentMailIsNeverMovedIntoLinux() {
        assertTrue(TaildropInbox.isMail("msg-20261025-161720-9f79e6-from-jacks-mac-pro.json"))
        assertFalse(TaildropInbox.isMail("soma-af50f6cd0-158.json"))
        assertFalse(TaildropInbox.isMail("msg-notes.txt"))
        val dir = kotlin.io.path.createTempDirectory().toFile()
        java.io.File(dir, "msg-1-from-x.json").writeText("{}")
        java.io.File(dir, "soma-1.apk").writeText("apk")
        assertEquals(listOf("soma-1.apk"), TaildropInbox.waiting(listOf(dir)).map { it.name })
        dir.deleteRecursively()
    }
}
