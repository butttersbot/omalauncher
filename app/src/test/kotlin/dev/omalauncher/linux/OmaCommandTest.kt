package dev.omalauncher.linux

import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.upgrade.ManagedFiles
import dev.omalauncher.upgrade.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `oma`, the one command for the small guest tools: where it, its tools and the old names are installed, that
 * oma.sh's subcommands are the table's, and that each old name still reaches its tool. The dispatcher's own
 * behaviour (help, an unknown subcommand, each subcommand reaching the real tool) is tested by
 * src/test/shell/test_oma.sh, run here when the machine has bash.
 */
class OmaCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun asset(name: String) = moduleFile("src/main/assets/$name").readBytes()

    private fun has(program: String) = runCatching { ProcessBuilder("/bin/sh", "-c", "command -v $program").start().waitFor() == 0 }.getOrDefault(false)

    @Test fun theDispatcherAndTheOldNamesAreExecutablesTheLauncherOwns() {
        val rootfs = File(tmp.root, "arch")
        val tools = omaTools(rootfs, { asset(OMA_ASSET) }, { asset(DROP_ASSET) }, { asset(MSG_ASSET) }, { asset(GROKBOT_ASSET) }, { asset(CONNECTIONS_ASSET) })
        // `oma drop` was never a command of its own, so it has no old name; its script is installed with `oma`.
        assertEquals(
            listOf("usr/local/bin/oma", "usr/local/bin/oma-thread", "usr/local/bin/oma-keep", "usr/local/bin/oma-agents", "usr/local/lib/oma/drop", "usr/local/lib/oma/msg", "usr/local/lib/oma/connections", "usr/local/lib/oma/front", "usr/local/lib/oma/receipt", "usr/local/bin/grokbot"),
            tools.map { it.file.relativeTo(rootfs).path },
        )
        assertTrue(tools.all { it.policy == Policy.Owned && it.executable })
        ManagedFiles(tmp.root, File(tmp.root, "managed.json")).sync(tools)
        assertTrue(tools.all { it.file.canExecute() })
        assertEquals(asset("oma.sh").decodeToString(), File(rootfs, OMA_COMMAND).readText())
        assertEquals(asset("oma-drop.sh").decodeToString(), File(rootfs, "$OMA_LIB/drop").readText())
        // oma.sh finds its tools as ../lib/oma from where it is installed.
        assertEquals(File(rootfs, OMA_COMMAND).parentFile.resolve("../lib/oma").normalize(), File(rootfs, OMA_LIB))
        assertTrue(asset("oma.sh").decodeToString().contains("lib=\"\$(dirname \"\$0\")/../lib/oma\""))
    }

    @Test fun eachToolIsInstalledUnchangedWhereTheDispatcherLooks() {
        // The keeper's provider is testable here; the other two take a Context, so their source is read instead.
        assertEquals("$OMA_LIB/keep", KEEP_COMMAND)
        val rootfs = File(tmp.root, "arch")
        val keep = keepTool(rootfs) { asset(KEEP_ASSET) }
        assertEquals(File(rootfs, "usr/local/lib/oma/keep"), keep.file); assertEquals(Policy.Owned, keep.policy); assertTrue(keep.executable)
        for ((source, lib, script) in listOf(
            Triple("assist/MacThreads.kt", "thread", "oma-thread.sh"),
            Triple("assist/BuilderWatch.kt", "agents", "oma-agents.sh"),
        )) {
            val text = moduleFile("src/main/kotlin/dev/omalauncher/$source").readText()
            assertTrue(source, text.contains("Managed(File(rootfs, \"$OMA_LIB/$lib\"), Policy.Owned, executable = true) { context.assets.open(\"$script\")"))
        }
        assertEquals(listOf("thread", "keep", "agents", "connections", "drop", "msg", "front", "receipt"), OMA_PARTS.map { it.lib })
        assertEquals(listOf("oma-thread", "oma-keep", "oma-agents", null, null, null, null, null), OMA_PARTS.map { it.oldName })
    }

    @Test fun theDispatchersSubcommandsAreTheTables() {
        val script = asset("oma.sh").decodeToString()
        val cases = script.substringAfter("case \$command in\n").substringBefore("\nesac")
        val labels = Regex("^  ([a-z| -]+)\\)", RegexOption.MULTILINE).findAll(cases).map { it.groupValues[1] }.toList()
        // Besides help, the only other case is the catch-all for an unknown word, which the label pattern leaves out.
        assertEquals(OMA_PARTS.map { it.word } + "help | -h | --help", labels)
        assertTrue(cases.contains("\n  *)\n"))
        for (part in OMA_PARTS) {
            val branch = cases.substringAfter("\n  ${part.word})").substringBefore(";;")
            assertTrue(branch, branch.contains("exec \"\$lib/${part.lib}\""))
        }
        assertEquals(listOf("threads", "keep", "status", "connections", "drop", "msg", "front", "receipt"), OMA_PARTS.map { it.word })
    }

    @Test fun theLauncherInstallsItWithLinuxAndTellsTheAgents() {
        val runtime = moduleFile("src/main/kotlin/dev/omalauncher/Runtime.kt").readText()
        for (tools in listOf("macGuestTools", "keepGuestTools", "agentsGuestTools", "omaGuestTools")) {
            assertTrue(tools, runtime.contains("{ $tools(context, proot.rootfs) },"))
        }
        val pointer = moduleFile("src/main/kotlin/dev/omalauncher/widgets/WidgetEngine.kt").readText()
        assertTrue(pointer.contains("`oma` holds the launcher's small tools: `oma threads` (the Mac's project threads), `oma keep`, `oma status`; `oma help` says more. \" +"))
        assertTrue(
            pointer.contains(
                "\"`oma drop get` brings files the Macs Taildropped to the phone into ~/Inbox; `oma drop send FILE` puts a file in the Mac's Downloads/From Fold.\\n\"",
            ),
        )
        assertTrue(pointer.contains("`oma msg send DEVICE[:SESSION] TEXT` writes to an agent on one of the computers"))
    }

    @Test fun theOldNamesRunOmaWithTheSameWords() {
        assumeTrue("sh is needed to run the aliases", has("sh"))
        val rootfs = File(tmp.root, "arch")
        ManagedFiles(tmp.root, File(tmp.root, "managed.json")).sync(omaTools(rootfs, { asset(OMA_ASSET) }, { asset(DROP_ASSET) }, { asset(MSG_ASSET) }, { asset(GROKBOT_ASSET) }, { asset(CONNECTIONS_ASSET) }))
        // Stand-ins for the tools, which print what they were given.
        for (part in OMA_PARTS) File(rootfs, "$OMA_LIB/${part.lib}").apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho \"${part.lib} \$*\"\n"); setExecutable(true) }
        fun run(vararg words: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(File(rootfs, "usr/local/bin/${words[0]}").path) + words.drop(1)).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            return process.waitFor() to out
        }
        assertEquals(0 to "thread list\n", run("oma-thread", "list"))
        assertEquals(0 to "thread new FF4 mod ~/p\n", run("oma-thread", "new", "FF4 mod", "~/p"))
        assertEquals(0 to "keep run web -- python3 -m http.server\n", run("oma-keep", "run", "web", "--", "python3", "-m", "http.server"))
        assertEquals(0 to "keep serve\n", run("oma-keep", "serve"))
        assertEquals(0 to "agents status\n", run("oma-agents", "status"))
        assertEquals(0 to "drop get --keep\n", run("oma", "drop", "get", "--keep"))
        assertEquals(0 to "msg send macbook-pro:b53e20 hi\n", run("oma", "msg", "send", "macbook-pro:b53e20", "hi"))
        // oma-agents only ever took `status`; anything else is still its usage.
        assertEquals(2 to "usage: oma status\n", run("oma-agents"))
        assertEquals(2 to "usage: oma status\n", run("oma-agents", "clean"))
    }

    @Test fun itsOwnTestsPass() {
        assumeTrue("bash is needed to run the script's tests", has("bash"))
        // The script is the guest's (GNU stat, /proc): its tests run on Linux, not on the Mac.
        assumeTrue("Linux is needed to run the script's tests", System.getProperty("os.name") == "Linux")
        val run = ProcessBuilder("bash", moduleFile("src/test/shell/test_oma.sh").path).redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        assertEquals(out, 0, run.waitFor())
    }

    @Test fun omaFrontKeepsTheChoiceAndSaysWhoAnswers() {
        val home = tmp.newFolder("home")
        val script = File(tmp.root, "front").apply { writeText(OMA_FRONT_SCRIPT); setExecutable(true) }
        val bin = tmp.newFolder("bin")
        fun run(vararg words: String, path: String = "/usr/bin:/bin"): Pair<Int, String> {
            val process = ProcessBuilder(listOf(script.path) + words).redirectErrorStream(true)
                .apply { environment()["HOME"] = home.path; environment()["PATH"] = path }.start()
            val out = process.inputStream.bufferedReader().readText()
            return process.waitFor() to out
        }
        // Nothing chosen and no Grok here: Grok is what is chosen, and Haiku answers until it is installed.
        assertTrue(run().second, run().second.contains("not installed"))
        assertEquals(0, run("haiku").first)
        assertEquals("haiku\n", File(home, ".config/oma/front-agent").readText())
        assertTrue(run().second.contains("Claude Code is not installed"))
        File(bin, "claude").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertTrue(run(path = "${bin.path}:/usr/bin:/bin").second.contains("The front agent is Haiku"))
        File(bin, "grok").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        run("grok", path = "${bin.path}:/usr/bin:/bin")
        assertEquals("grok\n", File(home, ".config/oma/front-agent").readText())
        assertTrue(run(path = "${bin.path}:/usr/bin:/bin").second.contains("grok login --device-auth"))
        File(home, ".grok").mkdirs(); File(home, ".grok/auth.json").writeText("{}")
        assertTrue(run(path = "${bin.path}:/usr/bin:/bin").second.contains("The front agent is Grok"))
        // Anything else is refused and changes nothing.
        assertEquals(2, run("opus").first)
        assertEquals("grok\n", File(home, ".config/oma/front-agent").readText())
    }
}
