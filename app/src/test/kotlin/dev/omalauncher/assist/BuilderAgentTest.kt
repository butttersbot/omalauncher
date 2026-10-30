package dev.omalauncher.assist

import dev.omalauncher.linux.AGENTS
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuilderAgentTest {
    private val claude = AGENTS.first { it.bin == "claude" }
    private val codex = AGENTS.first { it.bin == "codex" }

    @Test fun eachBuilderUsesItsOwnProviderEvenWhenTheLauncherDefaultChanges() {
        val installed = listOf(claude, codex)
        assertEquals(BuilderAgent(claude), builderAgent(BuilderId.A, installed, claude))
        assertEquals(BuilderAgent(codex), builderAgent(BuilderId.A, installed, codex))
        assertEquals(BuilderAgent(codex, "gpt-6.1-sol"), builderAgent(BuilderId.B, installed, claude))
        assertEquals(BuilderAgent(codex, "gpt-6.1-sol"), builderAgent(BuilderId.B, installed, codex))
    }

    @Test fun missingCodexFallsBackWithAnExplanationAndWithoutTheCodexModel() {
        val choice = builderAgent(BuilderId.B, listOf(claude), claude)!!
        assertEquals(claude, choice.agent)
        assertNull(choice.model)
        assertTrue(choice.note!!.contains("Codex is not installed"))
        assertNull(builderAgent(BuilderId.B, emptyList(), null))
    }

    @Test fun codexReceivesTheSelectedModelImagesAndPromptAsSeparateLiteralArguments() {
        val dir = Files.createTempDirectory("soma-builder-command").toFile()
        try {
            File(dir, "codex").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
                setExecutable(true)
            }
            val prompt = "Read this; \$(echo injected) `echo unsafe` 'quoted'"
            val image = "/root/a photo's.png"
            val choice = builderAgent(BuilderId.B, listOf(claude, codex), claude)!!
            val command = backgroundAgentCommand(choice.agent.bin, prompt, listOf(image), choice.model, codexFullAccess = true)
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.path + ":" + environment()["PATH"]
            }.start()
            val args = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            assertEquals(listOf("exec", "--json", "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox", "--model", "gpt-6.1-sol", "--image", image, "--", prompt), args)
        } finally { dir.deleteRecursively() }
    }

    @Test fun fullAccessIsLimitedToPhoneBCodexAndDoesNotChangeClaudeFallback() {
        assertFalse(backgroundAgentCommand("codex", "test").contains("--dangerously-bypass"))
        assertEquals(backgroundAgentCommand("claude", "test"), backgroundAgentCommand("claude", "test", codexFullAccess = true))
    }

    @Test fun anAgentInstalledFromAShellIsFoundByOneRescanBeforeGivingUp() {
        var installed = listOf(claude)
        var rescans = 0
        val found = builderAgentFresh(BuilderId.D, { builderAgent(BuilderId.D, installed, claude) }) { rescans++; installed = listOf(claude, codex) }
        assertEquals(BuilderAgent(codex, PHONE_D_CODEX_MODEL), found)
        assertEquals(1, rescans)
        // Found at once: no rescan. Still missing after it: null, one rescan only.
        rescans = 0
        assertNotNull(builderAgentFresh(BuilderId.D, { builderAgent(BuilderId.D, installed, claude) }) { rescans++ })
        assertEquals(0, rescans)
        assertNull(builderAgentFresh(BuilderId.D, { builderAgent(BuilderId.D, listOf(claude), claude) }) { rescans++ })
        assertEquals(1, rescans)
    }

    @Test fun theMissingAgentIsNamedForEachBuilder() {
        assertEquals("Phone D needs Codex, which isn't installed on the phone.", missingBuilderAgentMessage(BuilderId.D))
        assertTrue(missingBuilderAgentMessage(BuilderId.B).startsWith("Phone B needs Codex"))
        assertTrue(missingBuilderAgentMessage(BuilderId.C).startsWith("Phone C needs Claude Code"))
        assertEquals("Install and sign in to an agent in Omalauncher first.", missingBuilderAgentMessage(BuilderId.A))
    }

    @Test fun mailToABuilderThatCannotStartIsRefusedToItsSender() {
        val find = { id: BuilderId -> builderAgent(id, listOf(claude), claude) }
        assertEquals("Phone D needs Codex, which isn't installed on the phone.", builderMailRefusal(BuilderId.D, find))
        assertNull(builderMailRefusal(BuilderId.B, find))
        assertNull(builderMailRefusal(null, find))
    }
}
