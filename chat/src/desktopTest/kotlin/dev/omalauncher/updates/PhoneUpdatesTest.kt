package dev.omalauncher.updates

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PhoneUpdatesTest {
    private val soma = AppOffer("dev.omalauncher.boss", "Soma", "175", "176", 176, "Oct 29, 18:10", listOf("Connections matches its approved design."),
        35, 35, DesignLine(9, 3, 0, true), launcher = true, phoneSteps = listOf("a", "b"))
    private val messages = AppOffer("dev.omalauncher.messages", "Soma Messages", "0.8.5", "0.8.6", 86, "Oct 29, 18:02", listOf("Replies thread."),
        9, 9, DesignLine(3, 0, 0, true), failure = "Android asked for a confirmation")
    private val hunch = AppOffer("dev.omalauncher.hunch", "Hunch", "0.7.2", "0.7.3", 18, "Oct 29, 17:40", emptyList(), 7, 7, DesignLine())

    @Test fun onlyWhatNeedsHisTapCountsAndNoPillWhenNothingDoes() {
        val u = PhoneUpdates()
        assertNull(phonePillLabel(u))
        u.offers = listOf(hunch)
        assertNull("an app on its way by itself is no reason for a pill", phonePillLabel(u))
        u.offers = listOf(hunch, soma)
        assertEquals("update soma · 176" to true, phonePillLabel(u))
        u.offers = listOf(hunch, messages, soma)
        assertEquals("updates · 2" to true, phonePillLabel(u))
        assertEquals(listOf(soma, messages), needsTap(u.offers))
        u.launcher = LauncherState.Waiting(listOf("Phone B"))
        assertEquals("waiting" to false, phonePillLabel(u))
        u.launcher = LauncherState.Failed("175", "x", "{}")
        assertEquals("didn't update" to true, phonePillLabel(u))
    }

    @Test fun designLineNeverHidesUnapprovedOrUnchecked() {
        assertEquals("design: 9 match · 3 not approved yet", DesignLine(9, 3, 0, true).warning)
        assertEquals("design: 2 match · 1 not checked", DesignLine(2, 0, 1, true).warning)
        assertEquals("design: not checked", DesignLine().warning)
        assertNull(DesignLine(4, 0, 0, true).warning)
        assertEquals("design: 4 match", DesignLine(4, 0, 0, true).good)
    }

    @Test fun sheetShowsSomaTheWaitingAppAndTheHistoryAndOnlyATapUpdates() = runComposeUiTest {
        val u = PhoneUpdates()
        val tapped = mutableListOf<String>()
        u.update = { tapped += it.pkg }
        u.offers = listOf(soma, messages, hunch)
        u.history = listOf(AutoUpdated("Soma Notes", "0.1.10", "0.1.11", "16:58"))
        setContent { PhoneUpdatePill(u) }
        assertTrue(tapped.isEmpty())
        onNodeWithContentDescription("Updates", substring = true).performClick()
        onNodeWithText("What changed", ignoreCase = true).assertExists()
        onNodeWithText("[ update and restart Soma ]").assertExists()
        onNodeWithText("didn’t update by itself: Android asked for a confirmation", ignoreCase = true).assertExists()
        onNodeWithText("design: 9 match · 3 not approved yet", ignoreCase = true).assertExists()
        onNodeWithText("Soma Notes 0.1.10 → 0.1.11").performScrollTo().assertExists()
        // Hunch is updating by itself: no panel, no button of its own.
        onAllNodesWithText("[ update Hunch ]").assertCountEquals(0)
        onNodeWithText("[ update Soma Messages ]").performScrollTo().performClick()
        assertEquals(listOf("dev.omalauncher.messages"), tapped)
    }

    @Test fun waitingNamesWhoAndCancelIsOffered() = runComposeUiTest {
        val u = PhoneUpdates()
        var cancelled = false
        u.cancel = { cancelled = true }
        u.offers = listOf(soma)
        u.launcher = LauncherState.Waiting(listOf("Phone B (“Connections walk”)"))
        u.open = true
        setContent { PhoneUpdateSheet(u) }
        onNodeWithText("Waits for Phone B (“Connections walk”) to finish", substring = true).assertExists()
        onNodeWithText("[ cancel ]").performClick()
        assertTrue(cancelled)
    }

    @Test fun updatedAfterReopeningSaysWhatRunsAndWhatWaits() = runComposeUiTest {
        val u = PhoneUpdates()
        var seen = false
        u.seen = { seen = true }
        u.launcher = LauncherState.Updated("176", 6)
        u.open = true
        setContent { PhoneUpdateSheet(u) }
        onNodeWithText("Soma 176 is running. 6 phone checks wait for us", substring = true).assertExists()
        onNodeWithText("[ later ]").performClick()
        assertTrue(seen)
    }
}
