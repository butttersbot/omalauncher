package dev.omalauncher.desktop

import dev.omalauncher.canvas.AskRefused
import dev.omalauncher.canvas.CanvasShelf
import dev.omalauncher.canvas.TEMPLATE_NAME
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Templates and LAYOUT.md in a Soma folder (slice 10): the service's (~/.config/soma on the Mac Pro), which every screen's
 * copy shares; templates one file each, LAYOUT.md one preference per line, both written whole. A name that isn't a
 * template's, or a file that would land outside the templates folder, is never read or written (round-1 review).
 */
class FileShelf(home: File) : CanvasShelf {
    private val dir = File(home, "templates")
    private val layoutFile = File(home, "LAYOUT.md")

    private fun fileOf(name: String): File {
        if (!TEMPLATE_NAME.matches(name) || ".." in name) throw AskRefused("\"$name\" can't be a template's name. Nothing was changed.")
        val f = File(dir, name.lowercase().replace(Regex("\\s+"), "-") + ".json")
        check(f.canonicalFile.parentFile == dir.canonicalFile) { "a template's file stays in its folder" }
        return f
    }

    @Synchronized override fun template(name: String): String? = runCatching { fileOf(name).readText() }.getOrNull()

    @Synchronized override fun keepTemplate(name: String, text: String) {
        dir.mkdirs()
        val target = fileOf(name)
        val fresh = File(dir, ".new-" + target.name)
        fresh.writeText(text)
        if (!fresh.renameTo(target)) { target.delete(); check(fresh.renameTo(target)) { "couldn't keep the template" } }
    }

    /**
     * The names of the templates kept, each one that reads back by that name (a hand-dropped file whose name isn't one, or
     * isn't its file's, is left out; round-2 review).
     */
    @Synchronized override fun templates(): List<String> = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") && !it.name.startsWith(".") }
        .mapNotNull { f -> runCatching { JSONObject(f.readText()).getString("name") }.getOrNull()?.takeIf { n -> TEMPLATE_NAME.matches(n) && runCatching { fileOf(n).name == f.name }.getOrDefault(false) } }.sorted()

    @Synchronized override fun layout(): String = runCatching { layoutFile.readText() }.getOrDefault("")

    @Synchronized override fun remember(line: String) {
        val was = layout().ifEmpty { "# How Boss likes his canvases\n" }
        layoutFile.parentFile?.mkdirs()
        val fresh = File(layoutFile.parentFile, ".LAYOUT.md.new")
        fresh.writeText(was.trimEnd('\n') + "\n- " + line + "\n")
        if (!fresh.renameTo(layoutFile)) { layoutFile.delete(); check(fresh.renameTo(layoutFile)) { "couldn't keep LAYOUT.md" } }
    }
}

/**
 * The service's shelf from another desktop, over the tailnet door with this desktop's own key (round-1 review: the
 * MacBook's Soma kept templates and preferences to itself). Every call says why when the service doesn't answer.
 */
class RemoteShelf(private val service: String, private val key: String) : CanvasShelf {
    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = URI(service.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 8000; c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = runCatching { c.responseCode }.getOrElse { throw AskRefused("Soma's canvas service on the Mac Pro didn't answer, so templates and LAYOUT.md can't be reached now. Nothing was changed.") }
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code == 404 && method == "GET") return JSONObject()
        if (code != 200) throw AskRefused(o.optString("error").ifEmpty { "Soma's canvas service said $code" } + ". Nothing was changed.")
        return o
    }

    private fun enc(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    override fun template(name: String): String? = call("GET", "/shelf/templates/" + enc(name)).optString("text").ifEmpty { null }
    override fun keepTemplate(name: String, text: String) { call("PUT", "/shelf/templates/" + enc(name), JSONObject().put("text", text)) }
    override fun templates(): List<String> = call("GET", "/shelf").optJSONArray("templates")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    override fun layout(): String = call("GET", "/shelf").optString("layout")
    override fun remember(line: String) { call("POST", "/shelf/layout", JSONObject().put("line", line)) }

    companion object {
        /** The shelf of the service one of [machines]' runners advertises, or null when none does. */
        fun of(machines: List<dev.omalauncher.assist.MacMachine>): RemoteShelf? =
            machines.firstNotNullOfOrNull { m -> dev.omalauncher.canvas.advertisedService(m.config.url, m.config.key)?.let { RemoteShelf(it, m.config.key) } }
    }
}

/**
 * What one call of the canvas words needs from [shelf], read before the call and written after it, so the window's own
 * thread never waits on the network: the templates' names, LAYOUT.md, and the template the words name.
 */
class PreparedShelf private constructor(private val shelf: CanvasShelf, private val names: List<String>, private val md: String, private val named: Map<String, String?>) : CanvasShelf {
    private val writes = ArrayList<() -> Unit>()

    override fun template(name: String) = if (name.lowercase() in named) named[name.lowercase()] else shelf.template(name)
    override fun keepTemplate(name: String, text: String) { writes += { shelf.keepTemplate(name, text) } }
    override fun templates() = names
    override fun layout() = md
    override fun remember(line: String) { writes += { shelf.remember(line) } }

    /** The writes the call made, now; why the first failed, or null. */
    fun flush(): String? {
        for (w in writes) runCatching(w).onFailure { return it.message ?: "the shelf couldn't keep it" }
        return null
    }

    companion object {
        private val WORDS = setOf("templates", "template-save", "template-apply", "layout", "remember")

        /**
         * The shelf [shelf] gives, read for [words]; null when they aren't shelf words, and then the shelf isn't even found
         * (round-2 review: every canvas word asked the runners where the service is).
         */
        fun of(shelf: () -> CanvasShelf, words: List<String>): PreparedShelf? {
            if (words.firstOrNull() !in WORDS) return null
            return of(shelf(), words)
        }

        /** [shelf] read for [words]. */
        fun of(shelf: CanvasShelf, words: List<String>): PreparedShelf? {
            if (words.firstOrNull() !in WORDS) return null
            // --replace is template-save's own word; anywhere else it stays, so the name read here is the one the words use.
            val rest = words.drop(1).filter { it != "--replace" || words[0] != "template-save" }.takeWhile { it != "--page" && it != "--onto" }
            val name = rest.joinToString(" ").trim().replace(Regex("\\s+"), " ")
            val named = if (name.isNotEmpty() && TEMPLATE_NAME.matches(name) && ".." !in name) mapOf(name.lowercase() to shelf.template(name)) else emptyMap()
            return PreparedShelf(shelf, shelf.templates(), shelf.layout(), named)
        }
    }
}

/** A template text as the service takes it from another desktop: JSON with a name that is one, and its items. */
internal fun templateTextOk(name: String, text: String): Boolean = runCatching {
    val o = JSONObject(text)
    o.getString("name").equals(name, ignoreCase = true) && o.get("items") is JSONArray && text.length <= 64 * 1024
}.getOrDefault(false)
