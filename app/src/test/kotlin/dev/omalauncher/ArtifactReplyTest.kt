package dev.omalauncher

import dev.omalauncher.oma.ArtifactReply
import org.junit.Assert.*
import org.junit.Test

class ArtifactReplyTest {
    private val html = """<html><head><meta name="soma-reply-to" content="phone:B"></head><body>Form</body></html>"""
    @Test fun declaredRecipientIsFixedAndMessagePreserved() {
        val reply = ArtifactReply.request(html, "soma-answer:Choice%20A%0ACover%2C%20Inner%20%2B%20Other", true, true)!!
        assertEquals("phone:B", reply.recipient)
        assertEquals("Choice A\nCover, Inner + Other", reply.text)
    }
    @Test fun missingDuplicateOrScriptDeclarationsDoNotGrantAReply() {
        assertNull(ArtifactReply.recipient("<html><body><meta name='soma-reply-to' content='phone:A'></body></html>"))
        assertNull(ArtifactReply.recipient(html.replace("</head>", "<meta name='soma-reply-to' content='phone:C'></head>")))
        assertNull(ArtifactReply.recipient("<head><script>var s=\"<meta name='soma-reply-to' content='phone:C'>\";</script></head>"))
        assertNull(ArtifactReply.recipient("<head><!--<meta name='soma-reply-to' content='phone:C'>--></head>"))
        assertNull(ArtifactReply.recipient(html.replace("phone:B", "https://evil.invalid")))
    }
    @Test fun backgroundScriptsSubframesBadPayloadsAndOtherSchemesAreRefused() {
        assertNull(ArtifactReply.request(html, "soma-answer:hi", false, true))
        assertNull(ArtifactReply.request(html, "soma-answer:hi", true, false))
        for (url in listOf("https://evil.invalid", "soma-answer:%", "soma-answer:%00", "soma-answer:%20", "soma-answer:" + "x".repeat(4097)))
            assertNull(ArtifactReply.request(html, url, true, true))
    }
}
