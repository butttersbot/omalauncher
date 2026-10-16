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
