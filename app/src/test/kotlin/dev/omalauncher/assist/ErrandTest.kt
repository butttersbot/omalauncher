package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test

/**
 * A plain errand on the phone (Boss, 2026-10-16: phone control "quite slow"): the front agent's `<<phone>>` goes to the
 * builders as `<<build>>` does, but runs on the fast model in a session of its own, so the builder's own conversation
 * is neither read on every step nor replaced.
 */
class ErrandTest {
    @Test fun thePhoneMarkIsAnErrandAndEveryOtherPhoneMarkIsBuildWork() {
        assertEquals(FrontRead.HandOver(Route.PHONE), frontRead("<<phone>>", ended = true))
        assertEquals(FrontRead.HandOver(Route.PHONE), frontRead("  `<<phone>>`", ended = true))
        assertEquals(FrontRead.HandOver(Route.BUILD), frontRead("<<build>>", ended = true))
        // Still arriving: held back, never shown.
        assertEquals(FrontRead.Undecided, frontRead("<<pho"))
        assertEquals(FrontRoute.Errand, frontRoute(Route.PHONE, null))
        assertEquals(FrontRoute.Build, frontRoute(Route.BUILD, null))
    }

    @Test fun anErrandRunsOnTheFastModelAndResumesNothing() {
        val errand = claudeStreamCommand(null, ERRAND_MODEL)
        assertTrue(errand, errand.startsWith("claude -p --model sonnet --input-format stream-json"))
        assertFalse(errand.contains("--resume"))
        // Work on code stays on the default model, resuming the builder's own conversation.
        val work = claudeStreamCommand("4b9c2a8e-1f3d-4e5a-9b7c-0d1e2f3a4b5c")
        assertFalse(work.contains("--model"))
        assertTrue(work.contains("--resume 4b9c2a8e-1f3d-4e5a-9b7c-0d1e2f3a4b5c "))
    }
}

/** Boss, 2026-10-17: the ring stops "as soon as the agent stopped using" the phone. What counts as using it is a tool call. */
class PhoneToolCallTest {
    @Test fun aPhoneCommandIsPhoneUseAndAnythingElseIsNot() {
        for (yes in listOf("oma-phone screen", "oma-phone press \"Wi-Fi\"", "oma-phone press Connect && oma-phone expect Connected",
            "sleep 1; oma-phone screenshot | grep path", "cd /root && oma-phone tap 10 20", "(oma-phone read)")) {
            assertTrue(yes, phoneToolCall("Bash", yes))
        }
        // Asking whether phone control is on, or how it works, is not using the phone; nor is any other tool or command.
        for (no in listOf("oma-phone status", "oma-phone help", "git status", "grep oma-phone-thing x", "echo 'see foma-phone tap'", "")) {
            assertFalse(no, phoneToolCall("Bash", no))
        }
        assertFalse(phoneToolCall("Read", "oma-phone screen"))
        assertFalse(phoneToolCall("Edit", ""))
    }
}

/** Why Soma last ended, for `oma status` (AppExits.kt): Android's reasons in words. */
class AppExitsTest {
    @Test fun anExitReadsAsWhenWhyHowBigAndAndroidsNote() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("1970-01-01 00:00:01  killed for low memory, 3100 MB in use (lmkd)", exitLine(1_000, 3, 3_100L * 1024, " lmkd ", utc))
        assertEquals("1970-01-01 00:00:01  its package was updated", exitLine(1_000, 16, 0, null, utc))
        assertEquals("1970-01-01 00:00:01  crashed (boom)", exitLine(1_000, 4, 0, "boom", utc))
        assertEquals("unknown", exitReason(99))
    }
}
