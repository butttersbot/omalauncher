package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** The quick lane's command, which is all that decides what it may do, and how its output becomes an answer. */
class QuickLaneTest {
    private val id = "55a7b410-5dd7-43d3-ab41-34b3554d5270"

    /** What a `claude` that prints its arguments, one to a line, is given by [command]. */
    private fun arguments(command: String): String {
        val dir = Files.createTempDirectory("oma-quick-test").toFile()
        try {
            val program = java.io.File(dir, "claude")
            program.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            program.setExecutable(true)
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.path + ":" + environment()["PATH"]
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return output
        } finally { dir.deleteRecursively() }
    }

    @Test fun theQuestionIsOneArgumentAfterTheDashesAndIsNotExecuted() {
        val prompt = "Quick one; \$(echo injection); `echo bad`; 'quotes' \"double\"\nnext line -- --allowedTools Bash"
        val expected = listOf(
            "-p", "--model", "haiku", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--permission-mode", "manual", "--permission-prompts", "none", "--append-system-prompt", quickSystemPrompt(),
            "--tools", "Read", "Bash", "WebSearch", "WebFetch", "--allowedTools",
        ) + QUICK_ALLOWED + listOf("--", prompt)
        assertEquals(expected.joinToString("\n") + "\n", arguments(quickCommand(prompt)))
        assertTrue(quickCommand(prompt).endsWith(" < /dev/null"))
    }

    @Test fun aFollowUpResumesOnlyAValidSession() {
        assertFalse(quickCommand("q").contains("--resume"))
        assertTrue(quickCommand("q", id).startsWith("claude -p --model haiku --resume $id --output-format stream-json "))
        assertTrue(arguments(quickCommand("q", id)).startsWith("-p\n--model\nhaiku\n--resume\n$id\n--output-format\n"))
        for (bad in listOf("x; touch unsafe", "", "$id; rm -rf /", "\$(reboot)")) assertEquals(quickCommand("q"), quickCommand("q", bad))
    }

    /** Pinned: the quick lane never raises an approval, never has a tool that changes a file, and never skips permissions. */
    @Test fun theCommandCanNeverAskForApprovalOrChangeAnything() {
        for (command in listOf(quickCommand("question"), quickCommand("question", id))) {
            for (never in listOf("permission-prompt-tool", "Edit", "Write", "dangerously", "bypass", "acceptEdits", "--input-format", "stdio")) {
                assertFalse("$never in $command", command.contains(never))
            }
            assertTrue(command.contains(" --permission-mode manual --permission-prompts none "))
        }
        assertEquals(listOf("Read", "Bash", "WebSearch", "WebFetch"), QUICK_TOOLS)
        assertEquals(
            listOf(
                "Read", "WebSearch", "WebFetch",
                "Bash(oma-phone status)", "Bash(oma-phone screen*)", "Bash(oma-phone read)", "Bash(oma-phone pages)",
                "Bash(oma-phone board*)", "Bash(oma-phone looks)", "Bash(oma-phone stickers)", "Bash(date*)",
            ),
            QUICK_ALLOWED,
        )
        // Bash is only ever allowed by a rule that names the command; never whole, and never every phone command.
        assertTrue(QUICK_ALLOWED.none { it == "Bash" || it == "Bash(*)" || it == "Bash(oma-phone *)" || it == "Bash(oma-phone*)" })
    }

    @Test fun itIsToldItCanLookAndNotHowToAct() {
        val told = quickSystemPrompt()
        assertFalse(told.contains("\n"))
        assertTrue(told.contains("quick lane")); assertTrue(told.contains("build lane")); assertTrue(told.contains("Markdown"))
        for (acting in listOf("press", "fill", "click", "tap", "swipe", "note", "page-add", "stick ", "look NAME")) assertFalse(acting, told.contains(acting))
    }

