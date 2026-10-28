package dev.omalauncher.runner

import java.io.OutputStream
import org.json.JSONArray

/** The authenticated TCP adapter selects this writer after protocol validates the stream route/cursor. */
fun writeRunnerStreamHeaders(out: OutputStream) {
    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
    out.flush()
}
fun writeRunnerStreamPage(out: OutputStream, events: JSONArray, after: Long): Long {
    var cursor = after
    for (n in 0 until events.length()) {
        val event = events.getJSONObject(n)
        val id = event.getLong("id")
        if (id <= cursor) continue
        out.write("id: $id\nevent: ${event.optString("type")}\ndata: $event\n\n".toByteArray(Charsets.UTF_8))
        cursor = id
    }
    out.write(": keep-alive\n\n".toByteArray()); out.flush()
    return cursor
}
