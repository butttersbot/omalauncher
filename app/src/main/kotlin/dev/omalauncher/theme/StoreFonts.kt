package dev.omalauncher.theme

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/*
 * The fonts every theme's voices end with, from Soma Canvas's font store (Boss, 2026-10-29: Canvas is the one store of
 * theme fonts, so the phone, the Macs and MG1 draw the same). The theme's own faces are the packs' files, bundled as
 * before. After them every voice falls back to Noto Sans Symbols 2 (bundled, assets/fonts), then Noto Color Emoji and
 * Noto Sans CJK JP, which are not bundled (25 MB and 16 MB, Boss, 19:53): each device fetches them once from Canvas
 * (/api/fonts/files/SHA.EXT, no token, immutable) and keeps them; until then, or offline, the device's own fonts draw
 * emoji and CJK. assets/fonts/store.json pins them by sha256 (tools/design-system/build.py --app-fonts writes it).
 */
internal object StoreFonts {
    const val PINS = "fonts/store.json"
    const val DIR = "store-fonts"

    data class Pin(val role: String, val family: String, val file: String, val sha256: String, val size: Long, val asset: String?)

    /** The bundled faces and the fetched ones, in stack order. */
    fun pins(json: String): Pair<List<Pin>, List<Pin>> {
        val o = JSONObject(json)
        fun list(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).run {
                    Pin(getString("role"), getString("family"), getString("file"), getString("sha256"), getLong("size"), optString("asset").ifEmpty { null })
                }
            }
        }.orEmpty()
        return list("bundled") to list("fetched")
    }

    /** A fetched face already kept on this device, or null. Only whole, checked files are ever kept under their name. */
    fun cached(dir: File, pin: Pin): File? = File(dir, safe(pin.file)).takeIf { it.isFile && it.length() == pin.size }

    /**
     * Fetches the pinned faces this device doesn't keep yet from [origin] (Soma Canvas), each checked against its pin
     * (size and sha256) before it is kept; a face that doesn't match is thrown away. Returns the ones newly kept.
     * Blocking; best effort: an unreachable Canvas leaves the device's own fonts drawing emoji and CJK.
     */
    fun fetchMissing(dir: File, origin: String, pins: List<Pin>, open: (String) -> InputStream = ::download): List<Pin> {
        dir.mkdirs()
        return pins.filter { pin ->
            cached(dir, pin) == null && runCatching {
                val part = File(dir, safe(pin.file) + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                open("${origin.trimEnd('/')}/api/fonts/files/${pin.file}").use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            check(bytes <= pin.size) { "longer than pinned" }
                            digest.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (bytes != pin.size || sha != pin.sha256) { part.delete(); false } else part.renameTo(File(dir, safe(pin.file)))
            }.getOrElse { File(dir, safe(pin.file) + ".part").delete(); false }
        }
    }

    /** A pinned name as a file name here, never a path. */
    private fun safe(name: String) = name.filter { it.isLetterOrDigit() || it == '.' }.take(80)

    private fun download(url: String): InputStream =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 30000
            check(responseCode == 200) { "HTTP $responseCode" }
        }.inputStream
}
