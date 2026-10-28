package dev.omalauncher.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.PackEntry
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.PackSchema
import dev.omalauncher.theme.pack.PackSource
import dev.omalauncher.theme.pack.readCapped
import dev.omalauncher.ui.ActivePack
import dev.omalauncher.ui.PlatformFonts
import java.io.InputStream

/**
 * The phone's looks on the desktop: the same packs, shipped in Soma's jar under `packs/` (from the phone app's own
 * assets at build time), and the same symbol font. The look in use is remembered in settings.json.
 */
object Looks {
    private val loader = Looks::class.java.classLoader

    /** A pack in the jar under `packs/<id>`. */
    private class JarPackSource(private val dir: String) : PackSource {
        override fun stat(path: String): PackEntry {
            if (PackSchema.pathProblem(path) != null) return PackEntry.Outside
            val bytes = open(path)?.use { it.readBytes() } ?: return PackEntry.Missing
            return PackEntry.File(bytes.size.toLong())
        }

        override fun open(path: String): InputStream? = if (PackSchema.pathProblem(path) != null) null else loader.getResourceAsStream("$dir/$path")
    }

    /** The packs in the jar, by id. */
    val ids: List<String> by lazy {
        loader.getResourceAsStream("packs/index.txt")?.bufferedReader()?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("bujo", "city-pop", "console", "custard", "dial-up", "instrument", "one-bit", "riso", "tiny-magazine")
    }

    private fun source(id: String): PackSource? = if (loader.getResource("packs/$id/pack.toml") != null || loader.getResource("packs/$id/pack.json") != null) JarPackSource("packs/$id") else null

    /** The symbol font, from the jar. */
    fun fonts() {
        loader.getResourceAsStream("fonts/nerd_symbols.ttf")?.use { PlatformFonts.nerdSymbols = FontFamily(Font("nerd_symbols", it.readBytes())) }
    }

    /** A look as the dashboard's overview offers it (desktop slice 5c): its name, and its page, card, ink and accent colors. */
    class Swatch(val id: String, val name: String, val colors: List<Color>)

    /** Every look in the jar as a [Swatch], read once: Console for one that can't be read. */
    val swatches: List<Swatch> by lazy {
        ids.map { id ->
            val pick = PackRegistry.choose(id, ::source)
            val pack = pick.load?.pack?.takeIf { !pick.isConsole } ?: ConsolePack.pack
            Swatch(pick.id, pack.name, with(pack.palette) { listOf(background, surface, foreground, accent) }.map { Color(it.argb) })
        }.distinctBy { it.id }
    }

    /** Uses the pack [id] and remembers it: both of Soma's windows restyle at once, as from the tray's Look menu. */
    fun pick(id: String) {
        use(id)
        SomaHome.write(SomaHome.settings, SomaHome.read(SomaHome.settings).put("pack", id))
    }

    /** Uses the pack [id] (Console when it isn't there), with its own typefaces. */
    fun use(id: String?) {
        val pick: PackPick = PackRegistry.choose(id, ::source)
        ActivePack.choose(pick)
        val load = pick.load?.takeIf { !pick.isConsole } ?: return
        val source = source(pick.id) ?: return
        val type = load.pack.type
        ActivePack.fonts = listOf(type.display, type.body, type.hand, type.mono).map { it.file }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { path -> readCapped(source, path, 8L * 1024 * 1024)?.let { bytes -> path to FontFamily(Font(pick.id + "/" + path, bytes)) } }.toMap()
    }
}
