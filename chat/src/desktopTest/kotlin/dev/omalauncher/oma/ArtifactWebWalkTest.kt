package dev.omalauncher.oma

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import kotlin.concurrent.thread

/**
 * The HTML engine walk, in a real window on a real desktop (docs/CHAT-ARTIFACT-WORKSPACE.md, "Desktop HTML engine"): the pinned
 * natives installed as the update pill installs them, Chromium started as Soma starts it, and a page shown in the artifact panel's
 * own view with its sandbox and native Send bar. Skipped unless asked:
 *
 *   ./gradlew :chat:desktopTest --tests '*ArtifactWebWalkTest' -PsomaWebWalk=DIR -PsomaWebNatives=JAR -PsomaWebPage=HTML [-PsomaWebScale=2]
 *
 * Input is real (java.awt.Robot: X11 input into the window, as a person's would be). Each step asks for a screenshot by writing
 * DIR/want-NAME and waits for DIR/NAME.png, which the walk's driver takes from outside (on Hyprland: grim of the window).
 * A step list (-PsomaWebSteps=FILE, else HTML.walk beside the page; one step a line: click X% Y% · press X% Y% MS · key K · hold KEY MS · wait MS · shot NAME) says
 * what to do; with none, it only shows the page and takes one shot. What the page answered through the Send bar is in DIR/answers.txt.
 */
class ArtifactWebWalkTest {
    @Test fun walk() {
        val out = System.getProperty("soma.webwalk").orEmpty()
        assumeTrue("the walk runs only when asked (-PsomaWebWalk=DIR)", out.isNotEmpty())
        val dir = File(out).apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("want-") || it.name == "done" || it.name == "log.txt" }?.forEach { it.delete() }
        val pageFile = File(System.getProperty("soma.webpage"))
        val page = pageFile.readText()
        val steps = (System.getProperty("soma.websteps").orEmpty().takeIf { it.isNotEmpty() }?.let(::File) ?: File(pageFile.path + ".walk")).takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: listOf("wait 4000", "shot page")
        WebEngine.home = File(dir, "engine")
        if (!WebEngine.installed()) assertNull(WebEngine.install(File(System.getProperty("soma.webnatives"))))
        val answers = File(dir, "answers.txt").apply { writeText("") }
        val log = File(dir, "log.txt")
        fun say(line: String) = log.appendText(line + "\n")
        var shown: ComposeWindow? = null
        application(exitProcessOnExit = false) {
            val state = rememberWindowState(size = DpSize(1100.dp, 760.dp))
            Window(onCloseRequest = ::exitApplication, state = state, title = "soma-web-walk") {
                shown = window
                ArtifactWeb(null, page, Modifier.fillMaxSize(), onAnswer = { request, done ->
                    answers.appendText(request.recipient + " | " + request.text.replace('\n', ' ') + "\n")
                    done(ArtifactReplyResult(true, "Sent to ${request.recipient} (walk)"))
                })
            }
            remember {
                thread(name = "walk", isDaemon = true) {
                    try {
                        val until = System.currentTimeMillis() + 120_000
                        while (WebEngine.state !is WebEngineState.Ready && System.currentTimeMillis() < until) Thread.sleep(250)
                        say("engine: ${WebEngine.state.toString().take(200)}")
                        Thread.sleep(3_000)
                        val robot = Robot().apply { autoDelay = 40 }
                        val window = shown!!
                        fun at(x: String, y: String): Pair<Int, Int> {
                            val p = window.locationOnScreen
                            return (p.x + window.width * x.removeSuffix("%").toDouble() / 100).toInt() to (p.y + window.height * y.removeSuffix("%").toDouble() / 100).toInt()
                        }
                        fun code(key: String): Int = when (key) {
                            "space" -> KeyEvent.VK_SPACE; "tab" -> KeyEvent.VK_TAB; "enter" -> KeyEvent.VK_ENTER; "escape" -> KeyEvent.VK_ESCAPE
                            else -> KeyEvent.getExtendedKeyCodeForChar(key.single().code)
                        }
                        for (step in steps) {
                            val w = step.split(Regex("\\s+"))
                            say("step: $step")
                            when (w[0]) {
                                "click" -> { val (x, y) = at(w[1], w[2]); robot.mouseMove(x, y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                                "press" -> { val (x, y) = at(w[1], w[2]); robot.mouseMove(x, y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(w[3].toLong()); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                                "key" -> { robot.keyPress(code(w[1])); robot.keyRelease(code(w[1])) }
                                "hold" -> { robot.keyPress(code(w[1])); Thread.sleep(w[2].toLong()); robot.keyRelease(code(w[1])) }
                                "wait" -> Thread.sleep(w[1].toLong())
                                "shot" -> {
                                    File(dir, "want-${w[1]}").writeText("")
                                    val shotUntil = System.currentTimeMillis() + 20_000
                                    while (!File(dir, "${w[1]}.png").exists() && System.currentTimeMillis() < shotUntil) Thread.sleep(200)
                                    say(if (File(dir, "${w[1]}.png").exists()) "shot: ${w[1]}" else "shot: ${w[1]} (none taken)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        say("walk stopped: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        File(dir, "done").writeText("")
                        javax.swing.SwingUtilities.invokeLater { exitApplication() }
                    }
                }
            }
        }
        WebEngine.dispose()
    }
}
