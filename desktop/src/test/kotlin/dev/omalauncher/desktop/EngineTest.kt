package dev.omalauncher.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** The HTML engine's line in the update sheet (Engine.kt): read from the record, offered once, fetched, checked, unpacked. */
class EngineTest {
    private val bytes = "the natives".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val natives = ReleaseFile("jcef-natives-linux-x64-152.0.6.jar", sha, bytes.size.toLong())
    private val engine = EngineRelease("152.0.6", "jcef-17e805a+cef-152.0.6", mapOf("linux-x64" to natives))
    private val release = Release("abcdef12", 100, emptyList(), emptyMap(), "jacks-mac-pro", engine)

    private class Hands(val give: ByteArray?) : UpdateHands {
        val fetched = mutableListOf<String>()
        override fun latest() = emptyList<Release>()
        override fun fetch(release: Release, file: ReleaseFile, into: File): String? = error("the app isn't fetched for the engine")
        override fun fetchEngine(release: Release, file: ReleaseFile, into: File): String? {
            fetched += file.file
            val data = give ?: return "its runner answered 404 for ${file.file}"
            File(into, file.file).writeBytes(data)
            return null
        }
        override fun hold() = emptyList<String>()
        override fun stageApp(artifact: File, release: Release): String? = null
        override fun swapRunner(tarball: File, release: Release): String? = null
        override fun restoreRunner(): String? = null
        override fun startSwap(release: Release): String? = null
        override fun quit() {}
    }

    private fun installer(hands: UpdateHands, installed: Boolean = false, unpacked: MutableList<File> = mutableListOf(), started: MutableList<Boolean> = mutableListOf()) =
        EngineInstaller(hands, Files.createTempDirectory("engine").toFile(), "linux-x64", "152.0.6", { installed }, { unpacked += it; null }, { started += true })

    @Test fun theRecordCarriesTheEngine() {
        val body = """{"ok":true,"release":{"commit":"abcdef12","committed":100,"notes":[],"artifacts":{},
            "engine":{"version":"152.0.6","tag":"jcef-17e805a+cef-152.0.6","natives":{"linux-x64":{"file":"jcef-natives-linux-x64-152.0.6.jar","sha256":"$sha","size":11}}}}}"""
        assertEquals(engine, Release.parse(body, "jacks-mac-pro")?.engine)
    }

    @Test fun aBrokenEngineDoesntCostTheAppItsUpdate() {
        val body = """{"ok":true,"release":{"commit":"abcdef12","committed":100,"notes":[],"artifacts":{},"engine":{"version":"152.0.6","natives":{"linux-x64":{"file":"../x","sha256":"no","size":1}}}}}"""
        val parsed = Release.parse(body, "jacks-mac-pro")
        assertNotNull(parsed)
        assertNull(parsed!!.engine)
    }

    @Test fun itIsOfferedOnlyWhenThisBuildsVersionIsntInYet() {
        assertEquals(natives, installer(Hands(bytes)).offered(release))
        assertNull("already installed", installer(Hands(bytes), installed = true).offered(release))
        assertNull("another engine version", installer(Hands(bytes)).offered(release.copy(engine = engine.copy(version = "153.0.1"))))
        assertNull("none for this platform", installer(Hands(bytes)).offered(release.copy(engine = engine.copy(natives = mapOf("macos-arm64" to natives)))))
        assertNull("no engine in the record", installer(Hands(bytes)).offered(release.copy(engine = null)))
        val shown = installer(Hands(bytes)).apply { check(release) }
        assertTrue(shown.state is EngineState.Offer)
    }

    @Test fun anInstallFetchesChecksUnpacksAndStarts() {
        val unpacked = mutableListOf<File>()
        val started = mutableListOf<Boolean>()
        val hands = Hands(bytes)
        val engine = installer(hands, unpacked = unpacked, started = started)
        engine.install(release, natives)
        assertEquals(EngineState.Installed, engine.state)
        assertEquals(listOf(natives.file), hands.fetched)
        assertEquals(1, unpacked.size)
        assertEquals(listOf(true), started)
        engine.close()
        assertEquals(EngineState.None, engine.state)
    }

    @Test fun aDownloadThatDoesntMatchItsShaIsNeverUnpacked() {
        val unpacked = mutableListOf<File>()
        val engine = installer(Hands("something else".toByteArray()), unpacked = unpacked)
        engine.install(release, natives)
        val failed = engine.state as EngineState.Failed
        assertTrue(failed.reason, failed.reason.contains("sha256"))
        assertTrue(unpacked.isEmpty())
        assertTrue(failed.details.contains("step: check"))
    }

    @Test fun aFetchThatFailsSaysWhyAndKeepsTheOffer() {
        val engine = installer(Hands(null))
        engine.install(release, natives)
        val failed = engine.state as EngineState.Failed
        assertTrue(failed.reason, failed.reason.contains("404"))
        assertEquals(natives, failed.file)
    }

    @Test fun theAppsUpdateSpeaksFirstOnThePill() {
        assertEquals("install html engine" to true, pillLabel(UpdateState.None, EngineState.Offer(release, natives)))
        assertEquals("update soma · abcdef12" to true, pillLabel(UpdateState.Offer(release), EngineState.Offer(release, natives)))
        assertNull(pillLabel(UpdateState.None, EngineState.None))
        assertFalse(pillLabel(UpdateState.None, EngineState.Installing(release, 1))!!.second)
    }
}
