package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import dev.omalauncher.ui.Pack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The update pill (Boss approved on Soma Canvas, soma-desktop/Updates): offered only for a newer build with this computer's
 * app and the runner; every file's sha256 checked before anything is swapped; the runner swapped only when no chat works
 * on it; anything that fails keeps the old version and says why for an agent; and the swap helper keeps the old app when
 * the new one doesn't start.
 */
@OptIn(ExperimentalTestApi::class)
class UpdatesTest {
    private val dir = Files.createTempDirectory("soma-update").toFile().also { System.setProperty("soma.home", it.path) }
    private val own = SomaBuild("85a29c49", 1_000)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val appBytes = "the app".toByteArray()
    private val runnerBytes = "the runner".toByteArray()
    private fun release(commit: String = "f0ed9535", committed: Long = 2_000, app: ByteArray = appBytes) = Release(
        commit, committed, listOf("Enter sends on the Mac too", "Settings has the tray icon"),
        mapOf("macos-arm64" to ReleaseFile("Soma-macos-arm64-$commit.zip", sha(app), app.size.toLong()), "runner" to ReleaseFile("oma-runner-$commit.tar.gz", sha(runnerBytes), runnerBytes.size.toLong())),
        "mac-pro",
    )

    /** Hands that do nothing real: they record what was asked, and serve [served] as each file's bytes. */
    private inner class Fake(var served: Map<String, ByteArray> = mapOf("zip" to appBytes, "gz" to runnerBytes)) : UpdateHands {
        val did = mutableListOf<String>()
        var busy = mutableListOf(listOf<String>())
        var runnerFails: String? = null
        var releases = listOf<Release>()
        override fun latest() = releases
        override fun fetch(release: Release, file: ReleaseFile, into: File): String? { did += "fetch ${file.file}"; File(into, file.file).writeBytes(served.getValue(file.file.substringAfterLast('.'))); return null }
        override fun working(): List<String>? = (if (busy.size > 1) busy.removeAt(0) else busy[0]).also { did += "working $it" }
        override fun stageApp(artifact: File, release: Release): String? { did += "stage"; return null }
        override fun swapRunner(tarball: File, release: Release): String? { did += "runner"; return runnerFails }
        override fun restoreRunner(): String? { did += "restore"; return null }
        override fun startSwap(release: Release): String? { did += "swap"; return null }
        override fun quit() { did += "quit" }
        override fun settled() { did += "settled" }
    }

    private fun updater(hands: UpdateHands) = Updater(hands, File(dir, "update"), own, "macos-arm64", "mac-pro", pause = {})

    @Test fun theBuildStampIsReadAndAMissingOneIsUnknown() {
        assertEquals(SomaBuild("f0ed9535", 1761750000), SomaBuild.read("commit=f0ed9535\ncommitted=1761750000\n"))
        assertEquals(SomaBuild("unknown", 0), SomaBuild.read(null))
    }

    @Test fun theRecordIsReadWholeOrNotAtAll() {
        val good = """{"ok": true, "release": {"commit": "f0ed9535", "committed": 2000, "notes": ["a"], "artifacts": {"linux-x64": {"file": "Soma-linux-x64-f0ed9535.jar", "sha256": "${"a".repeat(64)}", "size": 5}}}}"""
        assertEquals("f0ed9535", Release.parse(good, "mac-pro")?.commit)
        assertEquals(listOf("a"), Release.parse(good, "mac-pro")?.notes)
        assertNull(Release.parse(good.replace("Soma-linux-x64-f0ed9535.jar", "../../etc/passwd"), "mac-pro"))
        assertNull(Release.parse(good.replace("a".repeat(64), "short"), "mac-pro"))
        assertNull(Release.parse("""{"ok": false, "error": "no desktop build is published on this machine"}""", "mac-pro"))
    }

    @Test fun eachComputerTakesItsOwnFile() {
        assertEquals("macos-arm64", platformKey("Mac OS X", "aarch64"))
        assertEquals("linux-x64", platformKey("Linux", "amd64"))
        assertEquals("linux-arm64", platformKey("Linux", "aarch64"))
    }

    @Test fun onlyANewerBuildWithThisComputersAppAndTheRunnerIsOffered() {
        assertTrue(offered(own, release(), "macos-arm64"))
        assertFalse(offered(own, release(commit = own.commit), "macos-arm64"))
        assertFalse(offered(own, release(committed = 500), "macos-arm64"))  // older than this one: never a downgrade
        assertFalse(offered(own, release(), "linux-x64"))
        assertFalse(offered(own, release().copy(artifacts = release().artifacts - "runner"), "macos-arm64"))
        assertFalse(offered(own, null, "macos-arm64"))
        assertEquals("f0ed9535", newest(listOf(release(commit = "aaaaaaaa", committed = 1_500), release()))?.commit)
    }

