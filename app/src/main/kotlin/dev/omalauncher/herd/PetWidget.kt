package dev.omalauncher.herd

import android.content.Context
import android.graphics.BitmapFactory
import dev.omalauncher.news.NewsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * A skin the herd pet wears, as the page keeps it (pet.html's pick()): its sheet on a Mac's reporter, the size of one
 * frame, a row of the sheet per facing, which column it stands in and which it walks through. Crono's sheet instead
 * names each facing's cells in [poses].
 */
data class PetSkin(
    val name: String,
    val url: String,
    val frame: Pair<Int, Int>,
    val rows: List<String>,
    val stand: Int,
    val walk: List<Int>,
    val poses: Map<String, List<Int>>,
) {
    /** The sheet's cell (column, row) for [facing] at walk step [step]; standing is step -1. */
    fun cell(
        facing: String,
        step: Int,
        cols: Int,
    ): Pair<Int, Int> {
        poses[facing]?.takeIf { it.isNotEmpty() }?.let { p ->
            val i = if (step < 0) p[0] else p[1 + step % maxOf(1, p.size - 1)]
            return (i % cols) to (i / cols)
        }
        val row = maxOf(0, rows.indexOf(facing))
        val col = if (step < 0 || walk.isEmpty()) stand else walk[step % walk.size]
        return col to row
    }

    fun json(): JSONObject =
        JSONObject().put("name", name).put("url", url).put("frame", JSONArray(listOf(frame.first, frame.second)))
            .put("rows", JSONArray(rows)).put("stand", stand).put("walk", JSONArray(walk))
            .put("poses", JSONObject().also { o -> poses.forEach { (k, v) -> o.put(k, JSONArray(v)) } })

    companion object {
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")

        /** Only a herd reporter's skin sheets: a Tailscale address or a herd host, its port, its skins path. */
        private val URL_OK = Regex("""http://(100\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-z0-9-]+(\.[a-z0-9-]+)*):$HERD_PORT/v1/skins/[a-z0-9-]+/sheet\.png(\?v=[A-Za-z0-9._-]{1,40})?""")

        fun parse(o: JSONObject?): PetSkin? {
            o ?: return null
            val name = o.optString("name").takeIf { NAME.matches(it) } ?: return null
            val url = o.optString("url").takeIf { URL_OK.matches(it) } ?: return null
            val f = o.optJSONArray("frame")
            val fw = f?.optInt(0) ?: 0
            val fh = f?.optInt(1, fw) ?: 0
            if (fw !in 8..128 || fh !in 8..128) return null
            fun ints(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }.filter { it in 0..255 }.take(32)
            val rows = (0 until (o.optJSONArray("rows")?.length() ?: 0)).map { o.optJSONArray("rows")!!.optString(it) }.take(8)
            val poses = HashMap<String, List<Int>>()
            o.optJSONObject("poses")?.let { p -> for (k in p.keys()) if (k in FACINGS) poses[k] = ints(p.optJSONArray(k)) }
            return PetSkin(name, url, fw to fh, rows.ifEmpty { listOf("down") }, o.optInt("stand", 0).coerceIn(0, 255), ints(o.optJSONArray("walk")), poses)
        }

        val FACINGS = setOf("down", "up", "left", "right")
    }
}

/**
 * The pet for the home widgets (Boss, 2026-10-21: "a pet home widget so i can stick the pet in there", and a steps
 * widget the pet walks): whether it is out ([HerdSheep.shown]) or home, and the skin it wears as Owl sprites, in the
 * guest's ~/.cache/soma-pet/pet.json. Owl's `sprite()` draws pixel art crisp at any size, where a scaled picture
 * would blur, so the frames go as rows of letters with one palette for all of them.
 */
object PetWidget {
    private const val PREFS = "herd"
    private const val SKIN = "skin"

    /** Letters for the palette, as many colors as a frame can have. */
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private fun dir(context: Context) = File(NewsStore.home(context), ".cache/soma-pet")

    /** The page says what it wears, at load and when he picks another look; the sheet is fetched once. */
    fun wear(
        context: Context,
        skin: PetSkin?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = skin?.json()?.toString()
        if (json == prefs.getString(SKIN, null) && File(dir(context), "pet.json").exists()) return
        prefs.edit().putString(SKIN, json).apply()
        write(context)
    }

    fun skin(context: Context): PetSkin? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SKIN, null)
            ?.let { runCatching { PetSkin.parse(JSONObject(it)) }.getOrNull() }

    /** Writes pet.json, fetching the skin's sheet first if it isn't here yet. Off the main thread. */
    fun write(context: Context) {
        val app = context.applicationContext
        thread(name = "oma-pet-widget", isDaemon = true) {
            runCatching {
                val skin = skin(app)
                val d = dir(app).apply { mkdirs() }
                val out = JSONObject().put("out", HerdSheep.shown).put("updated", System.currentTimeMillis() / 1000)
                val sheet = skin?.let { sheet(d, it) }
                if (skin != null && sheet != null) {
                    out.put("name", skin.name.replace('-', ' ').uppercase())
                    out.put("skin", skin.name)
                    out.put("frame", JSONArray(listOf(skin.frame.first, skin.frame.second)))
                    out.put("sprites", sprites(sheet, skin))
                } else {
                    out.put("name", if (skin == null) "SHEEP" else skin.name.uppercase())
                }
                val tmp = File(d, "pet.json.tmp")
                tmp.writeText(out.toString())
                tmp.renameTo(File(d, "pet.json"))
            }
        }
    }

    /** The skin's sheet, from the reporter that serves it, kept beside pet.json by name. */
    private fun sheet(
        d: File,
        skin: PetSkin,
    ): File? {
        val f = File(d, "sheet-${skin.name}.png")
        if (f.length() > 0) return f
        return runCatching {
            val c = URL(skin.url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            try {
                if (c.responseCode != 200) return null
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size > 2_000_000) return null
                val tmp = File(d, f.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                f
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /**
     * The frames the widgets draw: standing facing you, walking right (and the rest of the facings for later), as
     * `{palette: {a: "#rrggbb"}, down: {stand: [rows], walk: [[rows]]}, right: …}`.
     */
    private fun sprites(
        file: File,
        skin: PetSkin,
    ): JSONObject {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return JSONObject()
        val (fw, fh) = skin.frame
        val cols = maxOf(1, bmp.width / fw)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val palette = LinkedHashMap<Int, Char>()
        val o = JSONObject()
        for (facing in listOf("down", "right", "left", "up")) {
            val steps = if (skin.poses[facing] != null) maxOf(1, (skin.poses[facing]!!.size - 1)) else maxOf(1, skin.walk.size)
            fun rows(step: Int): JSONArray {
                val (c, r) = skin.cell(facing, step, cols)
                return JSONArray(spriteRows(pixels, bmp.width, bmp.height, c * fw, r * fh, fw, fh, palette))
            }
            o.put(facing, JSONObject().put("stand", rows(-1)).put("walk", JSONArray((0 until steps).map { rows(it) })))
        }
        o.put("palette", JSONObject().also { p -> palette.forEach { (argb, ch) -> p.put(ch.toString(), "#%06x".format(argb and 0xffffff)) } })
        return o
    }

    /**
     * One frame of a sheet as sprite rows: a letter per opaque pixel, '.' for clear ones, with colors added to
     * [palette] as they're met. Past the letters there are, a color takes the nearest one already named.
     */
    fun spriteRows(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        palette: MutableMap<Int, Char>,
    ): List<String> =
        (y0 until y0 + h).map { y ->
            buildString {
                for (x in x0 until x0 + w) {
                    val p = if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0
                    if ((p ushr 24) < 128) {
                        append('.')
                        continue
                    }
                    val rgb = p or (0xff shl 24)
                    val ch =
                        palette[rgb] ?: if (palette.size < LETTERS.length) {
                            LETTERS[palette.size].also { palette[rgb] = it }
                        } else {
                            palette.minBy { (c, _) -> distance(c, rgb) }.value
                        }
                    append(ch)
                }
            }
        }

    private fun distance(
        a: Int,
        b: Int,
    ): Int {
        val dr = (a shr 16 and 0xff) - (b shr 16 and 0xff)
        val dg = (a shr 8 and 0xff) - (b shr 8 and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return dr * dr + dg * dg + db * db
    }
}
