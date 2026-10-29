package dev.omalauncher.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Linux tray (Boss, 2026-10-29: "Yes, add a Waybar icon"): only its own token drives Soma, and its menu is the Mac's. */
class LinuxTrayTest {
    @Test fun onlyTheTraysTokenAndItsOwnWordsAreTaken() {
        val token = "a1b2c3"
        assertEquals("settings", trayWord("tray a1b2c3 settings", token))
        assertEquals("car pair", trayWord("tray a1b2c3 car pair", token))
        assertEquals("quit", trayWord("tray a1b2c3 quit", token))
        assertEquals("look city-pop", trayWord("tray a1b2c3 look city-pop", token))
        assertNull("a wrong token", trayWord("tray zzzzzz quit", token))
        assertNull("no tray running", trayWord("tray a1b2c3 quit", null))
        assertNull("a word the tray doesn't have", trayWord("tray a1b2c3 rm -rf", token))
        assertNull("a look that isn't one", trayWord("tray a1b2c3 look ../../x", token))
        assertNull("not the tray", trayWord("quit", token))
    }

    @Test fun theHelpersMenuIsTheMacTraysPythonSaysSo() {
        val python = listOf("/opt/homebrew/bin/python3", "/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() } ?: return
        val test = File("src/test/python/test_soma_tray.py").absoluteFile
        val run = ProcessBuilder(python, "-I", test.path).redirectErrorStream(true).start()
        val said = run.inputStream.bufferedReader().readText()
        assertEquals(said, 0, run.waitFor())
        assertTrue(said, said.contains("OK"))
    }
}