    @Test fun thePillShowsOnlyWhenThereIsSomethingAndNothingHappensUntilTheTap() {
        val hands = Fake()
        val u = updater(hands)
        u.check()
        assertEquals(UpdateState.None, u.state)
        hands.releases = listOf(release())
        u.check()
        assertEquals(UpdateState.Offer(release()), u.state)
        assertEquals(emptyList<String>(), hands.did)  // asked, nothing fetched or touched
    }

    @Test fun aShaThatDoesntMatchSwapsNothing() {
        val hands = Fake(mapOf("zip" to "tampered".toByteArray(), "gz" to runnerBytes))
        val u = updater(hands)
        u.update(release())
        val failed = u.state as UpdateState.Failed
        assertTrue(failed.reason, failed.reason.contains("sha256 didn't match"))
        assertTrue(failed.reason.contains("Nothing was swapped"))
        assertEquals("85a29c49", failed.kept)
        assertFalse(hands.did.toString(), hands.did.any { it == "runner" || it == "swap" || it == "quit" })
        // Said for an agent, on screen and on disk.
        assertTrue(failed.details, failed.details.contains("step: check") && failed.details.contains("offered: f0ed9535"))
        assertTrue(File(dir, "update/last-error.txt").readText().contains("sha256"))
    }

    @Test fun itWaitsForEveryChatToFinishThenSwapsRunnerThenAppThenQuits() {
        val hands = Fake().apply { busy = mutableListOf(listOf("Fold GPU"), listOf("Fold GPU"), emptyList()) }
        val u = updater(hands)
        u.update(release())
        assertEquals(listOf("fetch Soma-macos-arm64-f0ed9535.zip", "fetch oma-runner-f0ed9535.tar.gz", "stage", "working [Fold GPU]", "working [Fold GPU]", "working []", "runner", "swap", "quit"), hands.did)
        assertEquals(UpdateState.Updating(release(), 3), u.state)
    }

    @Test fun cancellingTheWaitChangesNothingAndOffersItAgain() {
        lateinit var u: Updater
        val hands = Fake().apply { busy = mutableListOf(listOf("Fold GPU")) }
        u = Updater(hands, File(dir, "update"), own, "macos-arm64", "mac-pro", pause = { u.cancel() })
        u.update(release())
        assertEquals(UpdateState.Offer(release()), u.state)
        assertFalse(hands.did.toString(), "runner" in hands.did)
    }

    @Test fun aRunnerThatCantBeAskedStopsBeforeAnythingIsSwapped() {
        val hands = object : UpdateHands by Fake() { override fun working(): List<String>? = null }
        val u = updater(hands)
        u.update(release())
        assertTrue((u.state as UpdateState.Failed).reason.contains("couldn't be asked"))
    }

    @Test fun aRunnerThatDoesntTakeTheNewCopyLeavesTheAppAlone() {
        val hands = Fake().apply { runnerFails = "it didn't come back on f0ed9535 within a minute" }
        val u = updater(hands)
        u.update(release())
        val failed = u.state as UpdateState.Failed
        assertTrue(failed.reason, failed.reason.contains("The old runner is back; the app wasn't touched"))
        assertFalse("swap" in hands.did || "quit" in hands.did)
    }

    @Test fun theNewSomaSaysItUpdatedAndTheOldOnePutsTheRunnerBackAfterAFailedSwap() {
        val hands = Fake()
        val result = File(dir, "update/result.json").apply { parentFile.mkdirs() }
        result.writeText(JSONObject().put("ok", true).put("from", "85a29c49").put("to", own.commit).put("step", "app").toString())
        val u = updater(hands)
        u.reportAtStart()
        assertEquals(UpdateState.Updated(own.commit), u.state)
        assertEquals(own.commit, File(dir, "update/started").readText().trim())
        assertEquals(listOf("settled"), hands.did)
        assertFalse(result.exists())  // said once

        result.writeText(JSONObject().put("ok", false).put("from", own.commit).put("to", "f0ed9535").put("step", "start").put("error", "the new Soma did not start within a minute; the old app is back").put("runnerSwapped", true).toString())
        val again = Fake()
        val v = updater(again)
        v.reportAtStart()
        val failed = v.state as UpdateState.Failed
        assertTrue(failed.reason, failed.reason.startsWith("the new Soma did not start") && failed.reason.endsWith("the old app is back.") && failed.kept == "85a29c49")
        assertEquals(listOf("working []", "restore"), again.did)
    }

