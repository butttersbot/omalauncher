package dev.omalauncher.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneLaunchPolicyTest {
    @Test fun mainKeyboardDefersOnlyBackgroundActivityLaunches() {
        for (command in listOf("open", "url")) {
            assertTrue(PhoneLaunchPolicy.waits(true, command, true))
            assertFalse(PhoneLaunchPolicy.waits(true, command, false))
            assertFalse(PhoneLaunchPolicy.waits(false, command, true))
        }
        for (command in listOf("screen", "read", "press", "tap", "fill", "submit", "screenshot", "status"))
            assertFalse(command, PhoneLaunchPolicy.waits(true, command, true))
    }

    @Test fun waitingIsAnExplicitNonSuccessWithNoActionToReplayBlindly() {
        val result = PhoneLaunchPolicy.waiting()
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getBoolean("waiting"))
        assertFalse(result.getBoolean("actionMayHaveRun"))
        assertEquals("boss-typing", result.getString("reason"))
        assertTrue(result.getString("error").contains("Boss is typing"))
    }

    /** Agents' links reuse one browser tab; his own (Urls.kt) stay new tabs: only the agents' link command says the id. */
    @Test fun onlyAgentsLinksNameTheBrowserTab() {
        val carrying = java.io.File("src/main/kotlin").walk().filter { it.extension == "kt" && "EXTRA_APPLICATION_ID" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("PhoneService.kt"), carrying)
        val service = java.io.File("src/main/kotlin/dev/omalauncher/phone/PhoneService.kt").readText()
        val link = service.substringAfter("\"url\" -> {").substringBefore("launchOnDisplay")
        assertTrue(link.contains("putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, PhoneGuard.AGENT_BROWSER_ID)"))
        // Not a browser's own package: Chromium takes that to mean "replace whatever tab he has open".
        assertFalse(PhoneGuard.AGENT_BROWSER_ID.startsWith("com.brave") || PhoneGuard.AGENT_BROWSER_ID.startsWith("com.android.chrome"))
    }
}
