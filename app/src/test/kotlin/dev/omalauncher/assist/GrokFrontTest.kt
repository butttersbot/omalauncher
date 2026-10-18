package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Grok as the front agent: who answers, the command it runs, and its output read as Claude Code's. */
class GrokFrontTest {
    private val id = "55a7b410-5dd7-43d3-ab41-34b3554d5270"

    /** What a `grok` that prints its arguments, one to a line, is given by [command]. */
    private fun arguments(command: String): List<String> {
        val dir = Files.createTempDirectory("oma-grok-test").toFile()
        try {
            val program = java.io.File(dir, "grok")
            program.writeText("#!/bin/sh\nprintf '%s\\0' \"\$@\"\n")
            program.setExecutable(true)
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.path + ":" + environment()["PATH"]
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return output.split('\u0000').dropLast(1)
        } finally { dir.deleteRecursively() }
    }

    @Test fun grokAnswersOnceInstalledAndSignedInUnlessHaikuWasChosen() {
        assertEquals(FrontAgent.Grok, FrontAgent.pick(null, grok = true, signedIn = true, claude = true))
        assertEquals(FrontAgent.Grok, FrontAgent.pick("grok\n", grok = true, signedIn = true, claude = false))
        assertEquals(FrontAgent.Haiku, FrontAgent.pick("haiku\n", grok = true, signedIn = true, claude = true))
        // Grok not there or not signed in: Haiku answers, so no request goes to a Grok that can only fail.
        assertEquals(FrontAgent.Haiku, FrontAgent.pick(null, grok = false, signedIn = false, claude = true))
        assertEquals(FrontAgent.Haiku, FrontAgent.pick("grok", grok = true, signedIn = false, claude = true))
        assertNull(FrontAgent.pick("grok", grok = true, signedIn = false, claude = false))
        assertNull(FrontAgent.pick("haiku", grok = true, signedIn = true, claude = false))
        // A word it doesn't know is no choice: Grok, as with none.
        assertEquals(FrontAgent.Grok, FrontAgent.pick("opus", grok = true, signedIn = true, claude = true))
    }

    @Test fun theQuestionIsOneArgumentAndIsNotExecuted() {
        val prompt = "--yolo; \$(echo injection); `echo bad`; 'quotes' \"double\"\nnext line --allow Bash"
        val args = arguments(grokQuickCommand(prompt))
        assertEquals("--single=$prompt", args.last())
        assertEquals(1, args.count { it.startsWith("--single") })
        assertEquals(listOf("--output-format", "streaming-messages-json", "--include-partial-messages", "--model", QUICK_GROK_MODEL), args.take(5))
        assertEquals(quickSystemPrompt(), args[args.indexOf("--rules") + 1])
        assertEquals("dontAsk", args[args.indexOf("--permission-mode") + 1])
        assertTrue(args.containsAll(listOf("--no-subagents", "--no-plan")))
        assertFalse(args.any { it == "--yolo" || it == "--always-approve" || it.contains("bypass") })
        assertTrue(grokQuickCommand(prompt).endsWith(" < /dev/null"))
    }

    @Test fun itMayRunWhatHaikuMayAndNothingMore() {
        val args = arguments(grokQuickCommand("q"))
        assertEquals(GROK_QUICK_TOOLS.joinToString(","), args[args.indexOf("--tools") + 1])
        val allowed = args.withIndex().filter { it.value == "--allow" }.map { args[it.index + 1] }
        assertEquals(GROK_QUICK_ALLOWED, allowed)
        assertEquals(QUICK_ALLOWED.filter { it.startsWith("Bash(") }, allowed.filter { it.startsWith("Bash(") })
        assertTrue(allowed.none { it == "Bash" || it == "Bash(*)" || it.startsWith("Edit") || it.startsWith("Write") })
        assertFalse(GROK_QUICK_TOOLS.any { it in setOf("write", "search_replace", "Delete", "task") })
    }

    @Test fun aNewChatHasTheWebAloneAndItsRule() {
        val args = arguments(grokQuickCommand("q", id, mac = true, newChat = true))
        assertEquals("web_search,web_fetch", args[args.indexOf("--tools") + 1])
        assertEquals(listOf("WebFetch"), args.withIndex().filter { it.value == "--allow" }.map { args[it.index + 1] })
        assertEquals(quickSystemPrompt(mac = true, newChat = true), args[args.indexOf("--rules") + 1])
    }

    @Test fun onlyAConversationIdIsResumed() {
        assertEquals(id, arguments(grokQuickCommand("q", id)).let { it[it.indexOf("--resume") + 1] })
        for (bad in listOf("x; touch unsafe", "", "$id; rm -rf /", "\$(reboot)")) assertEquals(grokQuickCommand("q"), grokQuickCommand("q", bad))
    }

    @Test fun eachAgentFollowsUpOnlyOnItsOwnConversation() {
        assertEquals("Grok:$id", frontThread(FrontAgent.Grok, id))
        assertEquals(id, frontSession(FrontAgent.Grok, "Grok:$id"))
        assertNull(frontSession(FrontAgent.Haiku, "Grok:$id"))
        // One kept before there was a choice is Haiku's.
        assertEquals(id, frontSession(FrontAgent.Haiku, id))
        assertNull(frontSession(FrontAgent.Grok, id))
        assertNull(frontThread(FrontAgent.Grok, null))
        assertNull(frontSession(FrontAgent.Grok, null))
    }

    @Test fun haikuIsStillWhatItWas() {
        assertEquals(quickCommand("q", id, mac = true), frontCommand(FrontAgent.Haiku, "q", id, mac = true))
        assertEquals(grokQuickCommand("q", id), frontCommand(FrontAgent.Grok, "q", id))
    }

    @Test fun grokAnswersUnderItsOwnName() {
        assertEquals("Grok", agentName(FrontAgent.Grok.model))
        assertEquals("Haiku", agentName(FrontAgent.Haiku.model))
    }

    /** Lines in the shape Grok's manual gives for `streaming-messages-json`, read by the same reader as Claude Code's. */
    @Test fun grokStreamIsReadAsClaudeCodes() {
        val init = """{"type":"system","subtype":"init","session_id":"$id","apiKeySource":"oauth","model":"$QUICK_GROK_MODEL","cwd":"/root","permissionMode":"dontAsk","tools":["read_file"],"uuid":"u1"}"""
        val delta = """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It is "}},"session_id":"$id"}"""
        val message = """{"type":"assistant","message":{"id":"msg_0","type":"message","role":"assistant","model":"$QUICK_GROK_MODEL","content":[{"type":"text","text":"It is noon."},{"type":"tool_use","id":"call_1","name":"run_terminal_cmd","input":{"command":"date"}}],"stop_reason":"tool_use"},"parent_tool_use_id":null,"session_id":"$id","uuid":"u2"}"""
        val result = """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"result":"It is noon.","stop_reason":"end_turn","session_id":"$id","uuid":"u3"}"""
        val failed = """{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Not signed in"],"session_id":"$id"}"""
        assertEquals(QuickEvent.Session(id), quickEvent(init))
        assertEquals(QuickEvent.Delta("It is "), quickEvent(delta))
        assertEquals(QuickEvent.Message("It is noon.", listOf("run_terminal_cmd: date")), quickEvent(message))
        assertEquals(QuickEvent.Result("It is noon.", false, id), quickEvent(result))
        assertEquals(QuickEvent.Result("Not signed in", true, id), quickEvent(failed))
        assertEquals("It is noon.", listOf(init, delta, message, result).mapNotNull(::quickEvent).fold(QuickAnswer()) { a, e -> a.after(e) }.text)
    }
}
