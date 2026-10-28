package dev.omalauncher.desktop

import dev.omalauncher.assist.MacAnswer
import dev.omalauncher.assist.MacEvent
import dev.omalauncher.assist.MacRunnerClient
import dev.omalauncher.assist.MacThread
import dev.omalauncher.assist.MacThreadStatus
import java.io.File

/**
 * `soma check` (Boss, 2026-10-27: "use scratch chats"): what his Soma does with a runner, done for real on this Mac's
 * runner with Soma's own key, with no window and no one at the screen. In ~/Projects/oma-runner-scratch it makes a
 * "Scratch Claude" chat and a "Scratch Codex" one (manual, so it asks), sends each its words and waits for the answer,
 * sees Codex ask to run `touch scratch-ok.txt && echo ok` and stops that turn (only his tap approves: MacThreadsTest), and stops a long answer
 * in the Claude one. It says each step and the threads' ids, so the phone can find them and mail one.
 * The key is read here because only Soma's own program opens its Keychain item without asking him.
 */
object Check {
    private const val MINUTES = 4

    private fun runner() = RunnerKeys.machines().let { all -> all.firstOrNull { it.id == SomaHome.machine } ?: all.firstOrNull() }

    /** `soma check stop THREAD`: a scratch thread's turn stopped, and what was waiting its turn dropped, as its tab's stop does. */
    fun stop(thread: String, say: (String) -> Unit): Int {
        val machine = runner() ?: return 1.also { say("No runner is paired with this Soma (or its key isn't in the Keychain).") }
        val answer = MacRunnerClient(machine.config, machine.id).stop(thread)
        say("stop $thread: $answer")
        return if (answer is MacAnswer.Ok) 0 else 2
    }

    fun run(say: (String) -> Unit): Int {
        val machine = runner() ?: return 1.also { say("No runner is paired with this Soma (or its key isn't in the Keychain).") }
        val client = MacRunnerClient(machine.config, machine.id)
        val project = File(System.getProperty("user.home"), "Projects/oma-runner-scratch").apply { mkdirs() }.path
        var failed = 0
        fun step(what: String, ok: Boolean) { say((if (ok) "ok    " else "FAIL  ") + what); if (!ok) failed++ }
        fun made(answer: MacAnswer<MacThread>, what: String): MacThread? = (answer as? MacAnswer.Ok)?.value.also { step("$what made: ${it?.id ?: answer}", it != null) }
        fun texts(id: String): String = ((client.events(id, 0) as? MacAnswer.Ok)?.value?.events.orEmpty())
            .joinToString("\n") { e -> when (e) { is MacEvent.Text -> e.text; is MacEvent.Result -> e.text; else -> "" } }
        fun until(seconds: Int = MINUTES * 60, done: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) { if (runCatching(done).getOrDefault(false)) return true; Thread.sleep(1_000) }
            return false
        }
        fun thread(id: String) = (client.thread(id) as? MacAnswer.Ok)?.value

        say("runner ${machine.id} (${machine.config.url}), project $project")
        val claude = made(client.makeThread("Scratch Claude", project, "claude"), "Scratch Claude") ?: return 1
        client.sendMessage(claude.id, "Scratch check from Soma desktop. Reply with exactly SCRATCH-CLAUDE-OK and nothing else.")
        step("Claude answered", until { "SCRATCH-CLAUDE-OK" in texts(claude.id) && thread(claude.id)?.status == MacThreadStatus.Idle })

        val codex = made(client.makeThread("Scratch Codex", project, "codex", mode = "manual"), "Scratch Codex") ?: return 1
        client.sendMessage(codex.id, "Scratch check from Soma desktop. Run the shell command `touch scratch-ok.txt && echo ok` in this folder, then reply with SCRATCH-CODEX- followed by its output in capitals.")
        var asked: dev.omalauncher.assist.MacApproval? = null
        step("Codex asked for approval", until { thread(codex.id)?.pending?.firstOrNull()?.also { asked = it } != null })
        // Only his tap answers a Mac approval (MacThreadsTest.nothingButHisTapSendsTheMacAnAnswer): the check sees the
        // request arrive, as his Approve button would, and stops the turn rather than approving it itself.
        asked?.let { a -> say("      it asked: ${a.tool} ${a.input.take(120)}") }
        step("stopped the waiting Codex turn: ${client.stop(codex.id)}", until(60) { thread(codex.id)?.pending.isNullOrEmpty() })

        client.sendMessage(claude.id, "Write a 3000-word story about a lighthouse keeper.")
        step("the long answer started", until(90) { thread(claude.id)?.status == MacThreadStatus.Working })
        Thread.sleep(3_000)
        step("stopped: ${client.stop(claude.id)}", until(60) { thread(claude.id)?.status != MacThreadStatus.Working })

        say("threads: Scratch Claude ${claude.id}, Scratch Codex ${codex.id}")
        say(if (failed == 0) "all checks passed" else "$failed check(s) failed")
        return if (failed == 0) 0 else 2
    }
}
