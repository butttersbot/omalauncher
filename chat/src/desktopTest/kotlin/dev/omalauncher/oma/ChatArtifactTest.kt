package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import org.json.JSONObject

class ChatArtifactTest {
    @Test fun outputsComeFromRepliesNotDirectories() {
        val text = """
            Here is [the preview](/Users/boss/Projects/demo/output%20one.html:12).
            And [notes](outputs/notes.md), [unsafe](javascript:alert(1)).
            ```html
            <h1>Hello</h1>
            ```
        """.trimIndent()
        val files = chatArtifacts(text, "turn1", "mac/t1")
        assertEquals(3, files.size)
        assertEquals("/Users/boss/Projects/demo/output one.html", files[0].location)
        assertEquals(ArtifactKind.Html, files[0].kind)
        assertEquals(ArtifactKind.Markdown, files[1].kind)
        assertEquals("<h1>Hello</h1>", files[2].inline)
        assertEquals(ArtifactKind.Html, files[2].kind)
    }

    @Test fun linkIdentitySurvivesAnotherTurnAndSnippetIdentitySurvivesEdits() {
        assertEquals(chatArtifacts("[x](a.md)", "a", "mac/t").single().id, chatArtifacts("[x](a.md)", "b", "mac/t").single().id)
        assertNotEquals(chatArtifacts("[x](a.md)", "a", "mac/t").single().id, chatArtifacts("[x](a.md)", "a", "other/t").single().id)
        val first = chatArtifacts("```html\n<h1>A</h1>\n```", "a", "t").single()
        val second = chatArtifacts("```html\n<h1>B</h1>\n```", "a", "t").single()
        assertEquals(first.id, second.id)
        assertNotEquals(inlineArtifact(first).document.version, inlineArtifact(second).document.version)
    }

    @Test fun pathsKeepLiteralPlusAndRejectSchemesAndRemoteFileHosts() {
        assertEquals("a+b.md", artifactPath("a+b.md"))
        assertEquals("/tmp/a.md", artifactPath("file:///tmp/a.md#heading"))
        assertNull(artifactPath("file://elsewhere/tmp/a.md"))
        assertNull(artifactPath("content://files/a.md"))
        assertNull(artifactPath("javascript:a.html"))
        assertNull(artifactPath("a.exe"))
        assertTrue(chatArtifacts("[page](https://example.org/preview)", "a", "t").single().kind == ArtifactKind.Web)
        assertTrue(chatArtifacts("[page](https://user:secret@example.org/)", "a", "t").isEmpty())
    }

    @Test fun longDocumentsHaveAnExplicitReadingLimit() {
        val doc = ArtifactDocument("long.txt", "text/plain", "x".repeat(150_000).toByteArray(), "v1")
        assertTrue(doc.readingText.length < 129_000)
        assertTrue(doc.readingText.endsWith("Preview limited to 128,000 characters."))
    }

    @Test fun aRunnerReadDecodesBytesAndNeverReflectsAnErrorBody() {
        val body = JSONObject().put("ok", true).put("name", "plan.md").put("mime", "text/markdown")
            .put("version", "abcd").put("data", Base64.getEncoder().encodeToString("# Plan".toByteArray())).toString()
        val read = decodeArtifact(200, body) as ArtifactRead.Ready
        assertEquals("# Plan", read.document.text)
        assertEquals("abcd", read.document.version)
        for (code in listOf(401, 403, 404, 413, 415, 500)) {
            val error = decodeArtifact(code, "SECRET TOKEN /private/path") as ArtifactRead.Failed
            assertFalse(error.reason.contains("SECRET"))
            assertFalse(error.reason.contains("/private"))
        }
        assertTrue(decodeArtifact(200, "{}") is ArtifactRead.Failed)
    }
}
