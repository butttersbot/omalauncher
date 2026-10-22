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
            val command = backgroundAgentCommand(choice.agent.bin, prompt, listOf(image), choice.model)
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.path + ":" + environment()["PATH"]
            }.start()
            val args = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            assertEquals(listOf("exec", "--skip-git-repo-check", "--model", "gpt-6.1-sol", "--image", image, "--", prompt), args)
        } finally { dir.deleteRecursively() }
    }
}
