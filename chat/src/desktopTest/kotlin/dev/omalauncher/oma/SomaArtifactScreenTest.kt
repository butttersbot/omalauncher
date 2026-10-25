package dev.omalauncher.oma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

/** Artifacts an agent makes with `soma-artifact` (features/artifact-panel/PLAN.md, slices 1 and 2), on the chat screen. */
@OptIn(ExperimentalTestApi::class)
class SomaArtifactScreenTest : ChatScreenTestBase() {
    /** A Mac thread t1 whose agent made notes.md; what its events say is [live], and every version reads as "Version N". */
    private open inner class MadeHost(steps: List<String>) : SampleHost() {
        var live by mutableStateOf(emptyList<ArtifactLive>())
        val reads = mutableListOf<String>()
        val entries = listOf(ChatTask(now - 10_000, "→ Notes", "write me notes", thread = "t1", threadName = "Notes", steps = steps, result = "Here they are.", status = TaskStatus.Done))
        override fun tasks() = entries
        override fun working() = false
        override fun builders() = emptyMap<dev.omalauncher.assist.BuilderId, BuilderState>()
        override fun liveArtifacts() = live
        override fun readArtifact(thread: String, path: String): ArtifactRead {
            synchronized(reads) { reads += path }
            val (name, asked) = parseSomaArtifactPath(path) ?: return ArtifactRead.Failed("not an artifact")
            val latest = live.firstOrNull { it.name == name }?.version?.takeIf { it > 0 } ?: 1
            val v = asked ?: latest
            return ArtifactRead.Ready(ArtifactDocument(name, "text/markdown", "# Version $v".toByteArray(), "sha$v", v, latest))
        }
        override fun artifactVersions(thread: String, name: String) = ArtifactVersions(name, "Notes", live.firstOrNull()?.version ?: 1, null,
            (1..(live.firstOrNull()?.version ?: 1)).map { ArtifactVersion(it, 1_700_000_000L + it, 10, if (it == 1) "first pass" else "second pass") })
        fun made(version: Int, editing: Int? = null, event: Long = version * 10L + (editing ?: 0)) {
            live = listOf(ArtifactLive("t1", "notes.md", version, editing, "Notes", "", "Claude", "Mac Pro", System.currentTimeMillis() / 1000, event))
        }
    }

    @Test fun unfoldedTheNewArtifactOpensBesideTheChatByItselfAndItsCardSaysSo() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNodeWithContentDescription("notes.md, version 1").assertExists()
        onNodeWithText("[ close ]").assertDoesNotExist()
        runOnIdle { host.made(1) }
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("notes.md, version 1, showing").assertExists()
        onNodeWithText("[ versions · 1 ]").assertExists()
        // The chat is still there to type in beside it.
        onNode(hasSetTextAction()).performTextInput("next")
        mainClock.advanceTimeBy(100)
        onNode(hasSetTextAction()).assertTextContains("next")
    }

    @Test fun aNewVersionReplacesTheOneShowingInPlaceAndOlderOnesAreAVersionsTapAway() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        runOnIdle { host.made(1) }
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 1").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { host.made(1, editing = 2) }
        mainClock.advanceTimeBy(1200)
        onNodeWithContentDescription("Claude is editing · v2").assertExists()
        runOnIdle { host.made(2) }
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 2").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Claude is editing · v2").assertDoesNotExist()
        // The card of the entry that made v1 no longer says it is the one showing.
        onNodeWithContentDescription("notes.md, version 1").assertExists()
        onNodeWithContentDescription("notes.md, version 1, showing").assertDoesNotExist()
        onNodeWithText("[ versions · 2 ]").performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("first pass").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("first pass").performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Viewing v1, an earlier version").assertExists()
        onNodeWithText("v1 of 2").assertExists()
        // Discuss names the version shown, and sends nothing.
        onNodeWithText("[ discuss ]").performClick()
        mainClock.advanceTimeBy(600)
        onNode(hasSetTextAction()).assertTextContains("About notes.md v1:", substring = true)
        onNodeWithText("[ back to latest ]").performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 2").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Viewing v1, an earlier version").assertDoesNotExist()
        assertTrue(synchronized(host.reads) { host.reads.toList() }.containsAll(listOf("soma-artifact:notes.md", "soma-artifact:notes.md?v=1")))
    }

    @Test fun foldedAnEditIsAStripAboveTheBoxAndTheChatKeepsTheScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        setContent { Box(Modifier.size(475.dp, 751.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).performTextInput("my draft")
        mainClock.advanceTimeBy(100)
        runOnIdle { host.made(1, editing = 2) }
        mainClock.advanceTimeBy(1200)
        onNodeWithContentDescription("Claude is editing notes · v2").assertExists()
        onNode(hasSetTextAction()).assertTextContains("my draft")
        onNodeWithText("[ ← chat ]").assertDoesNotExist()
        runOnIdle { host.made(2) }
        mainClock.advanceTimeBy(1200)
        onNodeWithContentDescription("notes · v2 is ready").assertExists()
        onNodeWithText("[ view ]").performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 2").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("[ ← chat ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertTextContains("my draft")
        onNodeWithContentDescription("notes · v2 is ready").assertDoesNotExist()
    }

    @Test fun theCardOfAnOlderVersionOpensThatVersionEvenAfterARestart() = runComposeUiTest {
        mainClock.autoAdvance = false
        // The app just started: nothing live, the entry's steps say v1, and the computer has v2.
        val host = object : MadeHost(listOf("made notes.md · v1")) {
            override fun readArtifact(thread: String, path: String): ArtifactRead {
                val (name, asked) = parseSomaArtifactPath(path)!!
                val v = asked ?: 2
                return ArtifactRead.Ready(ArtifactDocument(name, "text/markdown", "# Version $v".toByteArray(), "sha$v", v, 2))
            }
        }
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNodeWithContentDescription("notes.md, version 1").performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("v1 of 2").assertExists()
        onNodeWithText("Viewing v1, an earlier version").assertExists()
    }

    @Test fun aClosedPanelStaysClosedWhenTheEditEndsWithNothingNew() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        runOnIdle { host.made(1, editing = 2) }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ close ]").performClick()
        mainClock.advanceTimeBy(1200)
        runOnIdle { host.made(1, editing = null, event = 99) }
        mainClock.advanceTimeBy(1500)
        onNodeWithText("[ close ]").assertDoesNotExist()
    }

    @Test fun foldingWithTheArtifactBesideTheChatGivesTheScreenToTheChatAndOffersItInTheStrip() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        var width by mutableStateOf(933.dp)
        setContent { Box(Modifier.size(width, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        runOnIdle { host.made(1) }
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Version 1").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { width = 475.dp }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ ← chat ]").assertDoesNotExist()
        onNode(hasSetTextAction()).assertExists()
        onNodeWithContentDescription("notes · v1 is ready").assertExists()
        // Unfolding again shows it beside the chat.
        runOnIdle { width = 933.dp }
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("[ close ]").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun whatWasMadeBeforeTheChatOpenedOpensNothingByItself() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = MadeHost(listOf("made notes.md · v1"))
        host.made(1)
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(2000)
        onNodeWithText("[ close ]").assertDoesNotExist()
        onNodeWithText("[ artifacts · 1 ]").assertExists()
    }
}
