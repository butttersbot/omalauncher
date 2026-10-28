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
        var missingRoute = false
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
                "/phone-runner/whois" -> if (missingRoute) { status = 404; JSONObject().put("error", "not found") } else if (query == "address=100.80.1.3") JSONObject().put("ok", true).put("device", "jacks-mac-pro").put("owner", true)
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
            var wall = 1_000_000L
            var nano = 0L
            val client = MacPhoneTailnet(directory, { wall }, { nano })
            assertEquals(listOf("jacks-mac-pro"), client.devices())
            assertEquals(RunnerPeer("jacks-mac-pro", true), client.whois("100.80.1.3"))
            assertEquals(RunnerPeer("jacks-mac-pro", true), client.whois("100.80.1.3"))
            assertEquals(1, seen.count { it == "/phone-runner/whois?address=100.80.1.3" })
            assertEquals(RunnerIdentityLookup.Rejected, client.lookup("100.80.1.9"))
            val proof = client.confirmDevice("jacks-mac-pro")!!
            assertEquals(RunnerCachedIdentity("100.80.1.3", wall), proof)
            wall += 30_000; nano += 30_000_000_000L
            assertEquals(proof.at, (client.lookup(proof.address) as RunnerIdentityLookup.Confirmed).at)
            val pairing = JSONObject().put("url", "http://100.80.1.2:8478").put("key", "fictional-device-key").put("name", "Fold").put("runner", "galaxy-z-fold8")
            client.taildrop("jacks-mac-pro", "soma-runner-galaxy-z-fold8.json", pairing.toString())
            assertEquals("jacks-mac-pro", uploads.single().getString("device"))
            assertEquals("soma-runner-galaxy-z-fold8.json", uploads.single().getString("name"))
            assertEquals(pairing.toString(), uploads.single().getString("content"))
            missingRoute = true
            wall += 60_001; nano += 60_001_000_000L
            assertEquals(RunnerIdentityLookup.Unavailable, client.lookup(proof.address))
            assertTrue(runnerIdentityDecision("jacks-mac-pro", proof.address, proof, client.lookup(proof.address), wall).accepted)
            server.stop(0)
            // A fresh unknown caller remains refused when the helper is unavailable.
            assertEquals(RunnerIdentityLookup.Unavailable, client.lookup("100.80.1.10"))
            wall += 60_001; nano += 60_001_000_000L
            val offline = client.lookup(proof.address)
            assertEquals(RunnerIdentityLookup.Unavailable, offline)
            assertTrue(runnerIdentityDecision("jacks-mac-pro", proof.address, proof, offline, wall).accepted)
            assertFalse(runnerIdentityDecision("jacks-mac-pro", proof.address, proof, offline, proof.at + RUNNER_OFFLINE_IDENTITY_MS + 1).accepted)
        } finally { server.stop(0); directory.deleteRecursively() }
    }
}
