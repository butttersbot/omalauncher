package dev.omalauncher.connections

import java.net.InetSocketAddress
import java.net.Socket

/** Queries only an already running local ADB server/transport. Never starts ADB, pairs, scans, or opens a display. */
fun connectionAdbProbe(port: Int, expectedSerial: String, serverPort: Int = 5037): ConnectionCheck {
    require(port in 1..65535 && serverPort in 1..65535 && expectedSerial.matches(Regex("[A-Za-z0-9._-]{1,100}")))
    val deadline = System.nanoTime() + 3_000_000_000L
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", serverPort), 1000)
            socket.soTimeout = 1500
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            fun exact(size: Int): ByteArray {
                require(System.nanoTime() < deadline)
                val bytes = ByteArray(size); var read = 0
                while (read < size) {
                    require(System.nanoTime() < deadline)
                    val n = input.read(bytes, read, size - read); require(n > 0); read += n
                }
                return bytes
            }
            fun command(value: String): Boolean {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.write("%04x".format(bytes.size).toByteArray(Charsets.US_ASCII)); output.write(bytes); output.flush()
                return when (exact(4).toString(Charsets.US_ASCII)) {
                    "OKAY" -> true
                    "FAIL" -> {
                        val size = exact(4).toString(Charsets.US_ASCII).toInt(16); require(size in 0..4096)
                        val reason = exact(size).toString(Charsets.UTF_8)
                        if (reason.contains("unauthorized", ignoreCase = true)) throw SecurityException("ADB key unauthorized")
                        false
                    }
                    else -> error("Invalid ADB server protocol")
                }
            }
            require(command("host:transport:127.0.0.1:$port")) { "ADB transport not connected" }
            require(command("shell:getprop ro.serialno")) { "ADB shell unavailable" }
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 128) {
                require(System.nanoTime() < deadline)
                val n = input.read(); if (n < 0) break; bytes.write(n)
            }
            require(bytes.size() <= 128 && bytes.toString("UTF-8").trim() == expectedSerial) { "ADB identity mismatch" }
            ConnectionCheck("adb_transport_shell_identity", "phone", result = "pass", fields = mapOf("device_identity" to "matched"))
        }
    } catch (_: SecurityException) {
        ConnectionCheck("adb_transport_shell_identity", "phone", result = "auth", fields = mapOf("reason" to "existing_adb_key_unauthorized"))
    } catch (error: Exception) {
        ConnectionCheck("adb_transport_shell_identity", "phone", result = "unverified", fields = mapOf("error_type" to error.javaClass.simpleName))
    }
}
