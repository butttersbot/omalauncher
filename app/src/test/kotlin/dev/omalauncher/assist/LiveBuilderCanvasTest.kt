package dev.omalauncher.assist

import dev.omalauncher.phone.PhoneBridge
import dev.omalauncher.linux.shellQuote
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A phone builder on the real model, told what it is told on the Fold (PhoneBridge.INSTRUCTIONS) and given a stand-in
 * `oma-phone` that answers `help` and `canvas help` with the real script's text and writes down every call (slice 6):
 * "make me a page for the soma project" is made with `oma-phone canvas`, Needs you included; "undo that" runs
 * `oma-phone canvas undo`. Runs only when SOMA_LIVE names Claude Code.
 */
class LiveBuilderCanvasTest {
    private val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/oma-phone.sh") }.first { it.isFile }.readText()

    private fun section(marker: String) = script.substringAfter("cat <<'$marker'\n").substringBefore("\n$marker")

    private fun ask(claude: String, words: String, resume: String? = null): Pair<List<String>, String?> {
        val bin = Files.createTempDirectory("oma-builder").toFile()
        val calls = File(bin, "calls.txt")
        File(bin, "help.txt").writeText(section("HELP"))
        File(bin, "canvas.txt").writeText(section("CANVAS"))
        File(bin, "oma-phone").apply {
            writeText(
                "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${calls.path}'\n" +
                    "case \"\$1 \$2\" in\n" +
                    "  'help '*) cat '${bin.path}/help.txt';;\n" +
                    "  'canvas help'|'canvas --help'|'canvas ') cat '${bin.path}/canvas.txt';;\n" +
                    "  'canvas board'*) echo '{\"ok\":true,\"columns\":6,\"rows\":8,\"pages\":[{\"page\":\"Home\",\"items\":[],\"free\":48}]}';;\n" +
                    "  canvas*) echo '{\"ok\":true,\"did\":\"done\"}';;\n" +
                    "  'status '*) echo '{\"ok\":true,\"active\":true,\"connected\":true}';;\n" +
                    "  'pages '*) echo '{\"ok\":true,\"pages\":[\"Home\"],\"showing\":0}';;\n" +
                    "  *) echo '{\"ok\":true}';;\n" +
                    "esac\n",
            )
            setExecutable(true)
        }
        val command = "claude -p --output-format stream-json --verbose --permission-mode manual --permission-prompts none " +
            (resume?.let { "--resume $it " } ?: "") +
            "--append-system-prompt ${shellQuote(PhoneBridge.INSTRUCTIONS)} --allowedTools 'Bash(oma-phone *)' Read -- ${shellQuote(words)} < /dev/null"
        val child = ProcessBuilder("/bin/sh", "-c", command).directory(bin).apply {
            environment()["PATH"] = listOf(bin.path, File(claude).parent, "/opt/homebrew/bin", "/usr/bin", "/bin").joinToString(":")
        }.redirectErrorStream(true).start()
        val out = child.inputStream.bufferedReader().readText()
        child.waitFor(300, TimeUnit.SECONDS)
        val session = Regex("\"session_id\":\"([0-9a-f-]{36})\"").findAll(out).lastOrNull()?.groupValues?.get(1)
        return (if (calls.exists()) calls.readLines() else emptyList()) to session
    }

    @Test fun aPageForAProjectIsMadeOfModulesAndUndoTakesItBack() {
        val claude = System.getenv("SOMA_LIVE")
        assumeTrue(claude != null && File(claude).canExecute())
        val (made, session) = ask(claude!!, "make me a page for the soma project")
        System.err.println("page: $made")
        assertTrue(made.toString(), made.any { it.startsWith("canvas page-make") } && made.any { it.startsWith("canvas place needs") })
        val (undone, _) = ask(claude, "undo that", resume = session)
        System.err.println("undo: $undone")
        assertTrue(undone.toString(), undone.any { it == "canvas undo" })
    }
}
