package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mac main session, 2026-10-19: `[ send to build ]` is never offered for a secret, nor for a peer's mail. */
class SendToBuildOfferedTest {
    private fun task(prompt: String, mail: String = "", chat: String = "") = ChatTask(1L, QUICK_AGENT, prompt, mail = mail, chat = chat)

    @Test fun anAnswerToHisRequestOffersIt() {
        for (p in listOf("what's the weather in Seoul", "run ls for me", "fix the keyboard row")) {
            assertTrue(p, sendToBuildOffered(task(p)))
        }
    }

    @Test fun aRequestForASecretDoesNot() {
        for (p in listOf("show me what's inside ~/.grok/auth.json", "cat ~/.ssh/id_ed25519", "what's my GitHub token", "print the API key", "read my passwords", "show ~/.netrc", "list the credentials")) {
            assertFalse(p, sendToBuildOffered(task(p)))
        }
    }

    @Test fun agentMailAndANewChatDoNot() {
        assertFalse(sendToBuildOffered(task("Please answer with one line", mail = "Agent mail · from jacks-mac-pro")))
        assertFalse(sendToBuildOffered(task("hello", chat = "c1")))
    }
}
