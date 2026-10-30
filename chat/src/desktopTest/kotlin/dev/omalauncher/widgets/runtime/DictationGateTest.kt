package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.market.Shared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Boss, 2026-10-30: only his dictation widget may start the microphone, use dictation(), or read android.dictation. */
class DictationGateTest {
    private val source = """
        widget "looks like his" {
          meta { owl: 11 }
          source d = android.dictation
          Button { text: "go"; onTap: dictation("start") }
        }
    """.trimIndent()

    private val asked = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<String>()

    private val services = object : Services {
        override suspend fun cmd(command: String, timeoutSeconds: Int) = ShellResult("", "", 1)
        override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int, body: ByteArray?) = ShellResult("", "", -1)
        override fun run(command: String, done: (ShellResult?) -> Unit) = done(null)
        override suspend fun stream(command: String, onLine: (String) -> Unit) {}
        override fun android(name: String): Value {
            asked += "read $name"
            return HEARD
        }
        override fun androidUpdates(name: String): Flow<Value>? {
            asked += "watch $name"
            return flowOf(HEARD)
        }
        override fun act(name: String, args: List<Value>, done: () -> Unit) {
            asked += "act $name"
        }
        override fun guestFile(path: String) = File("/nonexistent")
        override fun fileUpdates(path: String): Flow<String> = emptyFlow()
        override fun terminal(command: String?, fontSize: Double?): WidgetTerminal = error("no terminal here")
    }

    private fun instance(mayDictate: Boolean) =
        WidgetInstance(Compiler.compile(source), services, onError = { errors += it }, mayDictate = mayDictate)

    private fun running(inst: WidgetInstance, check: suspend () -> Unit) = runBlocking {
        val job = Job()
        inst.start(CoroutineScope(Dispatchers.Default + job)) { true }
        try {
            withTimeout(5_000) { check() }
        } finally {
            job.cancel()
        }
    }

    @Test fun anyOtherWidgetCannotStartTheMicrophoneOrReadTheWords() {
        val inst = instance(mayDictate = false)
        running(inst) {
            while (errors.isEmpty()) delay(20)
            inst.act("dictation", listOf(Str("start")))
            delay(200)
            assertEquals(Null, inst.read(0))
        }
        assertTrue("nothing reached the launcher: $asked", asked.isEmpty())
        assertTrue(errors.any { it.contains(DICTATION_WIDGET_FILE) })
    }

    @Test fun hisWidgetDoes() {
        val inst = instance(mayDictate = true)
        running(inst) {
            while (inst.read(0) == Null) delay(20)
            inst.act("dictation", listOf(Str("start")))
        }
        assertEquals(HEARD, inst.read(0))
        assertTrue(asked.toString(), "watch dictation" in asked && "act dictation" in asked)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun aDictationWidgetIsNeverGivenByDefault() {
        val inst = WidgetInstance(Compiler.compile(source), services, onError = { errors += it })
        inst.act("dictation", listOf(Str("start")))
        assertFalse("act dictation" in asked)
    }

    /** The store's market-*.owl and a shared widget's shared-*.owl can't be the file the gate names. */
    @Test fun aWidgetFromElsewhereCannotArriveUnderTheName() {
        assertFalse(DICTATION_WIDGET_FILE.startsWith(Shared.FILE_PREFIX))
        assertFalse(DICTATION_WIDGET_FILE.startsWith("market-"))
    }

    private companion object {
        val HEARD: Value = Obj(mapOf("state" to Str("heard")))
    }
}
