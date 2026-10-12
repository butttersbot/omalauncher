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
            for (agent in listOf("claude", "codex")) {
                val program = java.io.File(dir, agent)
                program.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
                program.setExecutable(true)
            }
            val prompt = "Phone request; \$(echo injection); `echo bad`; 'quotes'\nnext line"
            for (agent in listOf("claude", "codex")) {
                val process = ProcessBuilder("/bin/sh", "-c", backgroundAgentCommand(agent, prompt)).apply {
                    environment()["PATH"] = dir.path + ":" + environment()["PATH"]
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor())
                assertTrue(output.endsWith(prompt + "\n"))
                assertTrue(output.startsWith(if (agent == "claude") "-p\n--allowedTools\nBash(oma-phone *)\nRead\n--\n" else "exec\n--skip-git-repo-check\n--\n"))
                assertFalse(output.contains("dangerously"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun followUpsResumeTheSavedSessionAndNothingElse() {
        assertFalse(claudeStreamCommand().contains("--resume"))
        val id = "9c2386fd-2736-408a-a815-6f6732bddf85"
        assertTrue(claudeStreamCommand(id).startsWith("claude -p --resume $id --input-format stream-json "))
        assertEquals(claudeStreamCommand(), claudeStreamCommand("x; touch unsafe"))
    }
    @Test fun unsupportedAgentsDoNotSilentlyLaunchInteractiveTerminal() {
        assertThrows(IllegalStateException::class.java) { backgroundAgentCommand("grok", "test") }
    }
}
