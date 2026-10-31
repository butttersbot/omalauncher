package dev.omalauncher.assist

import dev.omalauncher.phone.PhoneBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone rules are long and a session keeps every message: they go once a session, and stay reachable after that. */
class BuilderPromptTest {
    @Test fun aNewSessionGetsThePhoneRulesWhole() {
        for (id in listOf(BuilderId.A, BuilderId.B, BuilderId.C)) {
            val prompt = builderPrompt(id, "Do the thing", resumed = false)
            assertTrue(prompt.startsWith("Do the thing"))
            assertTrue(prompt.contains(PhoneBridge.INSTRUCTIONS))
            assertFalse(prompt.contains(PhoneBridge.RULES_POINTER))
        }
    }

    @Test fun aResumedSessionGetsNoneOfThemOnlyWhereTheyAre() {
        for (id in listOf(BuilderId.A, BuilderId.B, BuilderId.C)) {
            val prompt = builderPrompt(id, "Do the thing", resumed = true)
            assertTrue(prompt.startsWith("Do the thing"))
            for (rule in PhoneBridge.INSTRUCTIONS.lines().filter { it.isNotBlank() }) assertFalse(rule, prompt.contains(rule))
            assertTrue(prompt.contains(PhoneBridge.RULES_POINTER))
            assertTrue(PhoneBridge.RULES_POINTER.contains("/root/.local/share/omalauncher/phone-control.md"))
        }
        assertTrue(PhoneBridge.RULES_POINTER.length < 300)
    }

    @Test fun whatElseAMessageCarriesIsTheSameEitherWay() {
        val first = builderPrompt(BuilderId.A, "Go", resumed = false)
        val later = builderPrompt(BuilderId.A, "Go", resumed = true)
        assertEquals(first.replace(PhoneBridge.INSTRUCTIONS, PhoneBridge.RULES_POINTER), later)
        assertTrue(first.startsWith("Go\n\n" + PhoneBridge.INSTRUCTIONS + "\n" + GROKBOT_RULE + "\n" + MODEL_COMMAND_RULE + "\nAndroid build tools"))
        assertTrue(first.contains(dev.omalauncher.oma.PhoneArtifacts.INSTRUCTIONS))
    }

    @Test fun phoneDKeepsItsOwnRulesOnEveryMessage() {
        assertEquals(PHONE_D_INSTRUCTIONS + "\n\nGo", builderPrompt(BuilderId.D, "Go", resumed = true))
        assertEquals(PHONE_D_INSTRUCTIONS + "\n\nGo", builderPrompt(BuilderId.D, "Go", resumed = false))
    }

    /** Every kind of agent goes through this one line, with whether this run really goes on with a session. */
    @Test fun startSaysWhetherItIsResuming() {
        val tasks = java.io.File(dev.omalauncher.theme.pack.moduleFile("src/main/kotlin/dev/omalauncher"), "assist/AgentTasks.kt").readText()
        val start = tasks.substringAfter("private fun start(")
        assertTrue(start.contains("val fullPrompt = builderPrompt(id, imagePrompt, resumed != null)"))
        assertTrue(start.indexOf("val resumed = when") < start.indexOf("val fullPrompt ="))
        assertEquals(1, tasks.split("PhoneBridge.INSTRUCTIONS").size - 1)
    }
}
