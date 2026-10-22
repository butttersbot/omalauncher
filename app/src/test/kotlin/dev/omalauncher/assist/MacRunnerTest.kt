package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections

/**
 * The phone's side of oma-runner (docs/MAC-RUNNER-SPEC.md, "As built on the Mac"). The JSON here is in the shape
 * tools/mac-runner/oma-runner writes and test-runner.py checks. Nothing in this file reaches the Mac: the few
 * tests of the transport talk to a server on this machine's own loopback.
 */
class MacRunnerTest {
    private val key = "k".repeat(40)
    private val thread = """{"id": "t1a2b3c4d", "name": "Scratch", "project": "/Users/boss/Projects/scratch", "home": "mac", "kind": "claude",
        "summary": "what files are here", "status": "waiting", "last_worked": 1760000000, "tail": "Files here: notes.md", "waiting_turns": 1,
        "last_event": 6, "pending": [{"approval": "a0123456789ab", "nonce": "n-secret", "tool": "Write",
        "input": {"file_path": "/Users/boss/Projects/scratch/written.txt", "content": "hi"}, "explanation": ""}]}"""
    private val card = MacApproval("a0123456789ab", "n-secret", "Write", "", "")

    private fun ok(body: String) = MacReply(200, body)

    private fun <T> value(answer: MacAnswer<T>): T = (answer as MacAnswer.Ok<T>).value

    // Config

    @Test fun theConfigIsReadFromItsJson() {
        assertEquals(MacRunnerConfig("http://100.120.219.46:8478", key), parseMacRunnerConfig("""{"url":"http://100.120.219.46:8478","key":"$key"}"""))
        assertEquals(MacRunnerConfig("http://100.120.219.46:8478", key), parseMacRunnerConfig("""{"url":" http://100.120.219.46:8478/ ","key":" $key\n"}"""))
        assertEquals("https://mac.example:8478", parseMacRunnerConfig("""{"url":"HTTPS://mac.example:8478","key":"$key"}""")?.url)
    }

    @Test fun aMissingBlankOrBrokenFileMeansNoMacNotAnError() {
        val bad = listOf(
            null, "", "  \n", "not json", "[]", "{}", "\"text\"",
            """{"url":"http://100.120.219.46:8478"}""",
            """{"url":"http://100.120.219.46:8478","key":""}""",
            """{"url":"http://100.120.219.46:8478","key":"   "}""",
            """{"url":"http://100.120.219.46:8478","key":null}""",
            """{"key":"$key"}""",
            """{"url":"","key":"$key"}""",
            """{"url":"100.120.219.46:8478","key":"$key"}""",
            """{"url":"ftp://100.120.219.46:8478","key":"$key"}""",
            """{"url":"http://boss:pw@100.120.219.46:8478","key":"$key"}""",
            """{"url":"http://100.120.219.46:8478/elsewhere","key":"$key"}""",
            """{"url":"http://100.120.219.46:8478?x=1","key":"$key"}""",
            // A key that would break out of its header.
            """{"url":"http://100.120.219.46:8478","key":"abc\r\nX-Other: 1"}""",
            """{"url":"http://100.120.219.46:8478","key":"two words"}""",
        )
        for (text in bad) assertNull(text, parseMacRunnerConfig(text))
    }

    @Test fun theConfigFileIsLookedForInTheGuestsLauncherFolder() {
        val dir = Files.createTempDirectory("oma-mac-runner-test").toFile()
        try {
            assertNull(readMacRunnerConfig(dir))
            assertNull(readMacRunnerConfig(File(dir, "missing")))
            File(dir, MAC_RUNNER_CONFIG).writeText("""{"url":"http://100.120.219.46:8478","key":"$key"}""")
            assertEquals("mac-runner.json", MAC_RUNNER_CONFIG)
            assertEquals(MacRunnerConfig("http://100.120.219.46:8478", key), readMacRunnerConfig(dir))
            File(dir, MAC_RUNNER_CONFIG).writeText("{")
            assertNull(readMacRunnerConfig(dir))
        } finally { dir.deleteRecursively() }
    }

    /** Pinned: printing the config, or anything that holds it, never prints the key. */
    @Test fun theKeyNeverLeavesThroughToString() {
        val config = MacRunnerConfig("http://100.120.219.46:8478", key)
        for (printed in listOf(config.toString(), "$config", listOf(config).toString(), mapOf("mac" to config).toString(), IllegalStateException("failed for $config").toString())) {
            assertFalse(printed, printed.contains(key))
            assertTrue(printed, printed.contains("100.120.219.46"))
        }
        assertNotEquals(config, MacRunnerConfig(config.url, "another"))
        assertEquals(config.hashCode(), MacRunnerConfig(config.url, key).hashCode())
        // An approval's nonce is what makes an answer count, so it stays out too.
        assertFalse(MacApproval("a1", "n-secret", "Write", """{"content":"private"}""", "why").toString().contains("secret"))
        assertFalse(MacApproval("a1", "n-secret", "Write", """{"content":"private"}""", "why").toString().contains("private"))
    }

    // Requests

