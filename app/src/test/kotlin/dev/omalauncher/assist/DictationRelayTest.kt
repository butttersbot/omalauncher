package dev.omalauncher.assist

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/** The relay against a fake receiver, as linux/tailtype-serve answers (Boss, 2026-10-30); nothing reaches a device. */
class DictationRelayTest {
    private var server: HttpServer? = null
    private val heard = mutableListOf<String>()

    private fun receiver(code: Int, answer: String, delayMs: Long = 0): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/type") { x ->
            heard += x.requestBody.readBytes().decodeToString()
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = answer.toByteArray()
            x.sendResponseHeaders(code, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @After fun stop() { server?.stop(0) }

    @Test fun typedSaysWhereInTheQuietWords() {
        val outcome = relayText(receiver(200, """{"typed":12,"app":"Brave"}"""), "hello “MG1” é")
        assertEquals(RelayOutcome.Typed("Brave"), outcome)
        assertEquals("Typed into Brave on MG1", relayWords(outcome, "MG1"))
        assertEquals("hello “MG1” é", JSONObject(heard.single()).getString("text"))
    }

    @Test fun aRefusalIsNamedAndNotTyped() {
        assertEquals(RelayOutcome.Locked, relayText(receiver(409, """{"error":"locked"}"""), "x"))
        assertEquals(RelayOutcome.NoWindow, relayText(receiver(409, """{"error":"no window"}"""), "x"))
        assertEquals(RelayOutcome.TooLong, relayText(receiver(400, """{"error":"too long"}"""), "x"))
        assertEquals(RelayOutcome.NoText, relayText(receiver(400, """{"error":"no text"}"""), "x"))
        assertEquals(RelayOutcome.Refused(403), relayText(receiver(403, """{"error":"not allowed"}"""), "x"))
        assertEquals(RelayOutcome.Refused(500), relayText(receiver(500, "not json"), "x"))
    }

    @Test fun nothingListeningIsOffline() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(RelayOutcome.Offline, relayText("http://127.0.0.1:$free", "x"))
    }

    @Test fun silenceAfterSendingIsNotCalledNotTyped() {
        val outcome = relayText(receiver(200, "{}", delayMs = 2_000), "x", readTimeout = 200)
        assertEquals(RelayOutcome.NoAnswer, outcome)
        assertTrue(relayWords(outcome, "MG1").contains("may have typed"))
    }

    @Test fun tooLongOrBlankNeverLeavesThePhone() {
        val base = receiver(200, "{}")
        assertEquals(RelayOutcome.TooLong, relayText(base, "x".repeat(RELAY_MAX_CHARS + 1)))
        assertEquals(RelayOutcome.NoText, relayText(base, "  \n"))
        assertTrue(heard.isEmpty())
        assertTrue(relayText(base, "x".repeat(RELAY_MAX_CHARS)) is RelayOutcome.Typed)
    }

    @Test fun theAgentIsFirstAndTheDefault() {
        assertEquals(RelayTargets.Agent, RelayTargets.all.first())
        assertEquals(RelayTargets.Agent, RelayTargets.named(null))
        assertEquals(RelayTargets.Agent, RelayTargets.named("gone"))
        assertEquals(RelayTargets.Mg1, RelayTargets.named("mg1"))
    }
}
