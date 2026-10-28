package dev.omalauncher.runner

import dev.omalauncher.assist.*
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRunnerContractTest {
    private class Sessions : PhoneRunnerSessions {
        override fun list() = listOf(JSONObject().put("id", "builder-a").put("status", "working").put("last_event", 2))
        override fun first(id: String) = 1L
        override fun events(id: String, after: Long) = listOf(
            JSONObject().put("id", 1).put("time", 1000).put("type", "message").put("text", "hello"),
            JSONObject().put("id", 2).put("time", 1001).put("type", "text").put("text", "world"),
        ).filter { it.getLong("id") > after }
        override fun message(id: String, text: String) = JSONObject().put("taken", "now")
        override fun answer(id: String, approval: String, nonce: String, decision: String, answers: JSONObject?, device: String) = JSONObject()
        override fun stop(id: String) {}
    }
    @Test fun actualProtocolRepliesAreAcceptedByTheDesktopDecisionAndStopParsers() {
        val protocol = PhoneRunnerProtocol(Sessions())
        for (decision in listOf("allow", "deny")) {
            val reply = protocol.handle("POST", "/threads/builder-a/approvals/ticket", JSONObject().put("decision", decision).put("nonce", "fixture").toString(), true)
            assertEquals(MacAnswer.Ok(if (decision == "allow") MacDecision.Allow else MacDecision.Deny), parseMacDecision(MacReply(reply.code, reply.body.toString())))
        }
        val stop = protocol.handle("POST", "/threads/builder-a/stop", "{}", true)
        assertEquals(MacAnswer.Ok(true), parseMacStopped(MacReply(stop.code, stop.body.toString())))
        assertEquals("builder-a", protocol.handle("GET", "/threads/builder-a", "", true).body.getJSONObject("thread").getString("id"))
    }
    @Test fun productionStreamWriterFeedsTheDesktopSseReaderAndResumesAfterCursor() {
        val protocol = PhoneRunnerProtocol(Sessions())
        val page = protocol.handle("GET", "/threads/builder-a/stream?after=1", "", true)
        assertEquals(200, page.code)
        val bytes = ByteArrayOutputStream()
        writeRunnerStreamHeaders(bytes)
        assertEquals(2L, writeRunnerStreamPage(bytes, page.body.getJSONArray("events"), 1))
        assertEquals(2L, writeRunnerStreamPage(bytes, JSONArray(), 2))
        val wire = bytes.toString("UTF-8")
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"))
        val lines = wire.substringAfter("\r\n\r\n").reader().buffered()
        val received = mutableListOf<MacEvent>()
        val end = followMacStream(1, { lines.readLine() }, { received.size == 1 }, { received += it })
        assertEquals(2L, end.last)
        assertEquals(1, received.size)
        assertTrue(wire.contains("event: text\ndata: "))
        assertTrue(wire.contains(": keep-alive\n\n"))
        assertEquals(401, protocol.handle("GET", "/threads/builder-a/stream?after=1", "", false).code)
        assertEquals(400, protocol.handle("GET", "/threads/builder-a/stream?after=-1", "", true).code)
    }
}
