package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.widgets.runtime.CommandGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * Setting up the Mac Pro (Soma Canvas slice 11): the runner's own key registers its threads' tokens and nothing else,
 * from its own machine only; the key is made without ever being shown; the service finds Tailscale where this Mac has
 * it; its login item carries the car's public address.
 */
class RunnerKeyTest {
    private val home = Files.createTempDirectory("soma-runner-key").toFile().also { System.setProperty("soma.home", it.path) }
    private val foldKey = "fold-key-" + "x".repeat(40)
    private val runnerKey = "soma-runner-" + "r".repeat(64)
    private val keys = File(home, "keys.json").apply {
        writeText(JSONObject().put("devices", JSONArray(listOf(JSONObject().put("name", "fold").put("digest", keyDigest(foldKey))))).toString())
    }
    private val runnerKeys = File(home, "runner-keys.json").apply {
        writeText(JSONObject().put("runners", JSONArray(listOf(JSONObject().put("name", "macpro").put("digest", keyDigest(runnerKey))))).toString())
    }
    @Volatile private var calling = "macpro"
    private val source = object : ModuleSource {
        override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
        override fun asks() = emptyList<ModuleAsk>()
        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
    }
    // Whois: the loopback caller is whichever machine the test says.
    private val service = SomaService(home, PairedDevices(keys, runnerKeys) { calling }, source, CommandGate(File(home, "approved.json"), "macpro"), "macpro")
    private val server = service.open("127.0.0.1", 0)
    private val base = "http://127.0.0.1:${server.address.port}"

    private fun call(method: String, path: String, key: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        return code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    @Test fun theRunnersKeyRegistersItsThreadsTokensAndNothingElse() {
        assertEquals(200, call("GET", "/health", runnerKey).first)
        val token = ThreadTokens.PREFIX + "t".repeat(48)
        val reg = JSONObject().put("digest", keyDigest(token)).put("thread", "t1").put("screens", JSONArray(listOf("car")))
        assertEquals(200, call("POST", "/tokens", runnerKey, reg).first)
        // The token it registered works for its thread.
        assertTrue(call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("place", "needs", "card")))).second.getBoolean("ok"))
        // Nothing else for the runner's key: screens, canvas words, approvals, widgets, the car's pairing, Grok, sync, the shelf.
        for ((m, p) in listOf("GET" to "/screens", "POST" to "/screens/car/canvas", "GET" to "/approvals", "POST" to "/approvals",
                "POST" to "/widgets", "POST" to "/car/pairing", "GET" to "/car", "POST" to "/grok/consent", "POST" to "/screens/desk-x/sync", "GET" to "/shelf"))
            assertEquals("$m $p", 403, call(m, p, runnerKey, JSONObject().put("words", JSONArray(listOf("board")))).first)
        // A device can't revoke the runner's token; the runner can.
        calling = "fold"
        assertEquals(404, call("DELETE", "/tokens/${keyDigest(token)}", foldKey).first)
        calling = "macpro"
        assertEquals(200, call("DELETE", "/tokens/${keyDigest(token)}", runnerKey).first)
        // From another machine the runner's key is nothing.
        calling = "macbook"
        assertEquals(401, call("GET", "/health", runnerKey).first)
        // And a device's key is never a runner's (the tables are apart).
        calling = "fold"
        assertEquals(200, call("GET", "/screens", foldKey).first)
        server.stop(0)
    }

    @Test fun aNewKeyIsKeptInTheKeychainAndReplacesTheOldOneWithoutBeingShown() {
        val dir = Files.createTempDirectory("soma-mint").toFile()
        var kept: String? = null
        val said = SomaService.mintRunnerKey(dir, "macpro") { kept = it; true }
        val key = kept!!
        assertFalse(said, said.contains(key))
        val table = JSONObject(File(dir, "runner-keys.json").readText()).getJSONArray("runners")
        assertEquals(1, table.length())
        assertEquals(keyDigest(key), table.getJSONObject(0).getString("digest"))
        assertFalse(File(dir, "runner-keys.json").readText().contains(key))
        // Again: the old one goes.
        SomaService.mintRunnerKey(dir, "macpro") { kept = it; true }
        val again = JSONObject(File(dir, "runner-keys.json").readText()).getJSONArray("runners")
        assertEquals(listOf(keyDigest(kept!!)), (0 until again.length()).map { again.getJSONObject(it).getString("digest") })
        // The Keychain refused: nothing changes. Off the tailnet: nothing is made.
        assertTrue(SomaService.mintRunnerKey(dir, "macpro") { false }.contains("nothing was changed"))
        assertEquals(again.toString(), JSONObject(File(dir, "runner-keys.json").readText()).getJSONArray("runners").toString())
        assertTrue(SomaService.mintRunnerKey(dir, null) { true }.contains("isn't on the tailnet"))
        server.stop(0)
    }

    @Test fun tailscaleIsFoundWhereThisMacHasItAndTheLoginItemCarriesThePublicAddress() {
        assertEquals("/opt/homebrew/bin/tailscale", TailscaleWhois.find({ null }) { it == "/opt/homebrew/bin/tailscale" })
        // None found: the one on the login item's PATH.
        assertEquals("tailscale", TailscaleWhois.find({ null }) { false })
        assertEquals("/Applications/Tailscale.app/Contents/MacOS/Tailscale", TailscaleWhois.find({ null }) { true })
        assertEquals("/x/tailscale", TailscaleWhois.find({ if (it == "OMA_RUNNER_TAILSCALE") "/x/tailscale" else null }) { true })
        val plist = SomaService.launchAgent("/Users/jackkim/Applications/Soma.app/Contents/MacOS/Soma", mapOf("SOMA_PUBLIC_URL" to "https://jacks-mac-pro.tail56d7f9.ts.net"))
        assertTrue(plist.contains("<key>SOMA_PUBLIC_URL</key><string>https://jacks-mac-pro.tail56d7f9.ts.net</string>"))
        assertTrue(plist.contains("<key>PATH</key><string>/opt/homebrew/bin:"))
        assertTrue(SomaService.launchAgent("x", mapOf("A" to "<&>")).contains("<string>&lt;&amp;&gt;</string>"))
        server.stop(0)
    }
}
