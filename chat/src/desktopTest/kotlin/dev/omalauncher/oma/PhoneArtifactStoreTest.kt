package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The phone's own `soma-artifact` (app asset soma-artifact.sh) run for real, and the store it keeps read back as the app reads it. */
class PhoneArtifactStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/soma-artifact.sh") }.firstOrNull { it.isFile }

    private fun run(vararg words: String, thread: String = "phone", input: String? = null): Pair<Int, String> {
        assumeTrue("sh, jq and sha256sum are needed", listOf("jq", "sha256sum").all { tool -> System.getenv("PATH").split(':').any { File(it, tool).canExecute() } })
        val process = ProcessBuilder(listOf("/bin/sh", script!!.path) + words).directory(tmp.root).redirectErrorStream(true).apply {
            environment()["SOMA_ARTIFACT_STORE"] = File(tmp.root, "store").path
            environment()["SOMA_THREAD"] = thread
        }.start()
        if (input != null) process.outputStream.use { it.write(input.toByteArray()) } else process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    private val store get() = PhoneArtifactStore(File(tmp.root, "store"))

    @Test fun putEditingAndListKeepEveryVersionAndTellTheApp() {
        File(tmp.root, "neat.html").writeText("<p>one</p>")
        assertEquals(0, run("put", "neat.html", "--title", "NEAT tracker", "--note", "first").first)
        assertEquals(0, run("editing", "neat.html").first)
        assertEquals(0, run("put", "-", "--name", "neat.html", "--note", "second", input = "<p>two</p>").first)
        assertTrue(run("list").second.contains("neat.html  v2  NEAT tracker  (second)"))
        assertEquals(0 to "<p>one</p>", run("get", "neat.html", "--version", "1"))
        assertEquals(0 to "<p>two</p>", run("get", "neat.html"))
        assertNotEquals(0, run("get", "neat.html", "--version", "9").first)

        val two = (store.read("phone", somaArtifactPath("neat.html")) as ArtifactRead.Ready).document
        assertEquals(listOf("<p>two</p>", "text/html", "2", "2", "NEAT tracker"), listOf(two.text, two.mime, two.v.toString(), two.latest.toString(), two.title))
        val one = (store.read("phone", somaArtifactPath("neat.html", 1)) as ArtifactRead.Ready).document
        assertEquals("<p>one</p>", one.text)
        assertEquals(listOf("first", "second"), store.versions("phone", "neat.html")!!.versions.map { it.note })
        assertEquals(listOf("neat.html"), store.list("phone").map { it.name })

        val (events, next) = store.events("phone", 0)
        assertEquals(listOf("ready" to 1, "editing" to 2, "ready" to 2), events.map { it.state to it.version })
        assertEquals(File(tmp.root, "store/phone/events.jsonl").length(), next)
        assertTrue("nothing new from where it was read to", store.events("phone", next).first.isEmpty())
    }

    @Test fun namesThreadsAndSizesAnArtifactMayNotHaveAreRefused() {
        for (name in listOf("../x.html", ".hidden.html", "x.exe", "a/b.html", "noext", "x..html", "-x.html"))
            assertNotEquals(name, 0, run("put", "-", "--name", name, input = "x").first)
        assertNotEquals(0, run("put", "-", "--name", "empty.html", input = "").first)
        assertNotEquals(0, run("list", thread = "t1234abcd").first)
        assertNotEquals(0, run("list", thread = "").first)
        assertNotEquals(0, run("put", "-", "--name", "big.html", input = "x".repeat(4 * 1024 * 1024 + 1)).first)
        assertFalse(File(tmp.root, "store/phone/big.html/v1.html").exists())
    }

    @Test fun whatAnAgentLeavesInTheStoreIsNeverFollowedOrTrusted() {
        File(tmp.root, "neat.html").writeText("<p>one</p>")
        run("put", "neat.html")
        val secret = File(tmp.root, "secret.html").apply { writeText("SECRET") }
        val version = File(tmp.root, "store/phone/neat.html/v1.html")
        version.delete()
        Files.createSymbolicLink(version.toPath(), secret.toPath())
        assertTrue(store.read("phone", somaArtifactPath("neat.html")) is ArtifactRead.Failed)
        // A record naming other files reads only versions it can name itself.
        File(tmp.root, "store/phone/neat.html/meta.json").writeText("""{"versions": [{"v": "../../secret"}, {"v": true}, {"v": 1}]}""")
        assertEquals(listOf(1), store.versions("phone", "neat.html")!!.versions.map { it.version })
        // A thread that is not the phone's, or a path that is no artifact's, reads nothing.
        assertTrue(store.read("t1234abcd", somaArtifactPath("neat.html")) is ArtifactRead.Failed)
        assertTrue(store.read("phone", "/etc/passwd") is ArtifactRead.Failed)
        assertTrue(store.read("phone", "soma-artifact:../secret.html") is ArtifactRead.Failed)
    }

    @Test fun aPartLineWaitsAndAShorterFileStartsOver() {
        val folder = File(tmp.root, "store/phone").apply { mkdirs() }
        val events = File(folder, "events.jsonl")
        events.writeText("""{"name":"a.html","version":1,"state":"ready","title":"","note":"","time":5}""" + "\n" + """{"name":"a.html","vers""")
        val (first, at) = store.events("phone", 0)
        assertEquals(1, first.size)
        events.appendText("""ion":2,"state":"ready","title":"","note":"","time":6}""" + "\n" + "not json\n")
        val (second, end) = store.events("phone", at)
        assertEquals(listOf(2), second.map { it.version })
        events.writeText("""{"name":"b.html","version":1,"state":"editing","title":"","note":"","time":7}""" + "\n")
        assertEquals(listOf("b.html"), store.events("phone", end).first.map { it.name })
    }

    @Test fun aToolCallOnAnArtifactsFileIsSeenByItsName() {
        File(tmp.root, "neat.html").writeText("<p>one</p>")
        run("put", "neat.html")
        assertEquals("neat.html", store.touched("phone", "Write", "/root/neat.html", ""))
        assertEquals("neat.html", store.touched("phone", "Bash", "", "python3 fix.py neat.html && soma-artifact put neat.html"))
        assertNull(store.touched("phone", "Bash", "", "soma-artifact put neat.html"))
        assertNull(store.touched("phone", "Bash", "", "cat notneat.html.bak"))
        assertNull(store.touched("phone", "Read", "/root/neat.html", ""))
    }
}
