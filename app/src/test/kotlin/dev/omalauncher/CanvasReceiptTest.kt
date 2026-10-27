package dev.omalauncher

import dev.omalauncher.oma.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class CanvasReceiptTest {
    @Test fun canonicalMatchesSortedUtf8ServerFormat() {
        val input = JSONObject().put("z", "</script>\n雪").put("a", JSONObject().put("decision", "approve").put("other", ""))
        assertEquals("""{"a":{"decision":"approve","other":""},"z":"</script>\n雪"}""", CanvasReceipt.canonical(input))
        assertEquals(CanvasReceipt.canonical(input), CanvasReceipt.canonical(JSONObject(input.toString())))
    }
    @Test fun signedContentsBindExactArtifactRecipientAndAnswers() {
        val source = ArtifactReplySource("soma:phone:B:mock.html", "mock.html", "6", CanvasReceipt.sha256("exact HTML".toByteArray()))
        val request = ArtifactReplyRequest("phone:B", "decision: approve", """{"decision":"approve"}""")
        val receipt = CanvasReceipt.receipt("device", "nonce", "2026-10-26T16:00:00Z", source, request)
        assertEquals(source.sha256, receipt.getJSONObject("artifact").getString("sha256"))
        assertEquals("phone:B", receipt.getString("recipient"))
        assertEquals(CanvasReceipt.sha256("""{"decision":"approve"}""".toByteArray()), receipt.getString("answersSha256"))
        assertNotEquals(source.sha256, CanvasReceipt.sha256("different HTML".toByteArray()))
    }
    @Test fun structuredSubmitIsValidatedAndKeepsApprovalDecision() {
        val html = """<head><meta name="soma-reply-to" content="phone:C"></head>"""
        fun request(json: String) = ArtifactReply.request(html, "soma-answer:" + URLEncoder.encode("soma-json:$json", "UTF-8"), true, true)
        assertEquals("approve", JSONObject(request("""{"decision":"approve","other":"Use it"}""")!!.answers!!).getString("decision"))
        for (bad in listOf("{}", "[]", """{"decision":{"nested":"approve"}}""", """{"decision":"\u0000"}""")) assertNull(request(bad))
    }
    @Test fun keyBridgeOriginCannotFollowAUserInfoOrDifferentHost() {
        assertEquals("http://100.105.137.74:5860", CanvasReceipt.origin("http://100.105.137.74:5860/a/soma-chat"))
        assertNull(CanvasReceipt.origin("http://user@100.105.137.74:5860"))
        assertNull(CanvasReceipt.origin("javascript:alert(1)"))
        assertNotEquals(CanvasReceipt.origin("http://evil.invalid:5860"), CanvasReceipt.origin("http://100.105.137.74:5860"))
    }
}