    @Test fun theSwapHelperStartsTheNewJarAndKeepsTheOldOneWhenItDoesntStart() {
        val work = Files.createTempDirectory("soma-swap").toFile()
        val script = File(work, "swap.sh").apply { writeText(SWAP_SCRIPT) }
        // A "java" that, run with -jar NEW, says it started (as a new Soma's reportAtStart does) when its jar is the good one.
        val java = File(work, "java").apply { writeText("#!/bin/sh\ncase \"\$2\" in *good*) echo f0ed9535 > \"${work.path}/started\";; esac\necho \"\$2\" >> \"${work.path}/ran\"\n"); setExecutable(true) }
        fun swap(newJar: String): JSONObject {
            File(work, "ran").delete()
            val gone = ProcessBuilder("true").start().apply { waitFor() }.pid().toString()  // a Soma that has quit already
            val p = ProcessBuilder("/bin/sh", script.path, "linux", gone, "${work.path}/old.jar", "${work.path}/$newJar", "f0ed9535", "85a29c49", work.path, java.path)
                .apply { environment()["SOMA_SWAP_WAIT"] = "3" }.redirectErrorStream(true).start()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            Thread.sleep(500)
            return JSONObject(File(work, "result.json").readText())
        }
        val good = swap("good.jar")
        assertTrue(good.toString(), good.getBoolean("ok"))
        assertEquals(listOf("${work.path}/good.jar"), File(work, "ran").readLines())
        val bad = swap("bad.jar")
        assertFalse(bad.getBoolean("ok"))
        assertEquals("start", bad.getString("step"))
        assertTrue(bad.getBoolean("runnerSwapped"))
        assertEquals(listOf("${work.path}/bad.jar", "${work.path}/old.jar"), File(work, "ran").readLines())  // the old one runs again
    }

    @Test fun thePillOpensWhatChangedAndLaterChangesNothing() = runComposeUiTest {
        val hands = Fake().apply { releases = listOf(release()) }
        val u = updater(hands).apply { check() }
        setContent { Box(Modifier.size(520.dp, 520.dp)) { Column { Row { UpdatePill(u) }; UpdateSheet(u) } } }
        onNodeWithText("update soma · f0ed9535", ignoreCase = true).performClick()
        onNodeWithText("Update Soma").assertExists()
        onNodeWithText("·  Enter sends on the Mac too").assertExists()
        onNodeWithText("85a29c49 → f0ed9535", substring = true, ignoreCase = true).assertExists()
        onNodeWithText("[ later ]").performClick()
        waitForIdle()
        assertFalse(u.open)
        assertEquals(UpdateState.Offer(release()), u.state)
        assertEquals(emptyList<String>(), hands.did)
    }

    @Test fun commitTimesReadAsTheSheetSaysThem() {
        assertEquals("Oct 29, 16:00", whenCommitted(1_761_753_600, ZoneId.of("UTC")))
    }

    /** The sheet in each state, as PNGs for the walk (build/update-shots), when -Dsoma.shots is set. */
    @Test fun shots() = runComposeUiTest {
        val out = System.getProperty("soma.shots")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return@runComposeUiTest
        out.mkdirs()
        val u = updater(Fake())
        val states = listOf(
            "1-offer" to UpdateState.Offer(release()),
            "2-waiting" to UpdateState.Waiting(release(), listOf("Fold GPU")),
            "3-updating" to UpdateState.Updating(release(), 2),
            "4-updated" to UpdateState.Updated("f0ed9535"),
            "5-failed" to UpdateState.Failed("85a29c49", "the download's sha256 didn't match the record (Soma-macos-arm64-f0ed9535.zip). Nothing was swapped.", "details", release()),
        )
        var shown by androidx.compose.runtime.mutableStateOf(states[0].second)
        setContent {
            Box(Modifier.size(520.dp, 420.dp).background(Pack.background).padding(12.dp)) {
                ShowState(u, shown)
            }
        }
        for ((name, state) in states) {
            shown = state
            waitForIdle()
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
    }
}

@androidx.compose.runtime.Composable
private fun ShowState(u: Updater, state: UpdateState) {
    u.forTest(state)
    u.open = true
    Column { Row { UpdatePill(u) }; UpdateSheet(u) }
}