    @Test fun theStreamIsReadForTheSessionTheWordsAndTheResult() {
        assertEquals(QuickEvent.Session(id), quickEvent("""{"type":"system","subtype":"init","cwd":"/root","session_id":"$id","tools":["Bash","Read","WebFetch","WebSearch"],"model":"claude-haiku-5-5","permissionMode":"default"}"""))
        assertEquals(
            QuickEvent.Delta("It's Tuesday,"),
            quickEvent("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It's Tuesday,"}},"session_id":"$id","parent_tool_use_id":null}"""),
        )
        assertEquals(
            QuickEvent.Message("It's Tuesday, October 13, 2026.", emptyList()),
            quickEvent("""{"type":"assistant","message":{"model":"claude-haiku-5-5","role":"assistant","content":[{"type":"text","text":"It's Tuesday, October 13, 2026."}],"stop_reason":null},"session_id":"$id"}"""),
        )
        assertEquals(
            QuickEvent.Message("", listOf("Bash: date")),
            quickEvent("""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"date","description":"Print current date and time"}}]},"session_id":"$id"}"""),
        )
        assertEquals(
            QuickEvent.Result("It's Tuesday, October 13, 2026.", failed = false, session = id),
            quickEvent("""{"duration_api_ms":2559,"stop_reason":"end_turn","session_id":"$id","permission_denials":[],"is_error":false,"num_turns":2,"subtype":"success","result":"It's Tuesday, October 13, 2026.","type":"result"}"""),
        )
        assertEquals(
            QuickEvent.Result("Not logged in\nRun /login", failed = true, session = null),
            quickEvent("""{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"not-a-session","errors":["Not logged in","Run /login"]}"""),
        )
        assertEquals(QuickEvent.Lost, quickEvent("No conversation found with session ID: $id"))
    }

    @Test fun linesThatSayNothingAboutTheAnswerAreSkipped() {
        val skipped = listOf(
            "", "garbage", "{not json", "[1,2]", "null", "Warning: something on stderr",
            """{"type":"system","subtype":"status","status":"requesting","session_id":"$id"}""",
            """{"type":"system","subtype":"init","session_id":"x; touch unsafe"}""",
            """{"type":"system","subtype":"permission_denied","tool_name":"Bash","message":"Permission for this tool use was denied."}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\": \"date"}}}""",
            """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""",
            """{"type":"stream_event","event":{"type":"message_stop"}}""",
            """{"type":"stream_event"}""",
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"","signature":"CAQ"}]}}""",
            """{"type":"assistant"}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01","type":"tool_result","content":"Tue Oct 13 15:21:08 PDT 2026","is_error":false}]}}""",
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}""",
            // The build lane's approval request, which the quick lane has no way to be sent and no way to answer.
            """{"type":"control_request","request_id":"request-7","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"rm -rf build"}}}""",
        )
        for (line in skipped) assertNull(line, quickEvent(line))
    }

    @Test fun theAnswerGrowsAsItArrivesAndEndsAsTheResult() {
        var answer = QuickAnswer()
        fun read(event: QuickEvent) { answer = answer.after(event) }
        read(QuickEvent.Session(id)); assertEquals("", answer.text)
        read(QuickEvent.Delta("Let me ")); read(QuickEvent.Delta("look.")); assertEquals("Let me look.", answer.text)
        // The finished message stands for its pieces, once.
        read(QuickEvent.Message("Let me look.", emptyList())); assertEquals("Let me look.\n", answer.text)
        read(QuickEvent.Message("", listOf("Bash: date"))); assertEquals("Let me look.\n", answer.text)
        read(QuickEvent.Delta("It's ")); assertEquals("Let me look.\nIt's ", answer.text)
        read(QuickEvent.Lost); assertEquals("Let me look.\nIt's ", answer.text)
        read(QuickEvent.Result("It's Tuesday.", failed = false, session = id)); assertEquals("It's Tuesday.", answer.text)
        // A result with no words leaves what was said.
        assertEquals("so far", QuickAnswer("so far").after(QuickEvent.Result("", failed = true, session = null)).text)
        assertEquals(40_000, QuickAnswer("a".repeat(30_000), "b".repeat(30_000)).text.length)
    }

    @Test fun aRecordedRunReadsAsItsAnswer() {
        val lines = listOf(
            """{"type":"system","subtype":"init","session_id":"$id","tools":["Bash","Read","WebFetch","WebSearch"]}""",
            """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01","name":"Bash","input":{}}},"session_id":"$id"}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"date"}}]},"session_id":"$id"}""",
            "not json at all",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01","type":"tool_result","content":"Tue Oct 13 15:21:08 PDT 2026"}]}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It's Tuesday,"}},"session_id":"$id"}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" October 13, 2026."}},"session_id":"$id"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"It's Tuesday, October 13, 2026."}]},"session_id":"$id"}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"It's Tuesday, October 13, 2026.","session_id":"$id"}""",
        )
        val events = lines.mapNotNull(::quickEvent)
        val shown = events.runningFold(QuickAnswer()) { answer, event -> answer.after(event) }.map { it.text }
        assertEquals(listOf("", "", "", "It's Tuesday,", "It's Tuesday, October 13, 2026.", "It's Tuesday, October 13, 2026.\n", "It's Tuesday, October 13, 2026."), shown)
        assertEquals(listOf("Bash: date"), events.filterIsInstance<QuickEvent.Message>().flatMap { it.steps })
        assertEquals(id, events.filterIsInstance<QuickEvent.Result>().single().session)
    }
}
