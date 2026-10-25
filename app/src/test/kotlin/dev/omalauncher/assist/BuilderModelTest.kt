package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test

/**
 * /model in a phone builder's tab (Boss, 2026-10-24). Each request is a new agent process, so Claude Code's own /model
 * lasts one process ("for this session only") and Codex exec has none: the phone answers it and starts every later
 * request on the chosen model.
 */
class BuilderModelTest {
    @Test fun onlyModelCommandsAreTakenAndEverythingElseGoesToTheAgent() {
        assertEquals(ModelCommand.Show, modelCommand("/model"))
        assertEquals(ModelCommand.Show, modelCommand("  /MODEL  "))
        assertEquals(ModelCommand.Show, modelCommand("/models"))
        assertEquals(ModelCommand.Choose("haiku"), modelCommand("/model haiku"))
        assertEquals(ModelCommand.Choose("claude-opus-5-5"), modelCommand("/model claude-opus-5-5"))
        assertEquals(ModelCommand.Choose("sonnet[1m]"), modelCommand("/model sonnet[1m]"))
        assertEquals(ModelCommand.Choose("gpt-6.1-sol"), modelCommand("/model gpt-6.1-sol"))
        assertEquals(ModelCommand.Choose(null), modelCommand("/model default"))
        for (words in listOf("model haiku", "use /model haiku", "/modelx", "/compact", "/help", "", "what model are you?")) assertNull(words, modelCommand(words))
        assertTrue(modelCommand("/model a b") is ModelCommand.Refused)
        for (bad in listOf("/model 'x'", "/model \$(rm)", "/model ;ls", "/model -x", "/model a`b`")) assertTrue(bad, modelCommand(bad) is ModelCommand.Refused)
    }

    @Test fun theChoiceStartsEveryLaterRequestAndAnErrandKeepsItsOwn() {
        assertEquals("haiku", builderModel(null, "haiku", null))
        assertEquals("haiku", builderModel(null, "haiku", PHONE_B_CODEX_MODEL))
        assertEquals(PHONE_B_CODEX_MODEL, builderModel(null, null, PHONE_B_CODEX_MODEL))
        assertNull(builderModel(null, null, null))
        assertEquals(ERRAND_MODEL, builderModel(ERRAND_MODEL, "opus", null))
        // On the command line, resuming the same conversation.
        val id = "4b9c2a8e-1f3d-4e5a-9b7c-0d1e2f3a4b5c"
        val cmd = claudeStreamCommand(id, builderModel(null, "haiku", null))
        assertTrue(cmd, cmd.startsWith("claude -p --resume $id --model haiku --input-format stream-json"))
        assertTrue(claudeStreamCommand(null, "sonnet[1m]").contains("--model 'sonnet[1m]' "))
        val codex = codexBuilderCommand(emptyList(), builderModel(null, "gpt-6.1-mini", PHONE_B_CODEX_MODEL), "abc", fullAccess = true)
        assertTrue(codex, codex.contains("--model 'gpt-6.1-mini' "))
        assertTrue(codex, codex.contains("resume "))
        assertEquals(PHONE_B_CODEX_MODEL, PHONE_B_CODEX_MODEL_NAME)
    }

    @Test fun theTabSaysWhatItRunsAndWhatChanged() {
        val show = modelReply(ModelCommand.Show, BuilderId.C, "Claude Code", "claude", null, null)
        assertTrue(show, show.startsWith("Phone C runs Claude Code on its default model."))
        assertTrue(show, show.contains("opus, sonnet, haiku"))
        assertEquals("Phone A runs Claude Code on haiku.", modelReply(ModelCommand.Show, BuilderId.A, "Claude Code", "claude", "haiku", null).lineSequence().first().substringBefore(" Models"))
        assertTrue(modelReply(ModelCommand.Show, BuilderId.B, "Codex", "codex", null, PHONE_B_CODEX_MODEL).startsWith("Phone B runs Codex on $PHONE_B_CODEX_MODEL."))
        assertEquals("Done: from the next message Phone C runs Claude Code on haiku. It goes on with the same conversation.",
            modelReply(ModelCommand.Choose("haiku"), BuilderId.C, "Claude Code", "claude", null, null))
        assertEquals("Done: from the next message Phone B runs Codex on $PHONE_B_CODEX_MODEL.",
            modelReply(ModelCommand.Choose(null), BuilderId.B, "Codex", "codex", "gpt-x", PHONE_B_CODEX_MODEL))
        assertEquals("Phone A runs Grok, whose model can't be switched from here.", modelReply(ModelCommand.Choose("x"), BuilderId.A, "Grok", "grok", null, null))
        assertFalse(modelSwitchable("grok")); assertTrue(modelSwitchable("claude")); assertTrue(modelSwitchable("codex"))
    }
}
