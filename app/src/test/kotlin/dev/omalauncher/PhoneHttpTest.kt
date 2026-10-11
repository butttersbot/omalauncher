package dev.omalauncher

import dev.omalauncher.phone.PhoneHttp
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PhoneHttpTest {
    private fun request(body: String) = "POST /phone HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".byteInputStream()
    @Test fun readsUnicodeByByteLength() {
        val body = "{\"command\":\"type\",\"text\":\"こんにちは\"}"
        assertEquals(body, PhoneHttp.readBody(request(body)))
    }
    @Test fun rejectsOversizedAndTruncatedRequests() {
        for (value in listOf("POST /phone HTTP/1.1\r\nContent-Length: 65537\r\n\r\n", "POST /phone HTTP/1.1\r\nContent-Length: 20\r\n\r\n{}", "GET /phone HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}")) {
            assertThrows(IllegalArgumentException::class.java) { PhoneHttp.readBody(value.byteInputStream()) }
        }
    }
    @Test fun rejectsChunkedAndDuplicateLengthHeaders() {
        for (header in listOf("Transfer-Encoding: chunked\r\nContent-Length: 2", "Content-Length: 2\r\nContent-Length: 2")) {
            assertThrows(IllegalArgumentException::class.java) { PhoneHttp.readBody("POST /phone HTTP/1.1\r\n$header\r\n\r\n{}".byteInputStream()) }
        }
    }
    @Test fun framesReplyWithUtf8ByteLength() {
        val output = ByteArrayOutputStream()
        PhoneHttp.reply(output, "こんにちは")
        val response = output.toString("UTF-8")
        assertTrue(response.contains("Content-Length: 15\r\n"))
        assertTrue(response.endsWith("\r\n\r\nこんにちは"))
    }
}
