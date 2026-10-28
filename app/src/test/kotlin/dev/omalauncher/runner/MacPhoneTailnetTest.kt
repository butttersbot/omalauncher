package dev.omalauncher.runner

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Production HTTP client against the agreed helper JSON; loopback and fictional keys only. */
class MacPhoneTailnetTest {
    @Test fun agreedHelperPathsPairingBodyIdentityRefusalAndCache() {
        val directory = Files.createTempDirectory("phone-helper-client").toFile()
        val seen = CopyOnWriteArrayList<String>()
        val uploads = CopyOnWriteArrayList<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/phone-runner/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            seen += "$path?$query"
            var status = 200
            val body = if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-key") {
                status = 401; JSONObject().put("ok", false)
            } else when (path) {
                "/phone-runner/tailnet" -> JSONObject("""{"ok":true,"self":{"name":"galaxy-z-fold8","addresses":["100.80.1.2"],"os":"android","online":true},"runner":"jacks-mac-pro","devices":[{"name":"jacks-mac-pro","addresses":["100.80.1.3"]},{"name":"other/invalid"}]}""")
                "/phone-runner/whois" -> if (query == "address=100.80.1.3") JSONObject().put("ok", true).put("device", "jacks-mac-pro").put("owner", true)
                    else { status = 404; JSONObject().put("ok", false).put("owner", false) }
                "/phone-runner/taildrop" -> {
                    uploads += JSONObject(exchange.requestBody.bufferedReader().readText())
                    JSONObject().put("ok", true)
                }
                else -> { status = 404; JSONObject().put("ok", false) }
            }
            val bytes = body.toString().toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            directory.resolve("mac-runner.json").writeText(JSONObject().put("url", "http://127.0.0.1:${server.address.port}").put("key", "fixture-key").toString())
            val client = MacPhoneTailnet(directory)
            assertEquals(listOf("jacks-mac-pro"), client.devices())
            assertEquals(RunnerPeer("jacks-mac-pro", true), client.whois("100.80.1.3"))
            assertEquals(RunnerPeer("jacks-mac-pro", true), client.whois("100.80.1.3"))
            assertEquals(1, seen.count { it == "/phone-runner/whois?address=100.80.1.3" })
            assertNull(client.whois("100.80.1.9"))
            val pairing = JSONObject().put("url", "http://100.80.1.2:8478").put("key", "fictional-device-key").put("name", "Fold").put("runner", "galaxy-z-fold8")
            client.taildrop("jacks-mac-pro", "soma-runner-galaxy-z-fold8.json", pairing.toString())
            assertEquals("jacks-mac-pro", uploads.single().getString("device"))
            assertEquals("soma-runner-galaxy-z-fold8.json", uploads.single().getString("name"))
            assertEquals(pairing.toString(), uploads.single().getString("content"))
            server.stop(0)
            // A fresh unknown caller remains refused when the helper is unavailable.
            assertNull(client.whois("100.80.1.10"))
        } finally { server.stop(0); directory.deleteRecursively() }
    }
}
