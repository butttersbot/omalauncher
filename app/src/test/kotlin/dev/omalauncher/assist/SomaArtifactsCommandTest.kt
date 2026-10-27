package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What a phone builder runs with for `soma-artifact` (features/artifact-panel/PLAN.md, slice 3). */
class SomaArtifactsCommandTest {
    @Test fun eachBuilderKnowsItsThreadAndClaudeMayRunSomaArtifactWithoutACard() {
        val dir = kotlin.io.path.createTempDirectory("soma-artifacts").toFile()
        try {
            File(dir, "claude").apply { writeText("#!/bin/sh\necho \"thread=\$SOMA_THREAD\"\nprintf '%s\\n' \"\$@\"\n"); setExecutable(true) }
            for (builder in BuilderId.entries.take(PHONE_BUILDERS)) {
                val process = ProcessBuilder("/bin/sh", "-c", withSomaArtifacts("export OMA_BUILDER=${builder.letter}\n" + claudeStreamCommand(), builder)).apply {
                    environment()["PATH"] = dir.path + ":" + environment()["PATH"]
                }.start()
                process.outputStream.close()
                val out = process.inputStream.bufferedReader().readText().lines()
                assertEquals(0, process.waitFor())
                assertEquals("thread=" + builder.thread, out.first())
                val allowed = out.subList(out.indexOf("--allowedTools") + 1, out.size)
                assertEquals(listOf("Bash(soma-artifact *)", "Bash(oma-phone *)", "Read"), allowed.filter { it.isNotEmpty() })
            }
            // Codex and the others are given the thread alone.
            assertTrue(withSomaArtifacts("codex exec --json", BuilderId.B).startsWith("export SOMA_THREAD=phone-b\n"))
            assertEquals("export SOMA_THREAD=phone\ncodex exec --json", withSomaArtifacts("codex exec --json", BuilderId.A))
        } finally { dir.deleteRecursively() }
    }
}
