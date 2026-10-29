package dev.omalauncher.connections

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.File

/** Bounded read-only keeper protocol check. Kept command arguments/output never enter diagnostics. */
internal fun connectionKeeperProbe(home: File): ConnectionCheck {
    val socket = LocalSocket()
    return try {
        socket.soTimeout = 2000
        socket.connect(LocalSocketAddress(File(home, ".cache/oma-keep/keep.sock").path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.outputStream.write("{\"op\":\"list\"}\n".toByteArray())
        socket.outputStream.flush()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= 262_144) {
            val byte = socket.inputStream.read()
            require(byte >= 0) { "Incomplete keeper response" }
            if (byte == 10) break
            bytes.write(byte)
        }
        require(bytes.size() <= 262_144) { "Oversized keeper response" }
        val result = JSONObject(bytes.toString("UTF-8"))
        require(result.optBoolean("ok") && result.has("kept")) { "Keeper identity/schema unverified" }
        val kept = result.getJSONArray("kept")
        var active = 0
        for (i in 0 until kept.length()) {
            val row = kept.getJSONObject(i)
            require(row.has("running") && row.get("running") is Boolean)
            if (row.getBoolean("running")) active++
        }
        ConnectionCheck("phone_keeper_protocol", "phone", result = "pass", fields = mapOf("kept_active" to active.toString()))
    } catch (error: Exception) {
        ConnectionCheck("phone_keeper_protocol", "phone", result = "unverified", fields = mapOf("error_type" to error.javaClass.simpleName))
    } finally { runCatching { socket.close() } }
}
