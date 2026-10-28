package dev.omalauncher.runner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRunnerTest {
    private class Sessions : PhoneRunnerSessions {
        var messages = 0; var stopped = ""; var answered = false
        val nonce = "ticket-1"
        override fun list() = listOf("builder-a", "builder-b", "builder-c", "phone-d", "chat-local").map {
            JSONObject().put("id", it).put("name", it).put("home", "phone").put("kind", "claude").put("status", "idle")
        }
        override fun events(id: String, after: Long) = listOf(JSONObject().put("id", 1).put("type", "message").put("text", "hello")).filter { it.getLong("id") > after }
        override fun message(id: String, text: String): JSONObject { messages++; return JSONObject().put("taken", "now") }
        override fun answer(id: String, approval: String, nonce: String, decision: String, answers: JSONObject?, device: String): JSONObject {
            if (answered) throw RunnerFault(404, "already answered")
            if (nonce != this.nonce) throw RunnerFault(403, "wrong nonce")
            answered = true
            return JSONObject()
        }
        override fun stop(id: String) { stopped = id }
    }
    @Test fun authenticationRequiresBothThisDevicesKeyAndOwnersWhois() {
        val keys = listOf(RunnerKey("mac-pro", runnerDigest("secret")))
        assertFalse(runnerAuthorized(null, RunnerPeer("mac-pro", true), keys))
        assertFalse(runnerAuthorized("bad", RunnerPeer("mac-pro", true), keys))
        assertFalse(runnerAuthorized("secret", RunnerPeer("other-mac", true), keys))
        assertFalse(runnerAuthorized("secret", RunnerPeer("mac-pro", false), keys))
        assertFalse(runnerAuthorized("secret", null, keys))
        assertTrue(runnerAuthorized("secret", RunnerPeer("mac-pro", true), keys))
        assertFalse(runnerAuthorized("secret", RunnerPeer("mac-pro", true), emptyList()))
    }
    @Test fun unauthorizedHealthAndMutationsDiscloseNothingAndRunNothing() {
        val sessions = Sessions(); val protocol = PhoneRunnerProtocol(sessions)
        for (path in listOf("/health", "/threads", "/threads/builder-a/messages", "/threads/builder-a/stop")) {
            assertEquals(401, protocol.handle("POST", path, "{}", false).code)
        }
        assertEquals(0, sessions.messages); assertEquals("", sessions.stopped); assertFalse(sessions.answered)
    }
    @Test fun phoneDIsReadableAndStoppableButCannotReceiveWork() {
        val sessions = Sessions(); val protocol = PhoneRunnerProtocol(sessions)
        assertEquals(200, protocol.handle("GET", "/threads/phone-d", "", true).code)
        assertEquals(403, protocol.handle("POST", "/threads/phone-d/messages", "{\"text\":\"work\"}", true).code)
        assertEquals(0, sessions.messages)
        assertEquals(200, protocol.handle("POST", "/threads/phone-d/stop", "{}", true).code)
        assertEquals("phone-d", sessions.stopped)
    }
    @Test fun wrongNonceAndReuseCannotAnswerAndBadFormsDoNotConsume() {
        val sessions = Sessions(); val protocol = PhoneRunnerProtocol(sessions)
        val path = "/threads/builder-a/approvals/request"
        assertEquals(400, protocol.handle("POST", path, "{\"decision\":\"allow\",\"answers\":[]}", true).code)
        assertEquals(403, protocol.handle("POST", path, "{\"decision\":\"allow\",\"nonce\":\"wrong\"}", true).code)
        assertFalse(sessions.answered)
        val body = "{\"decision\":\"allow\",\"nonce\":\"ticket-1\"}"
        assertEquals(200, protocol.handle("POST", path, body, true).code)
        assertEquals(404, protocol.handle("POST", path, body, true).code)
    }
    @Test fun unsupportedRoutesAndCursorsNeverMutateSessions() {
        val sessions = Sessions(); val protocol = PhoneRunnerProtocol(sessions)
        for (path in listOf("/threads", "/files", "/projects", "/bots", "/threads/builder-a/model"))
            assertEquals(404, protocol.handle("POST", path, "{}", true).code)
        assertEquals(400, protocol.handle("GET", "/threads/builder-a/events?after=-1", "", true).code)
        assertEquals(0, protocol.handle("GET", "/threads/builder-a/events?after=1", "", true).body.getJSONArray("events").length())
        assertEquals(0, sessions.messages)
    }
}
