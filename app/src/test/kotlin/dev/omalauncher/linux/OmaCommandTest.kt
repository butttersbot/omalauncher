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
        val tools = omaTools(rootfs) { asset(OMA_ASSET) }
        assertEquals(
            listOf("usr/local/bin/oma", "usr/local/bin/oma-thread", "usr/local/bin/oma-keep", "usr/local/bin/oma-agents"),
            tools.map { it.file.relativeTo(rootfs).path },
        )
        assertTrue(tools.all { it.policy == Policy.Owned && it.executable })
        ManagedFiles(tmp.root, File(tmp.root, "managed.json")).sync(tools)
        assertTrue(tools.all { it.file.canExecute() })
        assertEquals(asset("oma.sh").decodeToString(), File(rootfs, OMA_COMMAND).readText())
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
            Triple("assist/BuilderHealth.kt", "agents", "oma-agents.sh"),
        )) {
            val text = moduleFile("src/main/kotlin/dev/omalauncher/$source").readText()
            assertTrue(source, text.contains("Managed(File(rootfs, \"$OMA_LIB/$lib\"), Policy.Owned, executable = true) { context.assets.open(\"$script\")"))
        }
        assertEquals(listOf("thread", "keep", "agents"), OMA_PARTS.map { it.lib })
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
        assertEquals(listOf("threads", "keep", "status"), OMA_PARTS.map { it.word })
    }

    @Test fun theLauncherInstallsItWithLinuxAndTellsTheAgents() {
        val runtime = moduleFile("src/main/kotlin/dev/omalauncher/Runtime.kt").readText()
        for (tools in listOf("macGuestTools", "keepGuestTools", "agentsGuestTools", "omaGuestTools")) {
            assertTrue(tools, runtime.contains("{ $tools(context, proot.rootfs) },"))
        }
        val pointer = moduleFile("src/main/kotlin/dev/omalauncher/widgets/WidgetEngine.kt").readText()
        assertTrue(pointer.contains("`oma` holds the launcher's small tools: `oma threads` (the Mac's project threads), `oma keep`, `oma status`; `oma help` says more.\\n"))
    }

    @Test fun theOldNamesRunOmaWithTheSameWords() {
        assumeTrue("sh is needed to run the aliases", has("sh"))
        val rootfs = File(tmp.root, "arch")
        ManagedFiles(tmp.root, File(tmp.root, "managed.json")).sync(omaTools(rootfs) { asset(OMA_ASSET) })
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
        // oma-agents only ever took `status`; anything else is still its usage.
        assertEquals(2 to "usage: oma status\n", run("oma-agents"))
        assertEquals(2 to "usage: oma status\n", run("oma-agents", "clean"))
    }

    @Test fun itsOwnTestsPass() {
        assumeTrue("bash is needed to run the script's tests", has("bash"))
        val run = ProcessBuilder("bash", moduleFile("src/test/shell/test_oma.sh").path).redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        assertEquals(out, 0, run.waitFor())
    }
}
