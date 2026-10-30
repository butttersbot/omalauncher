package dev.omalauncher.oma

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The desktop HTML engine's rules, without starting Chromium: its switches, the panel's sandbox decisions, and the install. */
class WebEngineTest {
    @Test fun chromiumStartsWithoutFirstRunExtensionsOrReachingOut() {
        val flags = WebEngineFlags.chromium("Linux", 1.0, software = false)
        for (flag in listOf("--no-first-run", "--no-default-browser-check", "--disable-component-update", "--disable-extensions", "--disable-sync", "--disable-background-networking"))
            assertTrue(flag, flag in flags)
        assertFalse(flags.any { it.startsWith("--remote-debugging") })
        assertFalse(flags.any { it.startsWith("--disable-gpu") })
    }

    @Test fun onLinuxItDrawsThroughX11AndOnTheMacItDoesnt() {
        assertTrue("--ozone-platform=x11" in WebEngineFlags.chromium("Linux", 1.0, false))
        assertFalse(WebEngineFlags.chromium("Mac OS X", 1.0, false).any { it.startsWith("--ozone") })
    }

    @Test fun pagesDrawAtSomasScaleByZoomNotByChromiumsScale() {
        // JCEF sizes the window by Soma's scale already; Chromium's own scale on top doubled it (MG1 walk).
        assertFalse(WebEngineFlags.chromium("Linux", 2.0, false).any { it.startsWith("--force-device-scale-factor") })
        assertEquals(2.0, Math.pow(1.2, WebEngineFlags.zoomLevel("Linux", 2.0)), 1e-9)
        assertEquals(1.5, Math.pow(1.2, WebEngineFlags.zoomLevel("Linux", 1.5)), 1e-9)
        assertEquals(0.0, WebEngineFlags.zoomLevel("Linux", 1.0), 0.0)
        assertEquals(0.0, WebEngineFlags.zoomLevel("Mac OS X", 2.0), 0.0)
    }

    @Test fun afterTheGpuFailedItStartsInSoftware() {
        val flags = WebEngineFlags.chromium("Linux", 2.0, software = true)
        assertTrue("--disable-gpu" in flags && "--disable-gpu-compositing" in flags)
    }

    @Test fun generatedHtmlLoadsItselfAndGoesNowhereElse() {
        assertEquals(ArtifactPolicy.Go.Allow, ArtifactPolicy.navigation(true, ARTIFACT_ORIGIN, true))
        assertEquals(ArtifactPolicy.Go.Allow, ArtifactPolicy.navigation(true, "about:blank", true))
        for (away in listOf("https://example.com/", "http://100.120.219.46:8480/", "file:///etc/passwd", "javascript:alert(1)", "chrome://settings", "devtools://devtools"))
            assertEquals(away, ArtifactPolicy.Go.Block, ArtifactPolicy.navigation(true, away, true))
        assertEquals(ArtifactPolicy.Go.Block, ArtifactPolicy.navigation(true, ARTIFACT_ORIGIN, false))
    }

    @Test fun aReplyIsOnlyTakenFromTheMainFrame() {
        assertEquals(ArtifactPolicy.Go.Answer, ArtifactPolicy.navigation(true, "soma-answer:t:yes", true))
        assertEquals(ArtifactPolicy.Go.Block, ArtifactPolicy.navigation(true, "soma-answer:t:yes", false))
        // A web page never gets the reply bridge.
        assertEquals(ArtifactPolicy.Go.Block, ArtifactPolicy.navigation(false, "soma-answer:t:yes", true))
    }

    @Test fun aWebPageStaysOnTheWeb() {
        assertEquals(ArtifactPolicy.Go.Allow, ArtifactPolicy.navigation(false, "https://canvas.example/board", true))
        for (away in listOf("file:///etc/passwd", "chrome://settings", "javascript:alert(1)"))
            assertEquals(away, ArtifactPolicy.Go.Block, ArtifactPolicy.navigation(false, away, true))
    }

    @Test fun generatedHtmlReachesOnlyItselfAndInlineData() {
        assertTrue(ArtifactPolicy.request(true, ARTIFACT_ORIGIN))
        assertTrue(ArtifactPolicy.request(true, "data:image/png;base64,AAAA"))
        assertTrue(ArtifactPolicy.request(true, "blob:https://soma-artifact.invalid/1"))
        for (away in listOf("https://cdn.example/react.js", "http://localhost:8480/v1/notes", "file:///home", "wss://example.com/"))
            assertFalse(away, ArtifactPolicy.request(true, away))
        assertTrue(ArtifactPolicy.request(false, "https://cdn.example/react.js"))
        assertFalse(ArtifactPolicy.request(false, "file:///home"))
    }

    @Test fun theSandboxPolicyGoesRightAfterTheHead() {
        val page = isolatedArtifactHtml("<!doctype html><html><head><title>x</title></head><body></body></html>")
        assertTrue(page.startsWith("<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\""))
    }

    @Test fun anInstallUnpacksTheNativesAndLocksLast() {
        val home = Files.createTempDirectory("engine").toFile()
        val dir = File(home, "jcef-test")
        assertFalse(WebEngine.installed(dir))
        assertNull(WebEngine.install(natives(mapOf("build_meta.json" to "{\"release_tag\":\"t\",\"platform\":\"linux-amd64\"}", "libcef.so" to "lib")), dir))
        assertTrue(WebEngine.installed(dir))
        assertEquals("lib", File(dir, "libcef.so").readText())
        assertFalse("no staging folder left", File(home, ".jcef-test.new").exists())
    }

    @Test fun aFileWithoutChromiumInItIsntInstalled() {
        val home = Files.createTempDirectory("engine").toFile()
        val dir = File(home, "jcef-test")
        val jar = File(home, "empty.jar").apply { ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("readme.txt")); it.write("x".toByteArray()) } }
        assertNotNull(WebEngine.install(jar, dir))
        assertFalse(WebEngine.installed(dir))
        // Unpacked but without build_meta.json: refused too.
        assertNotNull(WebEngine.install(natives(mapOf("libcef.so" to "lib")), dir))
        assertFalse(WebEngine.installed(dir))
    }

    /** A natives jar as jcefmaven publishes it: a zip holding one .tar.gz of the files. */
    private fun natives(files: Map<String, String>): File {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(tar)).use { out ->
            for ((name, text) in files) {
                val bytes = text.toByteArray()
                out.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return Files.createTempFile("natives", ".jar").toFile().apply {
            ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry("jcef-linux-amd64.tar.gz")); zip.write(tar.toByteArray()) }
        }
    }
}
