package dev.omalauncher.oma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.canvas.NeedsYouModule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Needs you's note on a live source (the Fold, the desktop, the car), where an answer that comes back "expired" also takes
 * the ask away, often in the same frame (slice 9b, round-2 review): the note stays through that, and goes at the next change.
 */
class NeedsYouNoteTest {
    private fun ask(id: String) = ModuleAsk(id, "Allow Bash on $id?", "ls", "mac:$id", "Mac", Any())

    /** The engine's order: the ask leaves the book, then the outcome is told, both before the next frame. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun expiredNoteOnALiveSourceWhenTheAskLeavesInTheSameFrame() = androidx.compose.ui.test.runComposeUiTest {
        var live by mutableStateOf(listOf(ask("t1"), ask("t2")))
        val calls = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            NeedsYouModule(live, ModuleSize.Card, onAnswer = { a, _, said ->
                calls += a.id
                live = live.filter { it.id != a.id } // book.remove + published()
                said("That request has expired") // told(Expired), same frame
            }, onOpen = {})
        }
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("t1"), calls)
        val shown = runCatching { onNodeWithText("That request has expired").assertExists(); true }.getOrDefault(false)
        assertEquals("the expired note is wiped by the ask leaving", true, shown)
    }

    /** The outcome told a frame later than the removal (the post landed after a frame). */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun expiredNoteOnALiveSourceWhenTheAskLeavesAFrameEarlier() = androidx.compose.ui.test.runComposeUiTest {
        var live by mutableStateOf(listOf(ask("t1"), ask("t2")))
        var tell: ((String?) -> Unit)? = null
        mainClock.autoAdvance = false
        setContent {
            NeedsYouModule(live, ModuleSize.Card, onAnswer = { a, _, said -> live = live.filter { it.id != a.id }; tell = said }, onOpen = {})
        }
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        mainClock.advanceTimeBy(500)
        tell!!("That request has expired")
        mainClock.advanceTimeBy(1_000)
        val shown = runCatching { onNodeWithText("That request has expired").assertExists(); true }.getOrDefault(false)
        assertEquals(true, shown)
        // And it stays while the asks don't change, goes when they do.
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("That request has expired").assertExists()
        live = live + ask("t3")
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("That request has expired").assertDoesNotExist()
    }

    /** A note whose ask stays ("The Mac could not take that answer…"): shown, stays. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aNoteWhoseAskStaysIsShown() = androidx.compose.ui.test.runComposeUiTest {
        var live by mutableStateOf(listOf(ask("t1"), ask("t2")))
        mainClock.autoAdvance = false
        setContent { NeedsYouModule(live, ModuleSize.Card, onAnswer = { _, _, said -> said("Mac unreachable. The request is still waiting there") }, onOpen = {}) }
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("Mac unreachable. The request is still waiting there").assertExists()
    }
}
