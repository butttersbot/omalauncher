package dev.omalauncher.desktop

import com.github.javakeyring.Keyring
import dev.omalauncher.assist.MacMachine
import dev.omalauncher.assist.MacRunnerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The runners this machine's Soma can reach, and their keys. A key is never kept in a file here (oma-runner's spec,
 * "a key per device"): it lives in the macOS Keychain, or on Linux in the Secret Service, where an item Soma made is
 * Soma's to read. The list of runners (id, name, address) is in [SomaHome.runners]; the key is looked up when asked for.
 *
 * A key reaches this machine as `soma-runner-<runner>.json` ({url, key, name, runner}): written by `oma-runner pair`
 * into Soma's own folder when the runner is this machine, or Taildropped and moved there by him. [take] moves each
 * into the Keychain and deletes the file, so it is on disk only until Soma next looks, and says so with the runner's address.
 */
object RunnerKeys {
    private const val SERVICE = "Soma runner"
    private val FILE = Regex("soma-runner-([a-z0-9][a-z0-9-]{0,62})\\.json")

    private val keyring: Keyring? by lazy { runCatching { Keyring.create() }.getOrNull() }

    /** Whether there is somewhere safe to keep keys on this machine. */
    val available: Boolean get() = keyring != null

    /** The runners set up, each with its key, in the order they were set up. One whose key is gone is left out. */
    fun machines(): List<MacMachine> = list().mapNotNull { (id, name, url) ->
        val key = runCatching { keyring?.getPassword(SERVICE, id) }.getOrNull() ?: return@mapNotNull null
        MacMachine(id, name, MacRunnerConfig(url, key))
    }

    private fun list(): List<Triple<String, String, String>> {
        val runners = SomaHome.read(SomaHome.runners).optJSONArray("runners") ?: JSONArray()
        return (0 until runners.length()).mapNotNull { runners.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Triple(id, o.optString("name").ifEmpty { id }, o.optString("url"))
        }
    }

    /** A key file that could not be taken: [key] is the same while the file is unchanged, so it is said once. */
    data class Problem(val key: String, val text: String)

    /** What one look found: what was [added], said with the runner's name and address, and the [problems]. */
    data class Taken(val added: List<String>, val problems: List<Problem>)

    /**
     * Takes every delivered key file into the Keychain and deletes it. A file that can't be read, or a machine with
     * nowhere safe to keep a key, leaves the file where it is and says why in [Taken.problems].
     */
    @Synchronized fun take(): Taken {
        val added = ArrayList<String>()
        val problems = ArrayList<Problem>()
        for (folder in SomaHome.delivered) {
            for (file in folder.listFiles().orEmpty()) {
                val runner = FILE.matchEntire(file.name)?.groupValues?.get(1) ?: continue
                fun problem(text: String) { problems += Problem("${file.path}:${file.lastModified()}:${file.length()}:$text", text) }
                val json = runCatching { JSONObject(file.readText()) }.getOrNull()
                val url = json?.optString("url").orEmpty()
                val key = json?.optString("key").orEmpty()
                if (url.isEmpty() || key.length < 32) { problem("${file.name} isn't a runner key"); continue }
                val ring = keyring
                if (ring == null) { problem("No Keychain here to keep ${file.name} in; it was left where it is"); continue }
                runCatching { ring.setPassword(SERVICE, runner, key) }.onFailure { problem("The Keychain refused ${file.name} (is the login keychain unlocked?): ${it.message}"); continue }
                val name = json?.optString("name").orEmpty().ifEmpty { runner }
                remember(runner, name, url)
                file.delete()
                added += "Added the runner $name at $url"
            }
        }
        return Taken(added, problems)
    }

    private fun remember(id: String, name: String, url: String) {
        val kept = list().filter { it.first != id } + Triple(id, name, url)
        SomaHome.write(SomaHome.runners, JSONObject().put("runners", JSONArray(kept.map { (i, n, u) -> JSONObject().put("id", i).put("name", n).put("url", u) })))
    }

    /**
     * `soma --keychain-check`: a throwaway key is kept, read back and removed, so a machine can be checked before any
     * runner is paired with it. Says what happened; never prints the key.
     */
    fun check(): String {
        val ring = keyring ?: return "no Keychain here: keys can't be kept on this machine"
        val value = java.util.UUID.randomUUID().toString()
        return runCatching {
            ring.setPassword("$SERVICE check", "check", value)
            val back = ring.getPassword("$SERVICE check", "check")
            ring.deletePassword("$SERVICE check", "check")
            if (back == value) "the Keychain kept a key and gave it back (${ring.javaClass.simpleName}); the test key is gone again" else "the Keychain gave back something else"
        }.getOrElse { "the Keychain refused: ${it.message}" }
    }

    /** Forgets [id]: its key leaves the Keychain and it leaves the list. */
    @Synchronized fun forget(id: String) {
        runCatching { keyring?.deletePassword(SERVICE, id) }
        SomaHome.write(SomaHome.runners, JSONObject().put("runners", JSONArray(list().filter { it.first != id }.map { (i, n, u) -> JSONObject().put("id", i).put("name", n).put("url", u) })))
    }
}
