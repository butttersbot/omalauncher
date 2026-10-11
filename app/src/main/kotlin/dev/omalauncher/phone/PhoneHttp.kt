package dev.omalauncher.phone

import java.io.InputStream
import java.io.OutputStream

/** Minimal bounded HTTP framing for curl over our private Unix socket. */
object PhoneHttp {
    fun readBody(input: InputStream): String {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val value = input.read()
            require(value >= 0 && header.length < 8192) { "Invalid or oversized request headers" }
            header.append(value.toChar())
        }
        val lines = header.toString().split("\r\n")
        require(lines.first() == "POST /phone HTTP/1.1") { "Only POST /phone is supported" }
        require(lines.none { it.startsWith("Transfer-Encoding:", true) }) { "Chunked requests not supported" }
        val lengths = lines.filter { it.startsWith("Content-Length:", true) }
        require(lengths.size == 1) { "Content-Length required" }
        val size = lengths.single().substringAfter(':').trim().toInt()
        require(size in 1..65_536) { "Invalid request size" }
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            require(count > 0) { "Incomplete request" }
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }
    fun reply(output: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
