package dev.omalauncher.oma

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An agent can never get Boss's signature on its own text (DESIGN-GATE.md amendment 77d43019): only the chat's send
 * paths make his words, only Soma's own code signs them, and nothing an agent reaches (the phone bridge, mail, the
 * guest tools, artifacts' JavaScript) touches either.
 */
class BossReceiptSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun sources() = listOf("app/src/main", "chat/src").flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && "Test" !in f.path.substringAfter("/src/") && (f.extension == "kt" || f.extension == "sh" || f.extension == "py" || f.extension == "js" || f.extension == "html") }.toList() }
    private fun using(pattern: Regex) = sources().filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.toSortedSet()

    @Test fun onlyTheComposerAndTheQuestionFormMakeHisWords() {
        assertEquals(sortedSetOf("BossReceipt.kt"), using(Regex("""BossWords\(""")))
        assertEquals(sortedSetOf("OmaChatScreen.kt"), using(Regex("""BossWords\.chat\(""")))
        assertEquals(sortedSetOf("WaitingQuestions.kt"), using(Regex("""BossWords\.answered\(""")))
        assertEquals(sortedSetOf("ChatHost.kt", "OmaChatScreen.kt", "WaitingQuestions.kt"), using(Regex("""bossSaid\(""")) - "AndroidChatHost.kt")
    }

    @Test fun onlySomasOwnCodeSignsThemAndNothingAnAgentReachesDoes() {
        assertEquals(sortedSetOf("BossReceipts.kt", "CanvasReceiptKeys.kt"), using(Regex("""signBoss\(""")))
        assertEquals(sortedSetOf("AndroidChatHost.kt"), using(Regex("""BossReceipts\.record\(""")))
        for (name in listOf("PhoneBridge.kt", "PhoneService.kt", "PhoneHttp.kt", "AgentMail.kt", "ArtifactView.android.kt", "oma-msg.sh", "oma-phone.sh")) {
            val text = sources().first { it.name == name }.readText()
            assertTrue(name, "signBoss" !in text && "BossReceipts.record" !in text && "BossWords" !in text)
        }
        // Phone control marks every command that changes the screen, so a send it drove is never vouched for.
        val service = sources().first { it.name == "PhoneService.kt" }.readText()
        assertTrue(service.contains("if (command !in PhoneGuard.reading) dev.omalauncher.oma.BossTouch.agentActed()"))
        assertTrue(service.substringAfter("private fun act(").substringBefore("try {").contains("BossTouch.agentActed()"))
        assertTrue(service.substringAfter("private fun clickSaying(").substringBefore("val root").contains("BossTouch.agentActed()"))
        // Only a lifted finger on Soma's own screens counts as his touch.
        for (name in listOf("OmaChatActivity.kt", "MainActivity.kt")) assertTrue(name, sources().first { it.name == name }.readText().contains("noteTouch(ev)"))
    }
}
