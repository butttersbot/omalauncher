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

    /** Agents' links reuse at most four browser tabs; his own (Urls.kt) stay new tabs: only the agents' link command says an id. */
    @Test fun onlyAgentsLinksNameABrowserTabAndThereAreFour() {
        val ids = listOf("A", "B", "C", "D").map(PhoneGuard::agentBrowserId)
        assertEquals(4, ids.toSet().size)
        for (other in listOf(null, "", "E", "a", "phone")) assertEquals(PhoneGuard.agentBrowserId("A"), PhoneGuard.agentBrowserId(other))
        val carrying = java.io.File("src/main/kotlin").walk().filter { it.extension == "kt" && "EXTRA_APPLICATION_ID" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("PhoneService.kt"), carrying)
        val service = java.io.File("src/main/kotlin/dev/omalauncher/phone/PhoneService.kt").readText()
        val link = service.substringAfter("\"url\" -> {").substringBefore("launchOnDisplay")
        assertTrue(link.contains("putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, PhoneGuard.agentBrowserId(requestBuilder))"))
        // Every oma-phone request says its builder, so a plan's url steps do too.
        assertTrue(java.io.File("src/main/assets/oma-phone.sh").readText().contains("'. + {builder:\$builder}'"))
    }
}
