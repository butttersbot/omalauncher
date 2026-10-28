package dev.omalauncher.oma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.*
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import org.junit.Test

class QuestionWithdrawalTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun otherDeviceAnswerClosesTheAlreadyOpenQuestionForm() = runComposeUiTest {
        val input = """{"questions":[{"question":"Layout?","options":[{"label":"Cover"}]}]}"""
        var pending by mutableStateOf(listOf(MacPending("mac/t2", "agent", MacApproval("q", "n", "AskUserQuestion", input, ""), "ticket", 0)))
        val host = object : SampleHost() { override fun macPending() = pending }
        setContent {
            Box(Modifier.size(475.dp, 751.dp)) {
                ChatArtifactWorkspace(host, "test", emptyList(), onDiscuss = {}) {
                    val open = LocalQuestionOpen.current
                    BracketAction("open test question", Pack.accent) {
                        open?.invoke(WaitingQuestion("ticket", "agent", input) { _, _ -> error("No local send expected") })
                    }
                }
            }
        }
        onNodeWithText("[ open test question ]").performClick()
        onNodeWithText("Before I continue").assertExists()
        runOnIdle { pending = emptyList() }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Before I continue").assertDoesNotExist()
        onNodeWithText("[ open test question ]").assertExists()
    }
}
