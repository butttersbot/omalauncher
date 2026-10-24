package dev.omalauncher.notes.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * /v1/notes on tailshare-serve, over the tailnet. Only Boss's own devices get through (the server asks tailscale who
 * is calling), so there is no token here. Network: off the main thread.
 */
class HttpLink(private val base: String = MAC_PRO) : Link {
    override fun list(): List<Remote>? {
        val (code, body, _) = call("GET", "$base/v1/notes") ?: return null
        return if (code == 200) Wire.list(body)?.second else null
    }

    override fun get(id: String): Pair<String, String>? {
        val (code, body, etag) = call("GET", "$base/v1/notes/${Wire.path(id)}") ?: return null
        return if (code == 200 && etag != null) body to etag.trim('"') else null
    }

    override fun put(
        id: String,
        text: String,
        sha: String?,
    ): Link.Put {
        val (code, body, _) = call("PUT", "$base/v1/notes/${Wire.path(id)}", text, sha) ?: return Link.Put.Failed
        return when {
            code == 200 || code == 201 -> Wire.saved(body)?.let { Link.Put.Saved(it) } ?: Link.Put.Failed
            code == 409 && sha == null -> Link.Put.Exists
            code == 409 || code == 412 -> Wire.conflict(body)?.let { (s, t) -> Link.Put.Conflict(s, t) } ?: Link.Put.Failed
            else -> Link.Put.Failed
        }
    }

    override fun delete(
        id: String,
        sha: String?,
    ): Boolean {
        val (code, _, _) = call("DELETE", "$base/v1/notes/${Wire.path(id)}", null, sha) ?: return false
        return code == 200 || code == 204 || code == 404
    }

    private fun call(
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
    ): Triple<Int, String, String?>? =
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 15_000
            ifMatch?.let { c.setRequestProperty("If-Match", it) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/markdown; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Triple(code, text, c.getHeaderField("ETag")).also { c.disconnect() }
        }.getOrNull()

    companion object {
        /** tailshare-serve on the Mac Pro, which keeps ~/Notes. */
        const val MAC_PRO = "http://100.120.219.46:8480"
    }
}
