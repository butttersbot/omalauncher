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
        assertEquals(listOf("--cwd", System.getenv("HOME") + "/" + GROK_FRONT_FOLDER, "--output-format", "streaming-messages-json", "--include-partial-messages", "--model", QUICK_GROK_MODEL), args.take(7))
        assertTrue(grokQuickCommand(prompt).startsWith("GROK_FOLDER_TRUST=0 OMA_FRONT_GUARD=1 OMA_FRONT_ALLOW='"))
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
        // No reading files: the guard only sees commands, and a read printed Grok's own tokens (2026-10-19).
        assertFalse("read_file" in GROK_QUICK_TOOLS)
        assertFalse("Read" in GROK_QUICK_ALLOWED)
        // Refused whatever the guest's settings allow: MCP tools, edits, and the mkdir and touch Grok counts as read-only.
        assertEquals(GROK_QUICK_DENIED, args.withIndex().filter { it.value == "--deny" }.map { args[it.index + 1] })
        assertEquals(listOf("MCPTool", "Edit", "Write", "Bash(mkdir*)", "Bash(touch*)"), GROK_QUICK_DENIED)
        val chat = arguments(grokQuickCommand("q", newChat = true))
        assertEquals(GROK_QUICK_DENIED, chat.withIndex().filter { it.value == "--deny" }.map { chat[it.index + 1] })
    }

    @Test fun aNewChatHasTheWebAloneAndItsRule() {
        val args = arguments(grokQuickCommand("q", id, mac = true, newChat = true))
        assertEquals("web_search,web_fetch", args[args.indexOf("--tools") + 1])
        assertEquals(listOf("WebFetch"), args.withIndex().filter { it.value == "--allow" }.map { args[it.index + 1] })
        assertEquals(quickSystemPrompt(mac = true, newChat = true), args[args.indexOf("--rules") + 1])
    }

    @Test fun onlyAConversationIdIsResumed() {
        assertEquals(id, arguments(grokQuickCommand("q", id)).let { it[it.indexOf("--resume") + 1] })
        for (bad in listOf("x; touch unsafe", "", "$id; rm -rf /", "\$(reboot)", "------------------------------------")) assertEquals(grokQuickCommand("q"), grokQuickCommand("q", bad))
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

    /** [GROK_FRONT_GUARD] as Grok runs it: the event on stdin, a deny printed and exit 2, or exit 0 to let it through. */
    private fun guard(command: String, allow: String?, on: Boolean = true, truncated: Boolean = false): Int {
        val dir = Files.createTempDirectory("oma-guard-test").toFile()
        try {
            assertTrue(writeGrokGuard(dir))
            val process = ProcessBuilder(java.io.File(dir, ".grok/hooks/oma-front.sh").path).apply {
                environment().remove("OMA_FRONT_GUARD"); environment().remove("OMA_FRONT_ALLOW")
                if (on) environment()["OMA_FRONT_GUARD"] = "1"
                allow?.let { environment()["OMA_FRONT_ALLOW"] = it }
            }.start()
            process.outputStream.bufferedWriter().use { it.write(org.json.JSONObject().put("toolInput", org.json.JSONObject().put("command", command)).put("toolInputTruncated", truncated).toString() + "\n") }
            process.inputStream.readBytes()
            return process.waitFor()
        } finally { dir.deleteRecursively() }
    }

    @Test fun theGuardLetsThroughOnlyOnePlainListedCommand() {
        val allow = GROK_QUICK_ALLOWED.filter { it.startsWith("Bash(") }.joinToString("\n") { it.removePrefix("Bash(").removeSuffix(")") }
        for (ok in listOf("oma-phone status", "oma-phone screen --until Settings", "date +%H", "oma status", "oma-phone canvas place needs card top-left --page 'Soma'", "ls", "ls -la /root", "pwd")) assertEquals(ok, 0, guard(ok, allow))
        for (no in listOf("python3 -c 'print(1)'", "ls; id", "pwd -P && id", "oma-phone press OK", "date; rm -rf /", "date && id", "date \$(id)", "date `id`", "oma-phone status | sh", "oma status > /root/x", "date\nid", "oma-agents stop", "")) assertEquals(no, 2, guard(no, allow))
        // The X helper's question may hold any text the shell doesn't read inside double quotes (critic: "Q&A", "(Anthropic)").
        for (ok in listOf("grokbot x \"Q&A on SpaceX & Tesla (Claude) today; top 3 | links\"", "grokbot x \"what's new from @xai\"")) assertEquals(ok, 0, guard(ok, allow))
        for (no in listOf("grokbot x \"\$TSLA\"", "grokbot x \"a\" ; rm -rf ~ ; \"b\"", "grokbot x \"`id`\"", "grokbot x \"a\\\"\"", "grokbot x \"\"", "grokbot x 'single'; id")) assertEquals(no, 2, guard(no, allow))
        assertEquals(2, guard("grokbot x \"fine\"", "date*"))
        // A command cut short for being too long is never let through, however its start reads.
        assertEquals(2, guard("oma-phone canvas place needs card top-left [truncated]", allow, truncated = true))
        // With nothing listed, nothing runs; and outside the front agent's runs it lets everything through, for Grok to decide.
        assertEquals(2, guard("date", ""))
        assertEquals(2, guard("date", null))
        assertEquals(0, guard("python3 -c 'print(1)'", null, on = false))
    }

    @Test fun theGuardIsPutBackWhenChangedAndTheRunTurnsItOn() {
        val dir = Files.createTempDirectory("oma-guard-test").toFile()
        try {
            assertTrue(writeGrokGuard(dir))
            val script = java.io.File(dir, ".grok/hooks/oma-front.sh")
            script.writeText("#!/bin/sh\nexit 0\n")
            assertTrue(writeGrokGuard(dir))
            assertEquals(GROK_FRONT_GUARD, script.readText())
            assertEquals(GROK_FRONT_HOOK, java.io.File(dir, ".grok/hooks/oma-front.json").readText())
            org.json.JSONObject(GROK_FRONT_HOOK)
        } finally { dir.deleteRecursively() }
        assertEquals("GROK_FOLDER_TRUST=0 OMA_FRONT_GUARD=1 OMA_FRONT_ALLOW='date*\nsoma canvas *' ", grokGuardEnv(listOf("Read", "Bash(date*)", "WebFetch", "Bash(soma canvas *)")))
        assertTrue(grokQuickCommand("q", newChat = true).startsWith("GROK_FOLDER_TRUST=0 OMA_FRONT_GUARD=1 OMA_FRONT_ALLOW='' grok "))
    }

    /** Boss, 2026-10-19: Grok answers agent mail. Only a mail run may send, and only one answer to one mail. */
    @Test fun onlyAMailRunMaySendAndOnlyAnAnswer() {
        val allowed = { command: String -> arguments(command).let { a -> a.withIndex().filter { it.value == "--allow" }.map { a[it.index + 1] } } }
        assertFalse(GROK_MAIL_ALLOWED in allowed(grokQuickCommand("q")))
        assertEquals(GROK_QUICK_ALLOWED + GROK_MAIL_ALLOWED, allowed(grokQuickCommand("q", mail = true)))
        assertFalse(GROK_MAIL_ALLOWED in allowed(grokQuickCommand("q", newChat = true, mail = true)))
        assertEquals(quickCommand("q"), frontCommand(FrontAgent.Haiku, "q", mail = true))
        val plain = GROK_QUICK_ALLOWED.filter { it.startsWith("Bash(") }.joinToString("\n") { it.removePrefix("Bash(").removeSuffix(")") }
        val mail = plain + "\noma msg send *"
        val ok = "oma msg send jacks-mac-pro:t9f45b731 --reply-to 20261019-013350-e081b9 --as grok \"Done (it is 3 PM); A & B are free | ok\""
        assertEquals(0, guard(ok, mail))
        assertEquals(2, guard(ok, plain))
        for (no in listOf(
            "oma msg send jacks-mac-pro --as grok \"no reply-to\"",
            "oma msg send jacks-mac-pro --reply-to x \"\$(id)\"",
            "oma msg send jacks-mac-pro --reply-to x \"a\" ; id ; \"b\"",
            "oma msg send jacks-mac-pro --reply-to x \"`id`\"",
            "oma msg send \$(id) --reply-to x \"hi\"",
            "oma msg send jacks-mac-pro --reply-to x \"a\nb\"",
            "oma msg send jacks-mac-pro --reply-to x \"\"",
            "oma msg list",
        )) assertEquals(no, 2, guard(no, mail))
    }
}
