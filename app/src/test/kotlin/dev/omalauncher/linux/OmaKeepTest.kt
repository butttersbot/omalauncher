package dev.omalauncher.linux

import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.upgrade.ManagedFiles
import dev.omalauncher.upgrade.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `oma keep`: how it is installed and started, and what the script may never do. Its own behaviour (names, the
 * cap of eight, log trimming, stopping) is tested by src/test/python/test_oma_keep.py, run here when the
 * machine has python3, and runnable on its own in the guest.
 */
class OmaKeepTest {
    @get:Rule val tmp = TemporaryFolder()

    private val script = moduleFile("src/main/assets/$KEEP_ASSET")

    @Test fun itIsInstalledAsAnExecutableTheLauncherOwns() {
        val rootfs = File(tmp.root, "arch")
        val tool = keepTool(rootfs) { script.readBytes() }
        assertEquals(File(rootfs, "usr/local/lib/oma/keep"), tool.file)
        assertEquals(Policy.Owned, tool.policy)
        ManagedFiles(tmp.root, File(tmp.root, "managed.json")).sync(listOf(tool))
        assertTrue(tool.file.canExecute())
        assertEquals(script.readText(), tool.file.readText())
        assertTrue(tool.file.readText().startsWith("#!/usr/bin/env python3\n"))
    }

    @Test fun theLauncherInstallsAndStartsItWithLinux() {
        val runtime = moduleFile("src/main/kotlin/dev/omalauncher/Runtime.kt").readText()
        assertTrue(runtime.contains("{ keepGuestTools(context, proot.rootfs) },"))
        assertTrue(runtime.contains("launch(\"oma keep serve\") { keeperOpen = false }"))
        assertTrue(runtime.contains("listOf(OMA_COMMAND, KEEP_COMMAND, KEEP_PYTHON).any { !File(proot.rootfs, it).isFile }"))
        val ready = runtime.substringAfter("private suspend fun onLinuxReady()").substringBefore("\n    }\n")
        assertTrue(ready.contains("openKeeper()"))
    }

    @Test fun theGuestAgentsAreToldOfIt() {
        assertTrue(moduleFile("src/main/kotlin/dev/omalauncher/widgets/WidgetEngine.kt").readText().contains("`oma keep run NAME -- COMMAND ARGS`"))
    }

    @Test fun itListensOnNoNetworkAndRunsNoShellOfItsOwn() {
        val source = script.readText()
        for (never in listOf("AF_INET", "shell=True", "os.system", "os.popen", "0.0.0.0", "127.0.0.1")) assertFalse(never, source.contains(never))
        assertTrue(source.contains("os.umask(0o177)"))
        assertTrue(source.contains("os.chmod(SOCKET, 0o600)"))
        assertTrue(source.contains("MAX_KEPT = 8"))
        assertTrue(source.contains("LOG_CAP = 1 << 20"))
        assertTrue(source.contains("STOP_GRACE = 5.0"))
    }

    @Test fun itsOwnTestsPass() {
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 is needed to run the script's tests", python)
        // The script is the guest's (GNU stat, /proc): its tests run on Linux, not on the Mac.
        assumeTrue("Linux is needed to run the script's tests", System.getProperty("os.name") == "Linux")
        val tests = moduleFile("src/test/python/test_oma_keep.py")
        val run = ProcessBuilder("python3", "-I", tests.path).redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        assertEquals(out, 0, run.waitFor())
    }
}
