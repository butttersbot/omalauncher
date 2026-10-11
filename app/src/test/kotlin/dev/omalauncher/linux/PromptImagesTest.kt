package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class PromptImagesTest {
    @Test fun codexImagesAreSeparateShellArguments() {
        val command = agentInvocation("codex", "what's here?", images = listOf("/root/a b.png", "/root/a'\";echo bad.png"))
        assertTrue(command.startsWith("codex --image "))
        assertTrue(command.contains("--image ${shellQuote("/root/a b.png")}"))
        assertTrue(command.endsWith(" -- ${shellQuote("what's here?")}"))
        assertTrue(command.contains(shellQuote("/root/a'\";echo bad.png")))
    }

    @Test fun shellReceivesImagesAndPromptWithoutExecutingTheirContents() {
        val images = listOf("/root/a b.png", "/root/a'\";echo bad.png")
        val prompt = "--help; echo injected; $(printf unsafe)"
        val command = agentInvocation("codex", prompt, images = images)
        // Shadow the agent with an argv printer: this invokes no agent and makes no network requests.
        val process = ProcessBuilder("/bin/sh", "-c", "codex() { printf '%s\\0' \"$@\"; }; " + command).start()
        val output = process.inputStream.readBytes().decodeToString().split('\u0000').dropLast(1)
        assertEquals(0, process.waitFor())
        assertEquals(listOf("--image", images[0], "--image", images[1], "--", prompt), output)
    }

    @Test fun claudeReadsSelectedImagesAndKeepsReadOnlyMode() {
        val command = agentInvocation("claude", "compare", readOnly = true, images = listOf("/root/a.png", "/root/b.png"))
        assertTrue(command.startsWith("claude --permission-mode plan "))
        assertTrue(command.contains("Read these attached images before answering:"))
        assertTrue(command.contains("/root/a.png\n/root/b.png"))
    }

    @Test fun unsupportedAgentDoesNotSilentlyLoseImages() {
        assertThrows(IllegalArgumentException::class.java) {
            agentInvocation("opencode", "look", images = listOf("/root/a.png"))
        }
        assertEquals("codex ${shellQuote("hello")}", agentInvocation("codex", "hello"))
    }

    @Test fun streamingLimitRemovesPartialCopy() {
        val file = File.createTempFile("attachment", ".png")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                copyPromptImage(ByteArrayInputStream(ByteArray(11)), file, limit = 10)
            }
            assertFalse(file.exists())
            copyPromptImage(ByteArrayInputStream(ByteArray(10) { 7 }), file, limit = 10)
            assertArrayEquals(ByteArray(10) { 7 }, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun emptyImageIsRejected() {
        val file = File.createTempFile("attachment", ".png")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                copyPromptImage(ByteArrayInputStream(byteArrayOf()), file)
            }
            assertFalse(file.exists())
        } finally { file.delete() }
    }
}
