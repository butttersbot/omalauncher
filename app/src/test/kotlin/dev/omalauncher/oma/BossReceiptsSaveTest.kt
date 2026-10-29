package dev.omalauncher.oma

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BossReceiptsSaveTest {
    private fun signed(kind: String, nonce: String) = JSONObject().put("sig", "c2ln").put("receipt", CanvasReceipt.canonical(
        JSONObject().put("v", 1).put("kind", kind).put("deviceId", "ab").put("nonce", nonce).put("messageId", nonce)
            .put("at", 5L).put("conversation", "soma").put("text", "approve all")))

    @Test fun eachReceiptIsSavedByItsNonceAndAsTheLastOfItsKind() {
        val dir = Files.createTempDirectory("boss").toFile()
        BossReceipts.save(dir, signed("chat-message", "n-1"))
        BossReceipts.save(dir, signed("question-answer", "../n 2"))
        val chat = JSONObject(File(dir, "last-chat.json").readText())
        assertEquals(signed("chat-message", "n-1").getString("receipt"), chat.getString("receipt"))
        assertEquals("c2ln", chat.getString("sig")); assertEquals("chat-message", chat.getString("kind"))
        assertTrue(File(dir, "n-1.json").isFile)
        assertTrue("a nonce is a file name, never a path", File(dir, "n2.json").isFile)
        assertEquals("question-answer", JSONObject(File(dir, "last.json").readText()).getString("kind"))
        assertTrue(File(dir, "last-answer.json").isFile)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}