    @Test fun everyCallIsItsMethodPathAndBody() {
        assertEquals(MacRequest("GET", "/health"), MacRequests.health())
        assertEquals(MacRequest("GET", "/threads"), MacRequests.threads())
        assertEquals(MacRequest("GET", "/threads/t1a2b3c4d"), MacRequests.thread("t1a2b3c4d"))
        assertEquals(MacRequest("GET", "/threads/t1/events?after=7"), MacRequests.events("t1", 7))
        assertEquals(MacRequest("GET", "/threads/t1/stream?after=7"), MacRequests.stream("t1", 7))
        assertEquals(MacRequest("GET", "/threads/t1/stream?after=0"), MacRequests.stream("t1", -3))
        assertEquals(MacRequest("POST", "/threads/t1/stop", "{}"), MacRequests.stop("t1"))

        val make = MacRequests.makeThread("FF4 mod", "~/ClaudeSync/projects/ffiv-ds-better-menu-mod")
        assertEquals("POST", make.method); assertEquals("/threads", make.path)
        assertEquals(setOf("name", "project"), JSONObject(make.body!!).keys().asSequence().toSet())
        // The agent the thread runs goes with it when one is named.
        val codex = MacRequests.makeThread("FF4 mod · Codex", "~/p/ff4", "codex")
        assertEquals("codex", JSONObject(codex.body!!).getString("kind")); assertEquals(setOf("name", "project", "kind"), JSONObject(codex.body!!).keys().asSequence().toSet())
        assertEquals("FF4 mod", JSONObject(make.body!!).getString("name"))
        assertEquals("~/ClaudeSync/projects/ffiv-ds-better-menu-mod", JSONObject(make.body!!).getString("project"))

        for ((allow, word) in listOf(true to "allow", false to "deny")) {
            val answer = MacRequests.answerApproval("t1", "a0123456789ab", allow, "n-secret")
            assertEquals("POST", answer.method); assertEquals("/threads/t1/approvals/a0123456789ab", answer.path)
            assertEquals(setOf("decision", "nonce"), JSONObject(answer.body!!).keys().asSequence().toSet())
            assertEquals(word, JSONObject(answer.body!!).getString("decision"))
            assertEquals("n-secret", JSONObject(answer.body!!).getString("nonce"))
        }
    }

    @Test fun theOwnersWordsAreSentExactlyAsSaid() {
        val words = "  fix the \"menu\"\n\tand don't touch /saves — ok? \\o/ "
        val send = MacRequests.sendMessage("t1", words)
        assertEquals("POST", send.method); assertEquals("/threads/t1/messages", send.path)
        assertEquals(setOf("text"), JSONObject(send.body!!).keys().asSequence().toSet())
        assertEquals(words, JSONObject(send.body!!).getString("text"))
    }

    @Test fun anIdCannotChangeThePathItIsPutIn() {
        assertEquals("/threads/..%2F..%2Fhealth", MacRequests.thread("../../health").path)
        assertEquals("/threads/t1/approvals/a%2Fstop%3Fx%3D1", MacRequests.answerApproval("t1", "a/stop?x=1", true, "n").path)
        assertEquals("/threads/a%20b/stop", MacRequests.stop("a b").path)
    }

    // Answers

    @Test fun healthIsRead() {
        assertEquals(MacAnswer.Ok(MacHealth(3, 1, 2)), parseMacHealth(ok("""{"ok": true, "runner": "oma-runner", "threads": 3, "working": 1, "waiting": 2}""")))
        assertEquals(MacAnswer.Ok(MacHealth(0, 0, 0)), parseMacHealth(ok("""{"ok": true, "runner": "oma-runner"}""")))
        assertEquals(MacAnswer.Ok(MacHealth(1, 0, 0, listOf("claude", "codex", "cursor"))), parseMacHealth(ok("""{"ok": true, "runner": "oma-runner", "threads": 1, "kinds": ["claude", "codex", "cursor"]}""")))
        assertEquals(MacAnswer.Ok(MacHealth(0, 0, 0, listOf("claude"))), parseMacHealth(ok("""{"ok": true, "runner": "oma-runner", "kinds": ["claude", "", null, 3]}""")))
        // Something else on that port is not the runner.
        for (other in listOf("""{"ok": true}""", """{"ok": true, "runner": "other"}""", """{"ok": false, "runner": "oma-runner"}""", "<html>hello</html>", ""))
            assertEquals(other, MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacHealth(ok(other)))
    }

    @Test fun healthComesToOneOfFourResults() {
        assertEquals(MacHealthResult.Up(MacHealth(0, 0, 0)), macHealthResult(ok("""{"ok": true, "runner": "oma-runner", "threads": 0, "working": 0, "waiting": 0}""")))
        assertEquals(MacHealthResult.Refused(401), macHealthResult(MacReply(401, """{"ok": false, "error": "no"}""")))
        assertEquals(MacHealthResult.Unreachable, macHealthResult(null))
        assertEquals(MacHealthResult.Unreachable, macHealthResult(ok("<html>a login page</html>")))
        assertEquals(MacHealthResult.Unreachable, macHealthResult(MacReply(502, "")))
        assertEquals(MacHealthResult.NotConfigured, MacRunnerClient(null).health())
    }

    @Test fun aThreadIsReadWithAllItsFields() {
        val read = value(parseMacThread(ok("""{"ok": true, "thread": $thread}""")))
        assertEquals("t1a2b3c4d", read.id); assertEquals("Scratch", read.name); assertEquals("/Users/boss/Projects/scratch", read.project)
        assertEquals(ThreadHome.Mac, read.home); assertEquals("claude", read.kind); assertEquals("what files are here", read.summary)
        assertEquals(MacThreadStatus.Waiting, read.status); assertEquals(1760000000L, read.lastWorked); assertEquals("Files here: notes.md", read.tail)
        assertEquals(1, read.waitingTurns); assertEquals(6L, read.lastEvent)
        val pending = read.pending.single()
        assertEquals(card.copy(input = pending.input), pending)
        assertEquals("/Users/boss/Projects/scratch/written.txt", JSONObject(pending.input).getString("file_path"))
        assertEquals("hi", JSONObject(pending.input).getString("content"))
    }

