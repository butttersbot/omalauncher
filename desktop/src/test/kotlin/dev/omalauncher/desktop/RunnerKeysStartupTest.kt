package dev.omalauncher.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Soma's window never waits on the Keychain (Boss, 2026-10-28: the MacBook's new Soma showed no window while a Keychain
 * prompt for its runner key waited): what the window and the engine ask for is the last read, never a read of its own.
 */
class RunnerKeysStartupTest {
    @Test fun theWindowsRunnersAreTheLastReadNeverAKeychainRead() {
        val home = Files.createTempDirectory("soma-keys").toFile().also { System.setProperty("soma.home", it.path) }
        File(home, "runners.json").writeText("""{"runners":[{"id":"jacks-mac-pro","name":"Mac Pro","url":"http://127.0.0.1:1"}]}""")
        val platform = DesktopThreadsPlatform(Notices())
        // Listed, but not read yet: nothing, at once.
        val started = System.nanoTime()
        assertTrue(platform.machines().none { it.id == "jacks-mac-pro" })
        assertTrue("no wait", System.nanoTime() - started < 50_000_000)
    }

    @Test fun onlyTheKeysThreadReadsTheKeychain() {
        val main = File("src/main/kotlin/dev/omalauncher/desktop/Main.kt").readText()
        assertTrue("Main reads the Keychain in one place, the soma-keys thread", Regex("RunnerKeys\\.(read|machines)\\(\\)").findAll(main).count() == 1)
        val keysThread = main.substringAfter("thread(name = \"soma-keys\"").substringBefore("engine.open()")
        assertTrue(keysThread.contains("RunnerKeys.read()"))
        assertTrue(File("src/main/kotlin/dev/omalauncher/desktop/DesktopThreadsPlatform.kt").readText().contains("RunnerKeys.known"))
    }
}
