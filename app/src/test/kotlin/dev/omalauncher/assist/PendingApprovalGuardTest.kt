package dev.omalauncher.assist

import dev.omalauncher.phone.PhoneControlPolicy
import org.junit.Assert.*
import org.junit.Test

/** While the owner has an approval to answer, the agent may look at the phone but not touch it. */
class PendingApprovalGuardTest {
    private val acting = listOf("click", "type", "scroll", "press", "fill", "tap", "swipe", "back", "home", "recents", "notifications", "open")

    @Test fun everyPhoneCommandWorksWithNothingPending() {
        for (command in acting + PhoneControlPolicy.whilePending) PhoneControlPolicy.requireNoPendingApproval(0, command)
    }
    @Test fun nothingThatActsOnThePhoneRunsWhileAnApprovalIsPending() {
        for (pending in listOf(1, 2, 16)) for (command in acting + listOf("", "READ", "read ", "unknown")) {
            val error = assertThrows(command, IllegalStateException::class.java) { PhoneControlPolicy.requireNoPendingApproval(pending, command) }
            assertTrue(error.message!!.contains("approval is pending"))
        }
    }
    @Test fun lookingIsStillAllowedWhilePending() {
        assertEquals(setOf("read", "screen", "apps", "screenshot"), PhoneControlPolicy.whilePending)
        for (command in PhoneControlPolicy.whilePending) PhoneControlPolicy.requireNoPendingApproval(3, command)
    }
}
