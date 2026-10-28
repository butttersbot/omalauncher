package dev.omalauncher.desktop

import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.assist.QUESTION_SKIP_ANSWER
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DesktopQuestionAnswerTest {
    @Test fun questionAnswersUseTheEngineNonceGateInsteadOfTheDefaultFailure() {
        val home = Files.createTempDirectory("desktop-question-answer").toFile()
        System.setProperty("soma.home", home.path)
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        var result: MacApprovalOutcome? = null
        host.answerMacQuestions("expired-ticket", JSONObject().put("Layout?", QUESTION_SKIP_ANSWER)) { result = it }
        // No runner configured: the real engine rejects this nonce, rather than the host's default Failed.
        assertEquals(MacApprovalOutcome.Expired, result)
    }
}
