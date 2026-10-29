package dev.omalauncher.connections

import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import org.junit.Test
import org.junit.Assert.*

class ConnectionAdbProbeTest {
    private fun probe(serial: String = "FICTIONAL123", refused: Boolean = false): ConnectionCheck {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val worker = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream(); val output = socket.getOutputStream()
                    fun command(): String {
                        fun exact(n: Int): ByteArray { val a = ByteArray(n); var at = 0; while (at < n) { val read = input.read(a, at, n - at); check(read > 0); at += read }; return a }
                        return exact(exact(4).toString(Charsets.US_ASCII).toInt(16)).toString(Charsets.UTF_8)
                    }
                    assertEquals("host:transport:127.0.0.1:37001", command())
                    if (refused) {
                        val message = "device unauthorized"; output.write(("FAIL%04x".format(message.length) + message).toByteArray()); output.flush()
                    } else {
                        output.write("OKAY".toByteArray()); output.flush()
                        assertEquals("shell:getprop ro.serialno", command())
                        output.write("OKAY$serial\n".toByteArray()); output.flush()
                    }
                }
            }
            val result = connectionAdbProbe(37001, "FICTIONAL123", server.localPort)
            worker.join(4000); assertFalse(worker.isAlive)
            return result
        }
    }
    @Test fun existingTransportMustAnswerShellWithTheExpectedDeviceIdentity() {
        assertEquals("pass", probe().result)
        assertEquals("unverified", probe("OTHER_DEVICE").result)
        assertEquals("auth", probe(refused = true).result)
    }
}
