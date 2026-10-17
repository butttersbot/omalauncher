package dev.omalauncher.assist

import dev.omalauncher.theme.pack.mainSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned by reading the source (Soma Canvas slice 3a): a cockpit module answers an approval only through the chat's own
 * paths (ChatHost.answerMac / answerBuilder, with their guards and one-time tickets), only from the one adapter, and the
 * drawing code never holds the host at all. Nothing in the modules talks to a runner's approval endpoint.
 */
class ModuleApprovalGuardTest {
    private fun code(name: String) = mainSources("canvas").first { it.name == name }.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }.joinToString("\n")

    @Test fun onlyTheAdapterAnswersAndOnlyThroughTheChatsOwnPaths() {
        val files = mainSources("canvas")
        assertTrue("the modules were found", files.any { it.name == "Modules.kt" } && files.any { it.name == "ModuleViews.kt" })
        for (file in files) {
            val text = code(file.name)
            for (never in listOf("answerApproval", "MacApprovalBook", "MacRunnerClient", "macClient(", "MacThreads.answer", "engine.answer")) assertFalse("$never in ${file.name}", never in text)
        }
        val adapter = code("Modules.kt")
        assertEquals(1, Regex("""host\.answerMac\(""").findAll(adapter).count())
        assertEquals(1, Regex("""host\.answerBuilder\(""").findAll(adapter).count())
        // The drawing never reaches the host or the source: it is handed asks and an onAnswer to call on his tap.
        val views = code("ModuleViews.kt")
        assertFalse(views.contains("ChatHost") || views.contains("ModuleSource") || views.contains(".answer("))
        // Every approve is a tap that answers true, every deny one that answers false, and nothing else answers.
        for ((label, allow) in listOf("approve" to true, "yes" to true, "deny" to false, "no" to false))
            assertTrue("$label is a tap answering $allow", Regex("""ModuleButton\("$label", [^)]*\) \{ answer\($allow\) \}""").containsMatchIn(views))
        assertEquals("answer( only inside those four taps", 4, Regex("""answer\((true|false)\)""").findAll(views).count())
    }
}
