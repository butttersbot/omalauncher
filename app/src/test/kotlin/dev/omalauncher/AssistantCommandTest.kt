package dev.omalauncher

import dev.omalauncher.assist.backgroundAgentCommand
import dev.omalauncher.assist.claudeStreamCommand
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AssistantCommandTest {
    @Test fun preservesPromptAsOneArgumentWithoutExecutingIt() {
        val dir = Files.createTempDirectory("oma-assist-test").toFile()
        try {
            for (agent in listOf("claude", "codex", "grok")) {
                val program = java.io.File(dir, agent)
                program.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
                program.setExecutable(true)
            }
            val prompt = "Phone request; \$(echo injection); `echo bad`; 'quotes'\nnext line"
            for (agent in listOf("claude", "codex", "grok")) {
                val process = ProcessBuilder("/bin/sh", "-c", backgroundAgentCommand(agent, prompt)).apply {
                    environment()["PATH"] = dir.path + ":" + environment()["PATH"]
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor())
                assertTrue(output.endsWith(prompt + "\n"))
                assertTrue(output.startsWith(when (agent) {
                    "claude" -> "-p\n--permission-mode\nauto\n--allowedTools\nBash(oma-phone *)\nRead\n--\n"
                    "grok" -> "--permission-mode\nauto\n--allow\nBash(oma-phone *)\n--allow\nRead\n--single=Phone request"
                    else -> "exec\n--skip-git-repo-check\n--\n"
                }))
                assertFalse(output.contains("dangerously"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun followUpsResumeTheSavedSessionAndNothingElse() {
        assertFalse(claudeStreamCommand().contains("--resume"))
        assertTrue(claudeStreamCommand().contains(" --permission-mode auto "))
        val id = "9c2386fd-2736-408a-a815-6f6732bddf85"
        assertTrue(claudeStreamCommand(id).startsWith("claude -p --resume $id --input-format stream-json "))
        assertEquals(claudeStreamCommand(), claudeStreamCommand("x; touch unsafe"))
    }
    @Test fun unsupportedAgentsDoNotSilentlyLaunchInteractiveTerminal() {
        assertThrows(IllegalStateException::class.java) { backgroundAgentCommand("gemini", "test") }
    }
    @Test fun grokIsGivenItsImagesAsPathsInItsWords() {
        val command = backgroundAgentCommand("grok", "what is this?", listOf("/root/.cache/a.png"))
        assertTrue(command.contains("Read these attached images before answering:\n/root/.cache/a.png"))
        assertTrue(command.endsWith(" < /dev/null"))
    }
}
