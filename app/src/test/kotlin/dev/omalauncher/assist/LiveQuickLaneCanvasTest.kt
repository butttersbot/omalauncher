package dev.omalauncher.assist

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The phone's quick lane on the real fast model, with a stand-in `oma-phone` that writes down what it is asked (slice 6):
 * a module moved or put it arranges itself with `oma-phone canvas`; a new page, an undo, a sticker or a widget it hands to
 * a builder (which has the same `oma-phone canvas` for pages and undo); a question it answers. Runs only when SOMA_LIVE
 * names Claude Code.
 */
class LiveQuickLaneCanvasTest {
    private fun ask(claude: String, words: String): Pair<String, List<String>> {
        val bin = Files.createTempDirectory("oma-live").toFile()
        val calls = File(bin, "calls.txt")
        File(bin, "oma-phone").apply {
            writeText(
                "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${calls.path}'\n" +
                    "case \"\$1 \$2\" in\n" +
                    "  'canvas board'*) echo '{\"ok\":true,\"columns\":6,\"rows\":8,\"pages\":[{\"page\":\"Home\",\"items\":[{\"kind\":\"app\",\"name\":\"app:chrome\",\"size\":\"dot\",\"cells\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}],\"free\":47}]}';;\n" +
                    "  'canvas help'|'canvas --help'|'canvas -h'|'canvas modules') echo 'MODULE: needs, threads, project:NAME, handoff:NAME. SIZE: dot line card card-wide card-big full. WHERE: top-left top-right bottom any right-of MODULE below MODULE. place MODULE [SIZE] [WHERE] [--page NAME]; page-make NAME; undo';;\n" +
                    "  canvas*) echo '{\"ok\":true,\"did\":\"done\"}';;\n" +
                    "  *) echo '{\"ok\":true}';;\n" +
                    "esac\n",
            )
            setExecutable(true)
        }
        val command = quickCommand(frontPrompt(buildBoard(emptyList(), false, "", System.currentTimeMillis()), words), mac = System.getenv("SOMA_MAC") != null)
        val child = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment()["PATH"] = listOf(bin.path, File(claude).parent, "/opt/homebrew/bin", "/usr/bin", "/bin").joinToString(":")
        }.redirectErrorStream(true).start()
        val out = child.inputStream.bufferedReader().readText()
        child.waitFor(180, TimeUnit.SECONDS)
        val result = Regex("\"result\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(out).lastOrNull()?.groupValues?.get(1).orEmpty()
        return result to (if (calls.exists()) calls.readLines() else emptyList())
    }

    @Test fun probe() {
        val claude = System.getenv("SOMA_LIVE")
        assumeTrue(claude != null && File(claude).canExecute() && System.getenv("SOMA_PROBE") != null)
        for (w in System.getenv("SOMA_PROBE")!!.split("|")) System.err.println("PROBE $w => " + ask(claude!!, w))
    }

    @Test fun modulesAreArrangedHereAndEverythingElseOnHomeIsHandedOver() {
        assumeTrue(System.getenv("SOMA_PROBE") == null)
        val claude = System.getenv("SOMA_LIVE")
        assumeTrue(claude != null && File(claude).canExecute())
        val page = ask(claude!!, "make me a page for the soma project")
        System.err.println("page: $page")
        assertTrue(page.toString(), page.first.trim() == HAND_OVER || (page.second.any { it.startsWith("canvas page-make") } && page.second.any { it.startsWith("canvas place needs") }))
        val sticker = ask(claude, "put a cat sticker on my home screen")
        System.err.println("sticker: $sticker")
        assertTrue(sticker.toString(), sticker.first.trim() == HAND_OVER && sticker.second.none { it.startsWith("canvas") })
        val widget = ask(claude, "make me a weather widget")
        System.err.println("widget: $widget")
        assertTrue(widget.toString(), widget.first.trim() == HAND_OVER)
        val move = ask(claude, "move the needs you module to the top right")
        System.err.println("move: $move")
        assertTrue(move.toString(), move.second.any { it.startsWith("canvas move needs") || it.startsWith("canvas place needs") })
    }
}