    @Test fun aThreadWithPartsMissingIsStillAThread() {
        // As the runner answers for a thread just made: nothing worked yet, nothing waiting.
        val fresh = value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "name": "FF4 mod", "project": "/p", "home": "mac", "kind": "claude",
            "summary": "", "status": "idle", "last_worked": null, "tail": "", "waiting_turns": 0, "last_event": 0, "pending": []}}""")))
        assertEquals(MacThread("t9", "FF4 mod", "/p", ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList()), fresh)
        assertEquals(MacThread("t9", "", "", ThreadHome.Mac, "", "", MacThreadStatus.Unknown, null, "", 0, 0, emptyList()), value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9"}}"""))))
        assertEquals(MacThreadStatus.Unknown, value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "status": "napping", "summary": null, "pending": "none"}}"""))).status)
        // The work the agent left running after its answer, as /threads and /threads/ID carry it; nothing from a runner that does not say.
        assertEquals(listOf("copy the games", "npm run dev"), value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "background": ["copy the games", " ", 7, null, " npm run dev "]}}"""))).background)
        assertEquals(emptyList<String>(), value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "background": []}}"""))).background)
        assertEquals(emptyList<String>(), value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "background": "copying"}}"""))).background)
        assertEquals(listOf("copy the games"), (parseMacThreads(ok("""{"ok": true, "threads": [{"id": "t9", "status": "idle", "background": ["copy the games"]}]}""")) as MacAnswer.Ok).value.single().background)
        assertEquals("", value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "summary": null}}"""))).summary)
        // A pending request that could not be answered is not drawn as a card.
        assertEquals(emptyList<MacApproval>(), value(parseMacThread(ok("""{"ok": true, "thread": {"id": "t9", "pending": [{"approval": "a1", "tool": "Write"}, 7]}}"""))).pending)
        for (none in listOf("""{"ok": true}""", """{"ok": true, "thread": {}}""", """{"ok": true, "thread": []}""", """{"ok": false, "thread": $thread}"""))
            assertEquals(none, MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacThread(ok(none)))
    }

    @Test fun theListOfThreadsIsRead() {
        assertEquals(emptyList<MacThread>(), value(parseMacThreads(ok("""{"ok": true, "threads": []}"""))))
        val listed = value(parseMacThreads(ok("""{"ok": true, "threads": [$thread, {"name": "no id"}, "stray", {"id": "t2", "status": "interrupted"}]}""")))
        assertEquals(listOf("t1a2b3c4d", "t2"), listed.map { it.id })
        assertEquals(listOf(MacThreadStatus.Waiting, MacThreadStatus.Interrupted), listed.map { it.status })
        assertEquals(MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacThreads(ok("""{"ok": true}""")))
    }

    @Test fun theAnswerSaysWhetherTheWordsWereTakenNowOrWaitTheirTurn() {
        assertEquals(MacAnswer.Ok(MacTaken.Now), parseMacTaken(ok("""{"ok": true, "taken": "now"}""")))
        assertEquals(MacAnswer.Ok(MacTaken.Queued(1)), parseMacTaken(ok("""{"ok": true, "taken": "queued", "position": 1}""")))
        assertEquals(MacAnswer.Ok(MacTaken.Queued(0)), parseMacTaken(ok("""{"ok": true, "taken": "queued"}""")))
        assertEquals(MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacTaken(ok("""{"ok": true, "taken": "later"}""")))
        assertEquals(MacAnswer.Failed(400, "a JSON body with \"text\" is needed"), parseMacTaken(MacReply(400, """{"ok": false, "error": "a JSON body with \"text\" is needed"}""")))
    }

    @Test fun refusalsAreReadTheSameForEveryCall() {
        val no = MacReply(401, """{"ok": false, "error": "no"}""")
        assertEquals(MacAnswer.Unauthorized, parseMacHealth(no)); assertEquals(MacAnswer.Unauthorized, parseMacThreads(no))
        assertEquals(MacAnswer.Unauthorized, parseMacTaken(no)); assertEquals(MacAnswer.Unauthorized, parseMacDecision(no))

        val outside = MacReply(403, """{"ok": false, "error": "the project must be an existing folder under an allowed root", "roots": ["/Users/boss/Projects", "/Users/boss/ClaudeSync/projects"]}""")
        assertEquals(MacAnswer.Refused("the project must be an existing folder under an allowed root", listOf("/Users/boss/Projects", "/Users/boss/ClaudeSync/projects")), parseMacThread(outside))
        assertEquals(MacAnswer.Refused("wrong nonce", emptyList()), parseMacDecision(MacReply(403, """{"ok": false, "error": "wrong nonce"}""")))

        assertEquals(MacAnswer.NotFound, parseMacThread(MacReply(404, """{"ok": false, "error": "not found"}""")))
        assertEquals(MacAnswer.NotFound, parseMacDecision(MacReply(404, """{"ok": false, "error": "no such request is waiting"}""")))
        assertEquals(MacAnswer.Failed(409, "the task is no longer running"), parseMacDecision(MacReply(409, """{"ok": false, "error": "the task is no longer running"}""")))
        assertEquals(MacAnswer.Failed(400, "a name is needed"), parseMacThread(MacReply(400, """{"ok": false, "error": "a name is needed"}""")))
        assertEquals(MacAnswer.Failed(500, ""), parseMacThreads(MacReply(500, "Internal Server Error")))
        assertEquals(MacAnswer.Refused("", emptyList()), parseMacThread(MacReply(403, "")))
        assertEquals(MacAnswer.Unreachable, parseMacThreads(null)); assertEquals(MacAnswer.Unreachable, parseMacStopped(null))
    }

    @Test fun approvalAndStopAnswersAreRead() {
        assertEquals(MacAnswer.Ok(MacDecision.Allow), parseMacDecision(ok("""{"ok": true, "decision": "allow"}""")))
        assertEquals(MacAnswer.Ok(MacDecision.Deny), parseMacDecision(ok("""{"ok": true, "decision": "deny"}""")))
        assertEquals(MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacDecision(ok("""{"ok": true}""")))
        assertEquals(MacAnswer.Ok(true), parseMacStopped(ok("""{"ok": true, "stopped": true}""")))
        assertEquals(MacAnswer.Ok(false), parseMacStopped(ok("""{"ok": true, "stopped": false}""")))
        assertEquals(MacAnswer.Failed(200, "not an answer from oma-runner"), parseMacStopped(ok("""{"ok": true}""")))
    }

    // Events

    @Test fun everyEventTypeIsRead() {
        fun read(rest: String) = parseMacEvent("""{"id": 4, "time": 1760000000, $rest}""")
        assertEquals(MacEvent.Status(4, 1760000000, MacThreadStatus.Working), read(""""type": "status", "status": "working""""))
        assertEquals(MacEvent.Status(4, 1760000000, MacThreadStatus.Interrupted), read(""""type": "status", "status": "interrupted""""))
        assertEquals(MacEvent.Message(4, 1760000000, "what files are here"), read(""""type": "message", "text": "what files are here""""))
        assertEquals(MacEvent.Text(4, 1760000000, "Files here: notes.md"), read(""""type": "text", "text": "Files here: notes.md""""))
        assertEquals(MacEvent.Step(4, 1760000000, "Write: /p/written.txt", "Write"), read(""""type": "step", "text": "Write: /p/written.txt", "tool": "Write""""))
        assertEquals(MacEvent.Step(4, 1760000000, "a tool", ""), read(""""type": "step", "text": "a tool", "tool": null"""))
        assertEquals(MacEvent.ApprovalResult(4, 1760000000, "a0123456789ab", MacDecision.Allow), read(""""type": "approval_result", "approval": "a0123456789ab", "decision": "allow""""))
        assertEquals(MacEvent.ApprovalResult(4, 1760000000, "a0123456789ab", MacDecision.Deny), read(""""type": "approval_result", "approval": "a0123456789ab", "decision": "deny""""))
        assertEquals(MacEvent.ApprovalResult(4, 1760000000, "a0123456789ab", MacDecision.Withdrawn), read(""""type": "approval_result", "approval": "a0123456789ab", "decision": "withdrawn""""))
        assertEquals(MacEvent.ApprovalResult(4, 1760000000, "a0123456789ab", MacDecision.Unknown), read(""""type": "approval_result", "approval": "a0123456789ab", "decision": "shrugged""""))
        assertEquals(MacEvent.Result(4, 1760000000, "done", false), read(""""type": "result", "text": "done", "failed": false"""))
        assertEquals(MacEvent.Result(4, 1760000000, "OAuth session expired", true), read(""""type": "result", "text": "OAuth session expired", "failed": true"""))
        assertEquals(MacEvent.Stopped(4, 1760000000, 1), read(""""type": "stopped", "dropped": 1"""))
        // What the agent did that is not his answer: background work running, ended or stopped, or a turn of its own.
        assertEquals(MacEvent.Note(4, 1760000000, "Still running on the Mac: copy the games"), read(""""type": "note", "text": " Still running on the Mac: copy the games """"))
        assertEquals(MacEvent.Other(4, 1760000000, "note"), read(""""type": "note", "text": "  """"))
        assertEquals(MacEvent.Other(4, 1760000000, "note"), read(""""type": "note""""))
        assertEquals(MacEvent.Error(4, 1760000000, "The agent ended without an answer.", 1), read(""""type": "error", "code": 1, "text": "The agent ended without an answer.""""))
        assertEquals(MacEvent.Error(4, 1760000000, "The agent could not be started on the Mac: no such file", null), read(""""type": "error", "text": "The agent could not be started on the Mac: no such file""""))
        // A word in place of an exit code says why the turn ended: "limit" is an agent at its usage or concurrency limit.
        assertEquals(MacEvent.Error(4, 1760000000, "You've hit your limit", null, "limit"), read(""""type": "error", "code": "limit", "text": "You've hit your limit""""))
        assertEquals(MacEvent.Error(4, 1760000000, "refused", null, "agent"), read(""""type": "error", "code": "agent", "text": "refused""""))
        // The first words of a turn, and its result, say which agent and model answered.
        assertEquals(MacEvent.Text(4, 1760000000, "Hi", "codex", "gpt-6.1-sol"), read(""""type": "text", "text": "Hi", "kind": "codex", "model": "gpt-6.1-sol""""))
        assertEquals(MacEvent.Result(4, 1760000000, "done", false, "cursor", "cursor-auto"), read(""""type": "result", "text": "done", "failed": false, "kind": "cursor", "model": "cursor-auto""""))
    }

    @Test fun anApprovalEventCarriesWhatTheCardAndTheAnswerNeed() {
        val event = parseMacEvent("""{"id": 5, "time": 1760000000, "type": "approval", "approval": "a0123456789ab", "nonce": "n-secret", "tool": "Write",
            "input": {"file_path": "/p/written.txt", "content": "hi"}, "explanation": "wants to write a file"}""") as MacEvent.Approval
        assertEquals(5L, event.id)
        assertEquals("a0123456789ab", event.request.approval); assertEquals("n-secret", event.request.nonce); assertEquals("Write", event.request.tool)
        assertEquals("wants to write a file", event.request.explanation)
        assertEquals("/p/written.txt", JSONObject(event.request.input).getString("file_path"))
        // No input is an empty one, as the runner writes it.
        assertEquals("{}", (parseMacEvent("""{"id": 5, "type": "approval", "approval": "a1", "nonce": "n"}""") as MacEvent.Approval).request.input)
    }

    @Test fun whatIsNotAKnownEventIsIgnoredAndNeverFatal() {
        // An unknown type, or a request that could not be answered, keeps its place in the count and shows nothing.
        assertEquals(MacEvent.Other(7, 1760000000, "thinking"), parseMacEvent("""{"id": 7, "time": 1760000000, "type": "thinking", "text": "hm"}"""))
        assertEquals(MacEvent.Other(7, 0, ""), parseMacEvent("""{"id": 7}"""))
        assertEquals(MacEvent.Other(7, 0, "approval"), parseMacEvent("""{"id": 7, "type": "approval", "approval": "a1", "tool": "Write"}"""))
        for (bad in listOf("", "not json", "[1, 2]", "7", "{", """{"type": "text", "text": "no id"}""", """{"id": 0, "type": "text"}""", """{"id": "seven", "type": "text"}""", """{"id": null, "type": "text"}"""))
            assertNull(bad, parseMacEvent(bad))
        // Fields of the wrong kind read as empty.
        assertEquals(MacEvent.Text(7, 0, ""), parseMacEvent("""{"id": 7, "type": "text", "text": null}"""))
        assertEquals(MacEvent.Stopped(7, 0, 0), parseMacEvent("""{"id": 7, "type": "stopped", "dropped": "many"}"""))
        // The stream's own lines fill in what the JSON leaves out, and do not overrule it.
        assertEquals(MacEvent.Text(9, 0, "hi"), parseMacEvent("""{"text": "hi"}""", "9", "text"))
        assertEquals(MacEvent.Text(7, 0, "hi"), parseMacEvent("""{"id": 7, "type": "text", "text": "hi"}""", "9", "status"))
        assertNull(parseMacEvent("""{"text": "hi"}""", "nine", "text"))
    }

    @Test fun thePlainEventsListIsRead() {
        val page = value(parseMacEvents(ok("""{"ok": true, "status": "working", "events": [
            {"id": 3, "time": 1760000000, "type": "status", "status": "working"},
            {"id": 4, "time": 1760000000, "type": "message", "text": "slow one"},
            "stray", {"type": "text", "text": "no id"},
            {"id": 5, "time": 1760000001, "type": "text", "text": "Heard: slow one"}]}""")))
        assertEquals(MacThreadStatus.Working, page.status)
        assertEquals(listOf(MacEvent.Status(3, 1760000000, MacThreadStatus.Working), MacEvent.Message(4, 1760000000, "slow one"), MacEvent.Text(5, 1760000001, "Heard: slow one")), page.events)
        assertEquals(MacEventsPage(MacThreadStatus.Idle, emptyList()), value(parseMacEvents(ok("""{"ok": true, "status": "idle", "events": []}"""))))
        assertEquals(MacAnswer.NotFound, parseMacEvents(MacReply(404, """{"ok": false, "error": "not found"}""")))
    }

    // The stream

    /** One event as the runner writes it to the stream. */
    private fun wire(id: Int, type: String, rest: String = "") = "id: $id\nevent: $type\ndata: {\"id\": $id, \"time\": 1760000000, \"type\": \"$type\"$rest}\n\n"

    private fun text(id: Int) = wire(id, "text", ", \"text\": \"t$id\"")

    private val keepAlive = ": still here\n\n"

    /** A link that gives [stream]'s lines and then ends. */
    private fun link(stream: String): () -> String? {
        val left = stream.split("\n").dropLast(1).iterator()
        return { if (left.hasNext()) left.next() else null }
    }

    private fun follow(stream: String, after: Long, shouldStop: () -> Boolean = { false }): Pair<List<MacEvent>, MacStreamEnd> {
        val seen = ArrayList<MacEvent>()
        val end = followMacStream(after, link(stream), shouldStop) { seen.add(it) }
        return seen to end
    }

    @Test fun aBlankLineEndsAnEvent() {
        val reader = SseReader()
        assertNull(reader.feed("id: 1"))
        assertNull(reader.feed("event: status"))
        assertNull(reader.feed("""data: {"id": 1, "time": 1760000000, "type": "status", "status": "working"}"""))
        assertEquals(SseEvent("1", "status", """{"id": 1, "time": 1760000000, "type": "status", "status": "working"}"""), reader.feed(""))
        // Nothing of one event is carried into the next.
        assertNull(reader.feed("data: x"))
        assertEquals(SseEvent(null, null, "x"), reader.feed(""))
    }

    @Test fun keepAlivesAndBlankLinesSayNothing() {
        val reader = SseReader()
        for (line in listOf(": still here", "", "", ":", "", ": id: 9", "")) assertNull(line, reader.feed(line))
        // An id with no data is not an event, and is not kept for the next one.
        assertNull(reader.feed("id: 4")); assertNull(reader.feed(""))
        assertNull(reader.feed(": still here")); assertNull(reader.feed("data: after")); assertNull(reader.feed(": in the middle"))
        assertEquals(SseEvent(null, null, "after"), reader.feed(""))
    }

    @Test fun dataSplitOverSeveralLinesIsOneValue() {
        val reader = SseReader()
        for (line in listOf("id: 2", "event: text", """data: {"id": 2,""", """data:  "type": "text",""", """data:"text": "one\ntwo"}""")) assertNull(reader.feed(line))
        val event = reader.feed("")!!
        assertEquals("{\"id\": 2,\n \"type\": \"text\",\n\"text\": \"one\\ntwo\"}", event.data)
        assertEquals(MacEvent.Text(2, 0, "one\ntwo"), parseMacEvent(event.data, event.id, event.type))
    }

    @Test fun lineEndingsAndUnknownFieldsAreTolerated() {
        val reader = SseReader()
        for (line in listOf("id:3\r", "event:text\r", "retry: 1000\r", "nonsense\r", "data\r", "data: b\r")) assertNull(reader.feed(line))
        assertEquals(SseEvent("3", "text", "\nb"), reader.feed("\r"))
    }

    @Test fun aStreamIsFollowedInOrderPastItsKeepAlives() {
        val (seen, end) = follow(wire(1, "status", ", \"status\": \"working\"") + keepAlive + wire(2, "message", ", \"text\": \"slow one\"") + keepAlive + keepAlive + text(3), 0)
        assertEquals(listOf(MacEvent.Status(1, 1760000000, MacThreadStatus.Working), MacEvent.Message(2, 1760000000, "slow one"), MacEvent.Text(3, 1760000000, "t3")), seen)
        assertEquals(MacStreamEnd(3, MacStreamWhy.Dropped), end)
        assertEquals(MacStreamEnd(5, MacStreamWhy.Dropped), follow(keepAlive, 5).second)
    }

    @Test fun aDroppedStreamIsPickedUpFromTheLastEventWithNothingTwiceAndNothingMissing() {
        // The link goes in the middle of event 3: what had arrived of it is not an event.
        val (first, dropped) = follow(text(1) + text(2) + "id: 3\nevent: text\n", 0)
        assertEquals(MacStreamEnd(2, MacStreamWhy.Dropped), dropped)
        val (second, end) = follow(text(3) + text(4), dropped.last)
        assertEquals(listOf(1L, 2L, 3L, 4L), (first + second).map { it.id })
        assertEquals(MacStreamEnd(4, MacStreamWhy.Dropped), end)
        // Asked from too far back, what was already shown is dropped.
        val (again, _) = follow(text(1) + text(2) + text(3) + text(4) + text(5), 4)
        assertEquals(listOf(5L), again.map { it.id })
        assertEquals(emptyList<MacEvent>(), follow(text(1) + text(2), 4).first)
        assertEquals(MacStreamEnd(4, MacStreamWhy.Dropped), follow(text(1) + text(2), 4).second)
    }

    @Test fun aHoleInTheStreamShowsNothingPastItAndAsksForWhatIsMissing() {
        val (seen, end) = follow(text(2) + text(4) + text(5), 1)
        assertEquals(listOf(2L), seen.map { it.id })
        assertEquals(MacStreamEnd(2, MacStreamWhy.Gap), end)
        // An event that cannot be read leaves a hole behind it; one of an unknown type does not.
        val (around, broken) = follow(wire(1, "thinking") + "id: 2\nevent: text\ndata: {not json\n\n" + text(3), 0)
        assertEquals(listOf(MacEvent.Other(1, 1760000000, "thinking")), around)
        assertEquals(MacStreamEnd(1, MacStreamWhy.Gap), broken)
    }

    @Test fun followingStopsWhenToldToAndWhenTheLinkFails() {
        val seen = ArrayList<MacEvent>()
        val stopped = followMacStream(0, link(text(1) + text(2) + text(3)), { seen.size == 2 }) { seen.add(it) }
        assertEquals(MacStreamEnd(2, MacStreamWhy.Stopped), stopped); assertEquals(2, seen.size)
        assertEquals(MacStreamEnd(7, MacStreamWhy.Stopped), followMacStream(7, { fail("read after stop"); null }, { true }) { fail("event after stop") })

        val lines = link(text(1))
        var read = 0
        val failed = followMacStream(0, { if (read++ < 4) lines() else throw java.net.SocketTimeoutException("Read timed out") }, { false }) { seen.add(it) }
        assertEquals(MacStreamEnd(1, MacStreamWhy.Dropped), failed)
    }

    @Test fun theLastEventIsRememberedPerThread() {
        val cursors = MacCursors(mapOf("t1" to 4L))
        assertEquals(4L, cursors.after("t1")); assertEquals(0L, cursors.after("t2"))
        assertEquals(MacOffer.Repeat, cursors.offer("t1", MacEvent.Text(4, 0, "old")))
        assertEquals(MacOffer.Repeat, cursors.offer("t1", MacEvent.Text(1, 0, "older")))
        assertEquals(MacOffer.Show, cursors.offer("t1", MacEvent.Text(5, 0, "new")))
        assertEquals(MacOffer.Repeat, cursors.offer("t1", MacEvent.Text(5, 0, "new")))
        assertEquals(MacOffer.Show, cursors.offer("t2", MacEvent.Text(1, 0, "other thread")))
        assertEquals(MacOffer.Gap, cursors.offer("t1", MacEvent.Text(8, 0, "ahead")))
        assertEquals(5L, cursors.after("t1"))
        assertEquals(mapOf("t1" to 5L, "t2" to 1L), cursors.snapshot())
        assertEquals("/threads/t1/stream?after=5", MacRequests.stream("t1", cursors.after("t1")).path)

        assertEquals(MacOffer.Repeat, macOffer(3, 3)); assertEquals(MacOffer.Show, macOffer(3, 4)); assertEquals(MacOffer.Gap, macOffer(3, 5)); assertEquals(MacOffer.Show, macOffer(0, 1))
    }

    @Test fun aHoleIsFilledFromThePlainListOnce() {
        val cursors = MacCursors(mapOf("t1" to 5L))
        val page = listOf(MacEvent.Text(7, 0, "c"), MacEvent.Text(5, 0, "seen"), MacEvent.Text(6, 0, "b"), MacEvent.Text(7, 0, "c"), MacEvent.Text(8, 0, "d"))
        assertEquals(listOf(6L, 7L, 8L), cursors.catchUp("t1", page).map { it.id })
        assertEquals(8L, cursors.after("t1"))
        assertEquals(emptyList<MacEvent>(), cursors.catchUp("t1", page))
        // The runner's record itself has a hole: it is passed over, or the same question would be asked for ever.
        assertEquals(listOf(11L, 12L), cursors.catchUp("t1", listOf(MacEvent.Text(11, 0, "x"), MacEvent.Text(12, 0, "y"))).map { it.id })
        assertEquals(MacOffer.Show, cursors.offer("t1", MacEvent.Text(13, 0, "z")))
        assertEquals(emptyList<MacEvent>(), cursors.catchUp("t2", emptyList())); assertEquals(0L, cursors.after("t2"))
    }

    @Test fun aThreadWhoseRecordWasClearedStartsOverAndTheOthersStay() {
        val cursors = MacCursors(mapOf("t1" to 40L, "t2" to 7L))
        assertEquals(MacOffer.Repeat, cursors.offer("t1", MacEvent.Text(1, 0, "the new record's first")))
        cursors.forget("t1")
        assertEquals(0L, cursors.after("t1"))
        assertEquals(MacOffer.Show, cursors.offer("t1", MacEvent.Text(1, 0, "the new record's first")))
        assertEquals(mapOf("t1" to 1L, "t2" to 7L), cursors.snapshot())
        cursors.forget("never seen")
        assertEquals(mapOf("t1" to 1L, "t2" to 7L), cursors.snapshot())
    }

    // When the Mac is not there

    @Test fun theFallbackTable() {
        val up = MacHealthResult.Up(MacHealth(1, 0, 0))
        val away = listOf(MacHealthResult.Unreachable, MacHealthResult.NotConfigured)
        val refused = MacHealthResult.Refused(401)

        // A Mac thread, nothing started yet.
        for (clone in listOf(true, false)) assertEquals(MacRoute.RunOnMac, macRoute(ThreadHome.Mac, up, startedOnMac = false, phoneHasClone = clone))
        for (health in away) {
            assertEquals(MacRoute.RunOnPhone, macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = true))
            assertEquals(MacRoute.TellOwnerUnreachable, macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = false))
        }
        for (clone in listOf(true, false)) assertEquals(MacRoute.TellOwnerUnreachable, macRoute(ThreadHome.Mac, refused, startedOnMac = false, phoneHasClone = clone))

        // A phone thread (the launcher itself, anything that drives the phone) never leaves the phone.
        for (health in away + up + refused) for (clone in listOf(true, false))
            assertEquals(MacRoute.RunOnPhone, macRoute(ThreadHome.Phone, health, startedOnMac = false, phoneHasClone = clone))
    }

    /** Pinned: a task that started on the Mac is never started again anywhere, whatever else is true. */
    @Test fun aTaskStartedOnTheMacIsNeverStartedAgain() {
        for (home in ThreadHome.entries) for (clone in listOf(true, false)) {
            for (health in listOf(MacHealthResult.Unreachable, MacHealthResult.NotConfigured, MacHealthResult.Refused(401)))
                assertEquals("$home $health $clone", MacRoute.KeepWaitingForLink, macRoute(home, health, startedOnMac = true, phoneHasClone = clone))
            assertEquals(MacRoute.RunOnMac, macRoute(home, MacHealthResult.Up(MacHealth(1, 1, 0)), startedOnMac = true, phoneHasClone = clone))
        }
    }

    // The transport, against this machine's own loopback

    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) break
            head.append(next.toChar())
        }
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val count = input.read(body, got, length - got)
            if (count < 0) break
            got += count
        }
        return head.toString() + String(body, 0, got, Charsets.UTF_8)
    }

    private fun http(code: Int, body: String, type: String = "application/json") =
        "HTTP/1.1 $code Answer\r\nContent-Type: $type\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    /** Runs [test] against a server that gives each request, whole, to [answer] and closes when it returns. */
    private fun withServer(answer: (String, OutputStream) -> Unit, machine: String = MAC_FIRST, test: (MacRunnerClient, List<String>) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val asked = Collections.synchronizedList(ArrayList<String>())
        Thread {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        socket.use {
                            val request = readRequest(it.getInputStream())
                            asked.add(request)
                            answer(request, it.getOutputStream())
                            it.getOutputStream().flush()
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        try {
            test(MacRunnerClient(MacRunnerConfig("http://127.0.0.1:${server.localPort}", key), machine), asked)
        } finally { server.close() }
    }

    @Test fun everyRequestCarriesTheKeyAndItsBody() {
        val answers = { request: String, out: OutputStream ->
            val body = when {
                request.startsWith("GET /health ") -> """{"ok": true, "runner": "oma-runner", "threads": 1, "working": 0, "waiting": 0}"""
                request.startsWith("POST /threads/t1/messages ") -> """{"ok": true, "taken": "queued", "position": 2}"""
                request.startsWith("POST /threads/t1/stop ") -> """{"ok": true, "stopped": true}"""
                else -> """{"ok": true, "thread": $thread}"""
            }
            out.write(http(200, body).toByteArray())
        }
        withServer(answers) { client, asked ->
            assertEquals(MacHealthResult.Up(MacHealth(1, 0, 0)), client.health())
            assertEquals(MacAnswer.Ok(MacTaken.Queued(2)), client.sendMessage("t1", "write the file"))
            assertEquals(MacAnswer.Ok(true), client.stop("t1"))
            assertEquals("t1a2b3c4d", value(client.thread("t1a2b3c4d")).id)
            assertEquals(4, asked.size)
            for (request in asked) assertTrue(request, request.contains("\r\nAuthorization: Bearer $key\r\n"))
            assertTrue(asked[1], asked[1].startsWith("POST /threads/t1/messages HTTP/1.1\r\n"))
            assertEquals("write the file", JSONObject(asked[1].substringAfter("\r\n\r\n")).getString("text"))
            assertTrue(asked[3], asked[3].startsWith("GET /threads/t1a2b3c4d HTTP/1.1\r\n"))
        }
    }

    @Test fun aPictureGoesUpToTheRunnersFilesAndComesBackAsItsPath() {
        val picture = Files.createTempFile("shot", ".png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        withServer({ request, out ->
            val reply = if (request.startsWith("POST /files?name=")) http(200, """{"ok": true, "path": "/Users/boss/Downloads/From Fold/x.png", "bytes": 4}""") else http(500, "{}")
            out.write(reply.toByteArray())
        }) { client, asked ->
            assertEquals("/Users/boss/Downloads/From Fold/x.png", client.upload(picture))
            assertTrue(asked[0], asked[0].startsWith("POST /files?name=" + picture.name + " HTTP/1.1\r\n"))
            assertTrue(asked[0], asked[0].contains("\r\nAuthorization: Bearer $key\r\n"))
            assertTrue(asked[0], asked[0].contains("\r\nContent-Length: 4\r\n"))
            assertNull(client.upload(File(picture.parentFile, "gone.png")))
        }
        withServer({ _, out -> out.write(http(413, """{"ok": false}""").toByteArray()) }) { client, _ -> assertNull(client.upload(picture)) }
        picture.delete()
    }

    @Test fun theThreadIsToldWhereItsPicturesAre() {
        assertEquals("look", withPictures("look", emptyList()))
        assertEquals("look\n\nRead these attached images before answering:\n/a.png\n/b.png", withPictures("look", listOf("/a.png", "/b.png")))
    }

    @Test fun refusalsComeBackAsAnswersNotExceptions() {
        withServer({ request, out ->
            val reply = when {
                request.startsWith("POST /threads ") -> http(403, """{"ok": false, "error": "the project must be an existing folder under an allowed root", "roots": ["/Users/boss/Projects"]}""")
                request.startsWith("GET /threads/gone") -> http(404, """{"ok": false, "error": "not found"}""")
                else -> http(401, """{"ok": false, "error": "no"}""")
            }
            out.write(reply.toByteArray())
        }) { client, _ ->
            assertEquals(MacHealthResult.Refused(401), client.health())
            assertEquals(MacAnswer.Unauthorized, client.threads())
            assertEquals(MacAnswer.Refused("the project must be an existing folder under an allowed root", listOf("/Users/boss/Projects")), client.makeThread("x", "/etc"))
            assertEquals(MacAnswer.NotFound, client.thread("gone"))
            assertEquals(MacStreamEnd(3, MacStreamWhy.Refused, 404), client.stream("gone", 3, { false }) { fail("no event") })
        }
    }

    /**
     * Another Mac's runner (MacMachines.kt): the phone knows its threads as `macbook-pro/` and the runner's id, and
     * the runner is only ever asked about its own id. What it answers comes back under the phone's id again.
     */
    @Test fun anotherMacsRunnerIsAskedByItsOwnIdsAndAnsweredWithThePhones() {
        val answers = { request: String, out: OutputStream ->
            val body = when {
                request.startsWith("GET /threads ") -> """{"ok": true, "threads": [{"id": "t1", "name": "Herd", "project": "/p", "status": "idle"}]}"""
                request.startsWith("POST /threads/t1/messages ") -> """{"ok": true, "taken": "now"}"""
                request.startsWith("POST /threads/t1/stop ") -> """{"ok": true, "stopped": true}"""
                else -> """{"ok": true, "thread": {"id": "t1", "name": "Herd", "project": "/p", "status": "idle"}}"""
            }
            out.write(http(200, body).toByteArray())
        }
        withServer(answers, "macbook-pro") { client, asked ->
            assertEquals(listOf("macbook-pro/t1"), (client.threads() as MacAnswer.Ok).value.map { it.id })
            assertEquals("macbook-pro/t1", (client.thread("macbook-pro/t1") as MacAnswer.Ok).value.id)
            assertEquals("macbook-pro/t1", (client.makeThread("Herd", "/p") as MacAnswer.Ok).value.id)
            assertEquals(MacAnswer.Ok(MacTaken.Now), client.sendMessage("macbook-pro/t1", "hello"))
            assertEquals(MacAnswer.Ok(true), client.stop("macbook-pro/t1"))
            client.events("macbook-pro/t1", 4)
            client.stream("macbook-pro/t1", 4, { false }) { }
            val lines = asked.map { it.substringBefore("\r\n") }
            assertTrue(lines.toString(), lines.containsAll(listOf("GET /threads HTTP/1.1", "GET /threads/t1 HTTP/1.1", "POST /threads HTTP/1.1",
                "POST /threads/t1/messages HTTP/1.1", "POST /threads/t1/stop HTTP/1.1", "GET /threads/t1/events?after=4 HTTP/1.1", "GET /threads/t1/stream?after=4 HTTP/1.1")))
            assertTrue(lines.none { "macbook-pro" in it })
        }
        // The first Mac's threads keep the runner's own ids, as they always had.
        withServer(answers) { client, _ -> assertEquals(listOf("t1"), (client.threads() as MacAnswer.Ok).value.map { it.id }) }
        assertEquals(MacAnswer.Unreachable, macClient(null).threads())
    }

    @Test fun aMacThatIsNotThereIsUnreachableAndNothingThrows() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val client = MacRunnerClient(MacRunnerConfig("http://127.0.0.1:$closed", key))
        assertEquals(MacHealthResult.Unreachable, client.health())
        assertEquals(MacAnswer.Unreachable, client.threads())
        assertEquals(MacAnswer.Unreachable, client.sendMessage("t1", "hello"))
        assertEquals(MacStreamEnd(6, MacStreamWhy.Dropped), client.stream("t1", 6, { false }) { fail("no event") })
        assertEquals(MacAnswer.Unreachable, MacRunnerClient(null).threads())
        assertEquals(MacStreamEnd(6, MacStreamWhy.Dropped), MacRunnerClient(null).stream("t1", 6, { false }) { fail("no event") })
    }

    /** The spec's two seconds: a Mac that takes the connection and then says nothing does not hold the phone up. */
    @Test fun healthGivesUpAfterTwoSeconds() {
        withServer({ _, _ -> Thread.sleep(8_000) }) { client, _ ->
            val began = System.nanoTime()
            assertEquals(MacHealthResult.Unreachable, client.health())
            val took = (System.nanoTime() - began) / 1_000_000
            assertTrue("took $took ms", took in 1_500L..3_000L)
        }
        assertEquals(2_000, MacRunnerClient.HEALTH_LIMIT_MS)
    }

    @Test fun theStreamIsReadOffTheLinkUntilItDrops() {
        withServer({ _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write((text(2) + text(3) + keepAlive + text(4)).toByteArray())
        }) { client, asked ->
            val seen = ArrayList<MacEvent>()
            val end = client.stream("t1", 3, { false }) { seen.add(it) }
            assertEquals(listOf(MacEvent.Text(4, 1760000000, "t4")), seen)
            assertEquals(MacStreamEnd(4, MacStreamWhy.Dropped), end)
            assertTrue(asked[0], asked[0].startsWith("GET /threads/t1/stream?after=3 HTTP/1.1\r\n"))
            assertTrue(asked[0], asked[0].contains("\r\nAuthorization: Bearer $key\r\n"))
        }
    }
}
