package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agents' own sessions stay off Boss's tabs (Boss, 2026-10-29: 76 design-judge calls, 17 titler calls, test suites and mesh
 * proofs filled them): a titler call is never saved as a session, and a test's or proof's thread is made internal.
 */
class InternalThreadsTest {
    @Test fun aTitlerCallIsNeverSavedAsASession() {
        val command = titleCommand(TitleAsk("fix the fold gpu", "Done."))
        assertTrue(command, command.contains("claude -p --no-session-persistence "))
    }

    @Test fun aTestOrProofThreadIsMadeInternalAndTheListAsksForThemOnlyWhenTold() {
        val made = MacRequests.makeThread("Mesh proof from mac-pro", "/Users/jackkim/Projects", internal = true)
        assertEquals(true, JSONObject(made.body!!).getBoolean("internal"))
        assertFalse(JSONObject(MacRequests.makeThread("Boss chat", "/Users/jackkim/Projects").body!!).has("internal"))
        assertEquals("/threads", MacRequests.threads().path)
        assertEquals("/threads?all=1", MacRequests.threads(all = true).path)
    }
}
