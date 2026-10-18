package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Each screen draws what it can and refuses a widget that needs what it hasn't, before it is placed (Soma Canvas slice 7a). */
class HostsTest {
    private fun widget(body: String) = Compiler.compile("widget \"w\" {\n  size: 3x2\n  meta { owl: 6 }\n$body\n}")

    @Test fun webTerminalAndShaderWidgetsAreRefusedOffTheFold() {
        val web = widget("  Web { src: \"https://s.tradingview.com/widgetembed/\" }")
        val term = widget("  Terminal { command: \"htop\" }")
        val shader = widget("  Shader { code: \"half4 main(float2 p) { return half4(1); }\"; fallback: red }")
        // A node used only on the widget's screen counts too: it would be drawn on the same host.
        val onScreen = widget("  Text { text: \"hi\" }\n  screen {\n    Web { src: \"https://s.tradingview.com/widgetembed/\" }\n  }")
        for (p in listOf(web, term, shader, onScreen)) {
            assertEquals(emptyList<String>(), hostRefusals(p, OwlHost.Fold))
            for (host in listOf(OwlHost.Desktop, OwlHost.Service)) assertEquals(p.nodeTypes.toString(), 1, hostRefusals(p, host).size)
        }
        assertTrue(hostRefusals(web, OwlHost.Desktop).single().startsWith("Web nodes don't draw on desktop"))
    }

    @Test fun everyShippedWidgetWithoutThoseNodesDrawsEverywhere() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/widgets") }.first { it.isDirectory }
        val files = dir.walk().filter { it.extension == "owl" }.toList()
        assertTrue(files.size > 20)
        for (f in files) {
            val p = Compiler.compile(f.readText())
            val needs = p.nodeTypes.intersect(setOf("Web", "Terminal", "Shader"))
            assertEquals(f.name, needs.isEmpty(), hostRefusals(p, OwlHost.Desktop).isEmpty())
        }
        val watch = Compiler.compile(File(dir, "examples/86-watchlist.owl").readText())
        assertTrue(hostRefusals(watch, OwlHost.Service).isEmpty())
    }
}
