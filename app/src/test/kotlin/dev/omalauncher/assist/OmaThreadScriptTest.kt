package dev.omalauncher.assist

import dev.omalauncher.theme.pack.moduleFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `oma threads` (assets/oma-thread.sh), the guest command that lists the Mac's threads and makes one. It is run
 * here against a stand-in for curl that records what it was given, so nothing reaches a network. What is pinned:
 * the key is handed to curl on its standard input and is in no argument, no output and no error.
 */
class OmaThreadScriptTest {
    private val script = moduleFile("src/main/assets/oma-thread.sh")

    /** Put together here so that the key is in no source line as a whole, and a search for it finds only leaks. */
    private val key = "S3cret" + "\"k\\ey" + "-phone"

    private class Ran(val code: Int, val out: String, val err: String, val arguments: String, val input: String)

    private fun has(program: String) = runCatching { ProcessBuilder("/bin/sh", "-c", "command -v $program").start().waitFor() == 0 }.getOrDefault(false)

    /** Runs the script with [config] as the Mac's file (null for none); curl answers [body] and [status], or fails when [exit] is not 0. */
    private fun run(config: String?, body: String, status: Int, vararg arguments: String, exit: Int = 0, trace: Boolean = false, others: Map<String, String> = emptyMap(), files: Map<String, String> = emptyMap(), look: (File) -> Unit = {}): Ran {
        assumeTrue("bash and jq are needed to run the script", has("bash") && has("jq"))
        val dir = Files.createTempDirectory("oma-thread-test").toFile()
        try {
            val bin = File(dir, "bin").apply { mkdirs() }
            File(bin, "curl").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$FAKE/arguments\"\ncat > \"\$FAKE/input\"\nprintf '%s\\n%s' \"\$FAKE_BODY\" \"\$FAKE_STATUS\"\nexit \"\$FAKE_EXIT\"\n")
                setExecutable(true)
            }
            val launcher = File(dir, "home/.config/omalauncher").apply { mkdirs() }
            if (config != null) launcher.resolve("mac-runner.json").writeText(config)
            for ((name, text) in others) launcher.resolve(name).writeText(text)
            for ((name, text) in files) File(dir, name).writeText(text)
            val process = ProcessBuilder(listOf("bash") + (if (trace) listOf("-x") else emptyList()) + script.absolutePath + arguments).directory(dir).apply {
                environment()["PATH"] = bin.path + ":" + environment()["PATH"]
                environment()["HOME"] = File(dir, "home").path
                environment()["FAKE"] = dir.path
                environment()["FAKE_BODY"] = body
                environment()["FAKE_STATUS"] = status.toString()
                environment()["FAKE_EXIT"] = exit.toString()
            }.start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            fun recorded(name: String) = File(dir, name).takeIf { it.isFile }?.readText().orEmpty()
            val code = process.waitFor()
            look(dir)
            return Ran(code, out, err, recorded("arguments"), recorded("input"))
        } finally { dir.deleteRecursively() }
    }

    private fun config(url: String = "http://192.0.2.1:8478/") = JSONObject().put("url", url).put("key", key).toString()

    private val roots = """{"ok":false,"error":"the project must be an existing folder under an allowed root","roots":["/Users/boss/Projects","/Users/boss/ClaudeSync/projects"]}"""

    @Test fun theKeyGoesToCurlOnItsInputAndNowhereElse() {
        val listed = run(config(), """{"ok":true,"threads":[{"id":"t1a2b3c4d","name":"FF4 mod","project":"/Users/boss/ClaudeSync/projects/ff4","status":"idle"}]}""", 200, "list", trace = true)
        assertEquals(0, listed.code)
        assertEquals("t1a2b3c4d\tidle\tFF4 mod\t/Users/boss/ClaudeSync/projects/ff4\n", listed.out)
        // A quote or a backslash in the key is escaped as curl's config wants it, so it cannot end the line early.
        assertEquals("header = \"Authorization: Bearer S3cret\\\"k\\\\ey-phone\"\n", listed.input)
        assertTrue(listed.arguments, listed.arguments.contains("--config\n-\n")); assertTrue(listed.arguments.endsWith("\nhttp://192.0.2.1:8478/threads\n"))
        // Not even with every command of the script traced.
        for (seen in listOf(listed.arguments, listed.out, listed.err)) for (part in listOf("S3cret", "ey-phone")) assertFalse("$part in $seen", seen.contains(part))
        for (failing in listOf(run(config(), roots, 403, "new", "x", "/etc", trace = true), run(config(), """{"ok":false,"error":"no"}""", 401, "list", trace = true), run(config(), "", 0, "list", exit = 7, trace = true))) {
            assertEquals(1, failing.code)
            for (seen in listOf(failing.arguments, failing.out, failing.err)) for (part in listOf("S3cret", "ey-phone")) assertFalse("$part in $seen", seen.contains(part))
        }
        // The script never follows a redirect, never turns on curl's own tracing and never writes the key to a file.
        val source = script.readText()
        for (never in listOf("--location", " -L", "--verbose", " -v ", "--trace", "set -x", "mktemp", "--header \"Authorization", "-H ")) assertFalse(never, source.contains(never))
    }

    @Test fun aThreadIsMadeWhereTheMacAllowsAndARefusalSaysItsRoots() {
        val made = run(config(), """{"ok":true,"thread":{"id":"t9z8y7x6w","name":"FF4 mod","project":"/Users/boss/ClaudeSync/projects/ff4","status":"idle"}}""", 200, "new", "FF4 mod", "~/ClaudeSync/projects/ff4")
        assertEquals(0, made.code)
        assertEquals("Made thread \"FF4 mod\", id t9z8y7x6w, in /Users/boss/ClaudeSync/projects/ff4.\n", made.out)
        // The name and the folder go as he gave them, as JSON, to the runner's one address for making a thread.
        val sent = made.arguments.lines()
        assertEquals("POST", sent[sent.indexOf("--request") + 1])
        assertEquals(JSONObject().put("name", "FF4 mod").put("project", "~/ClaudeSync/projects/ff4").toString(), JSONObject(sent[sent.indexOf("--data-binary") + 1]).toString())
        assertEquals("http://192.0.2.1:8478/threads", sent.last { it.isNotEmpty() })
        // --kind goes to the runner as its kind; without it the request has none and the runner starts Claude.
        val codex = run(config(), """{"ok":true,"thread":{"id":"t1","name":"n","project":"/p","status":"idle"}}""", 200, "new", "n", "~/p", "--kind", "codex").arguments.lines()
        assertEquals(JSONObject().put("name", "n").put("project", "~/p").put("kind", "codex").toString(), JSONObject(codex[codex.indexOf("--data-binary") + 1]).toString())
        val refused = run(config(), roots, 403, "new", "Elsewhere", "/etc")
        assertEquals(1, refused.code); assertEquals("", refused.out)
        assertEquals("The Mac refused: the project must be an existing folder under an allowed root.\nIt takes an existing folder under one of:\n  /Users/boss/Projects\n  /Users/boss/ClaudeSync/projects\n", refused.err)
    }

    @Test fun whatCannotBeAskedSaysWhyAndAsksNothing() {
        assertEquals("The Mac turned this phone's key down.\n", run(config(), """{"ok":false,"error":"no"}""", 401, "list").err)
        assertEquals("Mac unreachable. Nothing was made.\n", run(config(), "", 0, "new", "a", "b", exit = 28).err)
        assertEquals("The Mac answered HTTP 502.\n", run(config(), "<html>", 502, "list").err)
        // Nothing is asked of the Mac without a file, an address of a host alone, a key, or a command it knows.
        val unasked = listOf(
            run(null, "{}", 200, "list") to "No Mac is set up on this phone",
            run("""{"url":"http://192.0.2.1:8478"}""", "{}", 200, "list") to "no usable \"key\"",
            run("""{"url":"http://192.0.2.1:8478","key":"two words"}""", "{}", 200, "list") to "no usable \"key\"",
            run(config("http://192.0.2.1:8478/somewhere?else"), "{}", 200, "list") to "no usable \"url\"",
            run(config("file:///etc/passwd"), "{}", 200, "list") to "no usable \"url\"",
            run(config(), "{}", 200) to "usage: oma threads list",
            run(config(), "{}", 200, "delete", "t1") to "usage: oma threads list",
            run(config(), "{}", 200, "new", "only a name") to "usage: oma threads list",
            run(config(), "{}", 200, "list", "extra") to "usage: oma threads list",
            run(config(), "{}", 200, "new", "n", "~/p", "--kind", "gemini") to "usage: oma threads list",
            run(config(), "{}", 200, "new", "n", "~/p", "--kind") to "usage: oma threads list",
            run(config(), "{}", 200, "new", "n", "~/p", "--model", "codex") to "usage: oma threads list",
        )
        for ((ran, why) in unasked) {
            assertNotEquals(0, ran.code); assertTrue(ran.err, ran.err.contains(why)); assertEquals("", ran.arguments); assertEquals("", ran.out)
        }
    }

    /**
     * Two Macs (MacMachines.kt): each listed under the phone's id for its threads, with its name; a new thread goes
     * to the one named; and add-mac sets the second up from the file its runner sent without ever printing the key.
     */
    @Test fun twoMacsAreListedAndChosenAndTheSecondIsSetUpWithoutItsKeyShowing() {
        val second = mapOf("mac-runner-macbook-pro.json" to JSONObject().put("url", "http://192.0.2.2:8478").put("key", key).put("name", "MacBook Pro").toString())
        val body = """{"ok":true,"threads":[{"id":"t1a2b3c4d","name":"Herd","project":"/p","status":"working"}],"thread":{"id":"t1a2b3c4d","name":"Herd","project":"/p"}}"""
        val listed = run(config(), body, 200, "list", others = second)
        assertEquals(0, listed.code)
        assertEquals("t1a2b3c4d\tworking\tHerd\t/p\tMac Pro\nmacbook-pro/t1a2b3c4d\tworking\tHerd\t/p\tMacBook Pro\n", listed.out)
        val made = run(config(), body, 200, "new", "Herd", "/p", "--mac", "MacBook Pro", others = second)
        assertEquals("Made thread \"Herd\", id macbook-pro/t1a2b3c4d, in /p on the MacBook Pro.\n", made.out)
        assertTrue(made.arguments, made.arguments.contains("http://192.0.2.2:8478/threads"))
        val macs = run(config(), body, 200, "macs", others = second)
        assertEquals("Mac Pro\tfirst\thttp://192.0.2.1:8478/\nMacBook Pro\tmacbook-pro\thttp://192.0.2.2:8478\n", macs.out)
        for (ran in listOf(listed, made, macs)) assertFalse(ran.out + ran.err + ran.arguments, (ran.out + ran.err + ran.arguments).contains(key))
        // The file its runner sent: set up beside the first under its own name, mode 600, and taken out of the inbox.
        val sent = JSONObject().put("url", "http://192.0.2.2:8478").put("key", key).put("name", "MacBook Pro").toString()
        var kept = ""
        var mode = ""
        var left = true
        val added = run(config(), "", 200, "add-mac", "sent.json", files = mapOf("sent.json" to sent)) { dir ->
            val file = File(dir, "home/.config/omalauncher/mac-runner-macbook-pro.json")
            kept = file.takeIf { it.isFile }?.readText().orEmpty()
            mode = Files.getPosixFilePermissions(file.toPath()).toString()
            left = File(dir, "sent.json").exists()
        }
        assertEquals(added.err, 0, added.code)
        assertEquals(key, JSONObject(kept).getString("key"))
        assertEquals("[OWNER_READ, OWNER_WRITE]", mode)
        assertFalse(left)
        assertFalse(added.out + added.err, (added.out + added.err).contains(key))
    }
}
