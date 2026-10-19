package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * Grok as the front agent (Boss, 2026-10-19): the same prompt, tools and allowed commands as Claude's, nothing that
 * can change anything, and its stream read as Claude's is. The lines below are Grok 1.0.62's own, from a run on the Fold.
 */
class GrokFrontTest {
    private val id = "01a1531b-d72c-7f53-8545-a0db27e3f963"

    /** What a `grok` that prints its arguments, one to a line, is given by [command]. */
    private fun arguments(command: String): List<String> {
        val dir = Files.createTempDirectory("oma-grok-test").toFile()
        try {
            val program = java.io.File(dir, "grok")
            program.writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\0' \"\$a\"; done\n")
            program.setExecutable(true)
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.path + ":" + environment()["PATH"]
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return output.split('\u0000').dropLast(1)
        } finally { dir.deleteRecursively() }
    }

    @Test fun grokIsAskedTheSameAsClaudeAndTheQuestionIsNotExecuted() {
        val prompt = "Quick one; \$(echo injection); `echo bad`; 'quotes' \"double\"\nnext line -- --always-approve"
        val command = quickCommand(prompt, agent = FRONT_GROK)
        assertTrue(command.startsWith("grok "))
        assertTrue(command.endsWith(" < /dev/null"))
        val allowed = QUICK_ALLOWED.filter { it.startsWith("Bash(") || it == "WebFetch" }
        val expected = listOf(
            "--single=$prompt", "-m", GROK_QUICK_MODEL, "--output-format", "streaming-messages-json", "--include-partial-messages",
            "--permission-mode", "dontAsk", "--no-auto-update", "--disallowed-tools", "Agent", "--rules", quickSystemPrompt(),
            "--tools", "read_file,run_terminal_cmd,web_search,web_fetch",
        ) + allowed.flatMap { listOf("--allow", it) }
        assertEquals(expected, arguments(command))
    }

    /** Pinned: refused without asking, no approving everything, no subagents, no tool that writes. */
    @Test fun grokCanNeverApproveItselfOrChangeAnything() {
        for (command in listOf(
            quickCommand("q", agent = FRONT_GROK), quickCommand("q", id, mac = true, agent = FRONT_GROK),
            quickCommand("q", newChat = true, agent = FRONT_GROK), quickDesktopCommand("q", canvas = true, agent = FRONT_GROK),
        )) {
            assertTrue(command, command.contains(" --permission-mode dontAsk "))
            assertTrue(command, command.contains(" --disallowed-tools Agent "))
            // Every argument but the question and the prompt's own words.
            val given = arguments(command)
            val flags = given.filterIndexed { i, it -> !it.startsWith("--single=") && (i == 0 || given[i - 1] != "--rules") }
            for (never in listOf("always-approve", "yolo", "bypass", "acceptEdits", "search_replace", "write", "Edit", "--trust")) {
                assertFalse("$never in $flags", flags.any { it.contains(never) })
            }
        }
        // A new chat has the web and nothing else, as Claude's does.
        val chat = arguments(quickCommand("q", newChat = true, agent = FRONT_GROK))
        assertEquals("web_search,web_fetch", chat[chat.indexOf("--tools") + 1])
        assertEquals(listOf("WebFetch"), chat.withIndex().filter { (i, _) -> i > 0 && chat[i - 1] == "--allow" }.map { it.value })
    }

    @Test fun aFollowUpResumesOnlyAValidSession() {
        assertFalse(quickCommand("q", agent = FRONT_GROK).contains("--resume"))
        assertTrue(quickCommand("q", id, agent = FRONT_GROK).contains(" --resume $id "))
        for (bad in listOf("x; touch unsafe", "", "$id; rm -rf /", "\$(reboot)")) {
            assertEquals(quickCommand("q", agent = FRONT_GROK), quickCommand("q", bad, agent = FRONT_GROK))
        }
    }

    @Test fun claudeStaysTheFrontAgentUnlessGrokIsPickedAndInstalled() {
        val both = listOf("claude", "grok")
        assertEquals(FRONT_GROK, frontAgentOf("grok\n", both))
        assertEquals(FRONT_GROK, frontAgentOf(" Grok ", both))
        assertEquals(FRONT_CLAUDE, frontAgentOf("grok", listOf("claude")))
        for (word in listOf(null, "", "claude", "codex", "grok-4")) assertEquals(FRONT_CLAUDE, frontAgentOf(word, both))
        assertEquals(quickCommand("q", id), quickCommand("q", id, agent = FRONT_CLAUDE))
        assertEquals("Grok", agentName(frontModel(FRONT_GROK)))
        assertEquals("Haiku", agentName(frontModel(FRONT_CLAUDE)))
    }

    @Test fun neitherAgentIsAskedToContinueTheOthersConversation() {
        val claude = "55a7b410-5dd7-43d3-ab41-34b3554d5270"
        assertEquals(claude, frontKept(FRONT_CLAUDE, claude))
        assertEquals("grok:$id", frontKept(FRONT_GROK, id))
        assertNull(frontKept(FRONT_GROK, null))
        assertEquals(id, frontResumed(FRONT_GROK, frontKept(FRONT_GROK, id)))
        assertEquals(claude, frontResumed(FRONT_CLAUDE, frontKept(FRONT_CLAUDE, claude)))
        assertNull(frontResumed(FRONT_GROK, claude))
        assertNull(frontResumed(FRONT_CLAUDE, "grok:$id"))
        assertNull(frontResumed(FRONT_GROK, ""))
        assertNull(frontResumed(FRONT_CLAUDE, null))
    }

    @Test fun grokStreamIsReadAsClaudesIs() {
        assertEquals(QuickEvent.Session(id), quickEvent("""{"type":"system","subtype":"init","session_id":"$id","apiKeySource":"oauth","model":"grok-4.7-build-fast","cwd":"/tmp","permissionMode":"dontAsk","tools":["run_terminal_command","read_file","web_search","web_fetch"]}"""))
        assertEquals(QuickEvent.Delta("The"), quickEvent("""{"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"The"}},"parent_tool_use_id":null,"session_id":"$id","uuid":"a76e6842-5023-4add-b33a-0e7062c8376b"}"""))
        assertNull(quickEvent("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"kotlin\"}"}},"session_id":"$id"}"""))
        // Its thinking is not part of the answer.
        val message = quickEvent("""{"type":"assistant","message":{"id":"msg_0","type":"message","role":"assistant","model":"grok-4.7-build-fast","content":[{"type":"thinking","thinking":"I will run date first.","signature":"x"},{"type":"text","text":"I'll run `date` first."},{"type":"tool_use","id":"call_1","name":"run_terminal_command","input":{"command":"date","description":"Print the date"}}]},"session_id":"$id"}""")
        assertTrue(message is QuickEvent.Message)
        assertEquals("I'll run `date` first.", (message as QuickEvent.Message).text)
        assertEquals(1, message.steps.size)
        assertEquals(
            QuickEvent.Result("Mon Oct 19 00:41:38 PDT 2026.", false, id),
            quickEvent("""{"type":"result","subtype":"success","is_error":false,"num_turns":3,"result":"Mon Oct 19 00:41:38 PDT 2026.","stop_reason":"end_turn","session_id":"$id"}"""),
        )
        // A command it may not run ends the run: failed, and the request goes on as when Claude fails.
        assertTrue((quickEvent("""{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"stop_reason":"cancelled","session_id":"$id"}""") as QuickEvent.Result).failed)
        assertEquals(QuickEvent.Lost, quickEvent("Error: Failed to restore session from remote: fetching session record: session get failed: 404 Not Found"))
    }
}
