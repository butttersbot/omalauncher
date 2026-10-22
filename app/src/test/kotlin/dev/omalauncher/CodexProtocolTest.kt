package dev.omalauncher

import dev.omalauncher.assist.CodexProtocol
import dev.omalauncher.assist.CodexProtocol.Said
import org.junit.Assert.*
import org.junit.Test

/** Lines as `codex exec --json` 0.163 wrote them on the Fold, 2026-10-21. */
class CodexProtocolTest {
    @Test fun itsWordsAndStepsAreReadAndItsPromptIsNeverShown() {
        val lines = listOf(
            """{"type":"thread.started","thread_id":"01a16278-c8dc-72e0-b256-4de2800ac817"}""",
            """{"type":"turn.started"}""",
            """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I’ll run the command now.\n"}}""",
            """{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"/usr/bin/bash -lc 'echo hi'","aggregated_output":"","exit_code":null,"status":"in_progress"}}""",
            """{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"/usr/bin/bash -lc 'echo hi'","aggregated_output":"hi\n","exit_code":0,"status":"completed"}}""",
            """{"type":"item.completed","item":{"id":"item_2","type":"agent_message","text":"OK"}}""",
            """{"type":"turn.completed","usage":{"input_tokens":29415}}""",
            "Reading additional input from stdin...",
        )
        assertEquals(
            listOf(Said.Started("01a16278-c8dc-72e0-b256-4de2800ac817"), Said.Text("I’ll run the command now."), Said.Step("Bash: echo hi", "Bash", "echo hi"), Said.Text("OK")),
            lines.mapNotNull(CodexProtocol::read),
        )
    }

    @Test fun commandsAreUnwrappedFromItsShellAndPhoneStepsAreKnown() {
        assertEquals("oma-phone screen", CodexProtocol.shellBody("/usr/bin/bash -lc 'oma-phone screen'"))
        assertEquals("echo 'a b'", CodexProtocol.shellBody("bash -lc 'echo '\\''a b'\\'''"))
        assertEquals("ls -la", CodexProtocol.shellBody("ls -la"))
        val step = CodexProtocol.read("""{"type":"item.started","item":{"type":"command_execution","command":"/usr/bin/bash -lc 'oma-phone --display bg screen'"}}""") as Said.Step
        assertTrue(dev.omalauncher.assist.phoneToolCall(step.tool, step.command))
    }

    @Test fun editsSearchesAndFailuresSayWhatTheyAre() {
        assertEquals(Said.Step("Edit: a.kt, b.kt", "Edit", ""), CodexProtocol.read("""{"type":"item.started","item":{"type":"file_change","changes":[{"path":"a.kt","kind":"update"},{"path":"b.kt","kind":"add"}]}}"""))
        assertEquals(Said.Step("WebSearch", "WebSearch", ""), CodexProtocol.read("""{"type":"item.started","item":{"type":"web_search","query":"x"}}"""))
        assertEquals(Said.Failed("Not signed in"), CodexProtocol.read("""{"type":"turn.failed","error":{"message":"Not signed in"}}"""))
        assertNull(CodexProtocol.read("""{"type":"item.started","item":{"type":"reasoning","text":"…"}}"""))
        assertNull(CodexProtocol.read("not json"))
    }

    /** Boss, 2026-10-21: Phone B on Codex works as the Claude sessions do; his next words go on in the same conversation. */
    @Test fun aBuildersCodexReadsItsWordsFromStdinAndGoesOnWithItsConversation() {
        val dir = java.nio.file.Files.createTempDirectory("soma-codex-builder").toFile()
        try {
            java.io.File(dir, "codex").apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\ncat\n"); setExecutable(true) }
            fun run(command: String): List<String> {
                val process = ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = dir.path + ":" + environment()["PATH"] }.start()
                process.outputStream.bufferedWriter().use { it.write("his words; \$(echo no) 'q'") }
                return process.inputStream.bufferedReader().readLines().also { assertEquals(0, process.waitFor()) }
            }
            val id = "01a16278-c8dc-72e0-b256-4de2800ac817"
            assertEquals(
                listOf("exec", "resume", "--json", "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox", "--model", "gpt-6.1-sol", "--image", "/root/a b.png", "--", id, "-", "his words; \$(echo no) 'q'"),
                run(dev.omalauncher.assist.codexBuilderCommand(listOf("/root/a b.png"), "gpt-6.1-sol", id, fullAccess = true)),
            )
            assertEquals(listOf("exec", "--json", "--skip-git-repo-check", "--", "-", "his words; \$(echo no) 'q'"), run(dev.omalauncher.assist.codexBuilderCommand(emptyList(), null, null, fullAccess = false)))
        } finally { dir.deleteRecursively() }
    }
}
