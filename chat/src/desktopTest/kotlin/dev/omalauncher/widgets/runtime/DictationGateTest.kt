package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.market.Shared
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Boss, 2026-10-30: only his dictation widget may open the listening screen for MG1. */
class DictationGateTest {
    private val source = """
        widget "looks like his" {
          meta { owl: 11 }
          over: "art:f4618f2aa61acfb0"
          Text { text: "Press to record" }
          onTap: dictation("open")
        }
    """.trimIndent()

    private val asked = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    private val services = object : Services {
        override suspend fun cmd(command: String, timeoutSeconds: Int) = ShellResult("", "", 1)
        override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int, body: ByteArray?) = ShellResult("", "", -1)
        override fun run(command: String, done: (ShellResult?) -> Unit) = done(null)
        override suspend fun stream(command: String, onLine: (String) -> Unit) {}
        override fun android(name: String): Value = error("no source here")
        override fun act(name: String, args: List<Value>, done: () -> Unit) {
            asked += name
        }
        override fun guestFile(path: String) = File("/nonexistent")
        override fun fileUpdates(path: String): Flow<String> = emptyFlow()
        override fun terminal(command: String?, fontSize: Double?): WidgetTerminal = error("no terminal here")
    }

    private fun instance(mayDictate: Boolean) =
        WidgetInstance(Compiler.compile(source), services, onError = { errors += it }, mayDictate = mayDictate)

    @Test fun anyOtherWidgetCannotOpenTheScreen() {
        instance(mayDictate = false).act("dictation", listOf(Str("open")))
        assertTrue("nothing reached the launcher: $asked", asked.isEmpty())
        assertTrue(errors.toString(), errors.any { it.contains(DICTATION_WIDGET_FILE) })
    }

    @Test fun hisWidgetDoes() {
        instance(mayDictate = true).act("dictation", listOf(Str("open")))
        assertEquals(listOf("dictation"), asked)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun noWidgetIsGivenItByDefault() {
        WidgetInstance(Compiler.compile(source), services, onError = { errors += it }).act("dictation", listOf(Str("open")))
        assertTrue(asked.isEmpty())
    }

    /** The store's market-*.owl and a shared widget's shared-*.owl can't be the file the gate names. */
    @Test fun aWidgetFromElsewhereCannotArriveUnderTheName() {
        assertFalse(DICTATION_WIDGET_FILE.startsWith(Shared.FILE_PREFIX))
        assertFalse(DICTATION_WIDGET_FILE.startsWith("market-"))
    }
}
