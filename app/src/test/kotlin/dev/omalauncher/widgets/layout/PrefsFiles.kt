package dev.omalauncher.widgets.layout

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** A SharedPreferences XML file as its map: strings and ints are all the layout stores hold; other types are left out. */
fun readPrefs(file: File): Map<String, Any> {
    if (!file.exists()) return emptyMap()
    val map = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    val entries = map.childNodes
    val out = LinkedHashMap<String, Any>()
    for (i in 0 until entries.length) {
        val e = entries.item(i) as? Element ?: continue
        val name = e.getAttribute("name")
        when (e.tagName) {
            "string" -> out[name] = e.textContent
            "int" -> e.getAttribute("value").toIntOrNull()?.let { out[name] = it }
        }
    }
    return out
}

/** The widgets a folder of pulled preferences stands for, from its `widgets.txt`: a file name, its page and its size (`3x2`, or `-`) a line. */
fun readWidgets(file: File): List<OldWidget> =
    file.takeIf { it.exists() }?.readLines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
        val parts = line.split(Regex("\\s+"))
        val size = parts.getOrNull(2)?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        OldWidget(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 1, size)
    }

/** The old stores in [dir]: `widget_grid.xml`, `shortcuts.xml` and, if it's there, `widget_pages.xml`. */
fun readStores(dir: File) = OldStores.of(readPrefs(File(dir, "widget_grid.xml")), readPrefs(File(dir, "shortcuts.xml")), readPrefs(File(dir, "widget_pages.xml")))
