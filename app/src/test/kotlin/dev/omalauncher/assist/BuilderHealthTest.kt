package dev.omalauncher.assist

import dev.omalauncher.theme.pack.moduleFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** How the builder's health is read from /proc and the build lane, judged, told to the front agent and written for `oma-agents`. */
class BuilderHealthTest {
    private val minute = 60_000L
    private val start = 1_000_000_000L

    /** The phone builder's own command line, as /proc showed it on the phone (2026-10-13). */
    private val builderArgv = listOf("/root/.local/share/mise/installs/claude/latest/claude") + claudeStreamCommand("a9cbaba6-6dd3-473f-84dc-c77e575cbd10").split(' ').drop(1).map { it.trim('\'') }

    private fun row(pid: Int, ppid: Int = 1, startedAt: Long = start, cpuMs: Long = 0, argv: List<String> = builderArgv, guest: Boolean = true, tracer: Int = 0) =
        ProcRow(pid, ppid, tracer, startedAt, cpuMs, argv, guest)

    @Test fun aProcessIsReadFromItsStatStatusAndCmdline() {
        // Real lines from the phone, with a name that has a space and brackets in it to make sure fields are counted from the end of the name.
        val stat = "699 (claude (x) y) S 696 1852 0 0 -1 4194304 311377 3448426 66 761 3117 729 77165 2864 20 0 21 0 2148834 6233235456 98195"
        val read = procRow(699, stat, "Name:\tclaude\nTracerPid:\t696\n", "claude\u0000-p\u0000--verbose\u0000".toByteArray(), guest = true, ticks = 100, bootedAt = start)
        assertEquals(ProcRow(699, 696, 696, start + 21_488_340, (3117 + 729 + 77165 + 2864) * 10L, listOf("claude", "-p", "--verbose"), true), read)
        assertNull(procRow(1, "1 (init) S 0", "", ByteArray(0), false, 100, start))
        assertNull(procRow(1, stat, "", ByteArray(0), false, 0, start))
        assertEquals(0, procRow(699, stat, "Name:\tclaude\n", ByteArray(0), false, 100, start)?.tracer)
    }

    @Test fun aStandInForProcIsReadAndWhatCannotBeReadIsLeftOut() {
        val proc = Files.createTempDirectory("oma-proc").toFile()
        try {
            fun add(pid: String, stat: String?, argv: List<String>, guest: Boolean) = File(proc, pid).apply {
                mkdirs()
                if (stat != null) File(this, "stat").writeText(stat)
                File(this, "status").writeText("TracerPid:\t10\n")
                File(this, "cmdline").writeBytes(argv.joinToString("") { it + "\u0000" }.toByteArray())
                if (guest) File(this, "guest").writeText("")
            }
            add("11", "11 (claude) S 10 0 0 0 0 0 0 0 0 0 300 100 0 0 20 0 1 0 500 0", builderArgv, guest = true)
            add("12", "12 (sh) S 11 0 0 0 0 0 0 0 0 0 1 1 0 0 20 0 1 0 600 0", listOf("sh"), guest = true)
            add("13", null, listOf("gone"), guest = true)
            add("self", "x", listOf("x"), guest = false)
            File(proc, "uptime").writeText("1 1")
            val rows = procRows(proc, 100, start) { File(it, "guest").exists() }.sortedBy { it.pid }
            assertEquals(listOf(11, 12), rows.map { it.pid })
            assertEquals(ProcRow(11, 10, 10, start + 5_000, 4_000, builderArgv, true), rows[0])
        } finally { proc.deleteRecursively() }
    }

    @Test fun onlyTheBuildersOwnCommandIsABuilder() {
        assertTrue(builderCommand(builderArgv))
        assertTrue(builderCommand(listOf("node", "/usr/bin/claude") + claudeStreamCommand().split(' ').drop(1).map { it.trim('\'') }))
        val front = quickCommand("is the builder frozen?").split(' ').map { it.trim('\'') }
        for (other in listOf(
            front,
            // oma-door's and the old background command: no approvals over stdio.
            listOf("claude", "-p", "--permission-mode", "auto", "--allowedTools", "Bash(oma-phone *)", "Read", "--", "hi"),
            listOf("claude"), listOf("claude", "--resume", "x"),
            listOf("python3", "/usr/local/bin/oma-door"),
            listOf("/system/bin/app_process64", "dev.omalauncher.boss"),
            // The flags in the prompt of some other program are not a builder.
            listOf("grep", "-p", "--input-format", "stream-json", "--permission-prompt-tool", "stdio"),
            listOf("claude", "-p", "--input-format", "text", "--permission-prompt-tool", "stdio"),
        )) assertFalse(other.toString(), builderCommand(other))
    }

    @Test fun theTaskOwnsTheBuilderItStartedAndEveryOtherIsALeftover() {
        val launcher = 13955
        val rows = listOf(
            row(launcher, argv = listOf("dev.omalauncher.boss"), guest = false),
            row(500, ppid = launcher, argv = listOf("libproot.so"), guest = false, startedAt = start - 60 * minute),
            row(501, ppid = 500, tracer = 500, startedAt = start - 60 * minute, cpuMs = 9_000),  // an old task's builder
            row(696, ppid = launcher, argv = listOf("libproot.so"), guest = false, startedAt = start + 100, cpuMs = 500),
            row(699, ppid = 696, tracer = 696, startedAt = start + 300, cpuMs = 2_000),           // this task's
            row(700, ppid = 699, tracer = 696, argv = listOf("bash"), startedAt = start + 5 * minute, cpuMs = 1_000),
            row(701, ppid = 1, tracer = 696, argv = listOf("java", "GradleDaemon"), startedAt = start + 6 * minute, cpuMs = 30_000),
            row(702, ppid = 699, tracer = 696, startedAt = start + 7 * minute),                   // one the builder itself runs
            row(800, ppid = 1, tracer = 0, argv = listOf("python3", "/usr/local/bin/oma-door")),
            row(900, guest = false, startedAt = start + 8 * minute),                              // not the guest's
            row(launcher, guest = true),
        )
        val found = builderProcs(rows, listOf(start), self = launcher)
        assertEquals(699, found.own[start]?.pid)
        assertEquals(listOf(501), found.leftovers.map { it.pid })
        // The task's whole session counts: its tools, the proot that runs them and a daemon that left its parent.
        assertEquals(listOf(696, 699, 700, 701, 702), session(rows, found.own.getValue(start)).map { it.pid }.sorted())
        assertEquals(500L + 2_000 + 1_000 + 30_000 + 0, sessionCpu(rows, found.own.getValue(start)))
        // With no task running every builder is a leftover; one started before the task began is never its own.
        assertEquals(listOf(501, 699, 702), builderProcs(rows, emptyList(), launcher).leftovers.map { it.pid })
        assertNull(builderProcs(rows, listOf(start + 9 * minute), launcher).own[start + 9 * minute])
        // Two builders at once, as a pool will run them: each owns the first one started after it began.
        val second = row(1001, ppid = 1000, tracer = 1000, startedAt = start + 2 * minute)
        val pool = builderProcs(rows + second, listOf(start, start + 2 * minute - 1_000), launcher)
        assertEquals(699, pool.own[start]?.pid); assertEquals(1001, pool.own[start + 2 * minute - 1_000]?.pid)
        assertEquals(listOf(501), pool.leftovers.map { it.pid })
    }

    @Test fun theCpuTrackTellsComputingFromWaiting() {
        var track = CpuTrack().after(699, 10_000, start)
        assertEquals(CpuTrack(699, start, listOf(CpuSample(start, 10_000))), track)
        assertNull(track.movedAt); assertEquals(start, track.stillSince)
        // Too soon to tell: the earlier look stands.
        assertSame(track, track.after(699, 99_000, start + 5_000))
        // 30 s of CPU in a minute is computing; a trickle of 0.5 s a minute is waiting.
        track = track.after(699, 40_000, start + minute)
        assertEquals(start + minute, track.movedAt)
        track = track.after(699, 40_500, start + 2 * minute)
        assertEquals(start + minute, track.movedAt)
        assertEquals(30_500, track.growthMs); assertEquals(2 * minute, track.windowMs)
        // Only the last ten minutes are counted.
        for (i in 3..13) track = track.after(699, 40_500, start + i * minute)
        assertEquals(0, track.growthMs); assertEquals(10 * minute, track.windowMs)
        // Another process, or none, starts again.
        assertEquals(CpuTrack(700, start + 14 * minute, listOf(CpuSample(start + 14 * minute, 5))), track.after(700, 5, start + 14 * minute))
        assertEquals(CpuTrack(), track.after(null, null, start + 14 * minute))
    }

    private fun track(movedAgo: Long?, now: Long, firstAgo: Long = 30 * minute) =
        CpuTrack(699, now - firstAgo, listOf(CpuSample(now - 5 * minute, 1_000), CpuSample(now, if (movedAgo != null && movedAgo < 2 * minute) 41_000 else 1_000)), movedAgo?.let { now - it })

    private fun facts(quiet: Long, cpuMoved: Long?, now: Long = start + 60 * minute, approval: Boolean = false, pid: Int? = 699, traced: Boolean = true, leftovers: List<ProcRow> = emptyList(), queued: Int = 0) =
        BuilderFacts(now, working = true, startedAt = start, heardAt = now - quiet, lastStep = "Agent: Build agents page", lastStepAt = now - quiet - 2 * minute,
            traced = traced, pid = pid, cpu = if (pid == null) CpuTrack() else track(cpuMoved, now), approval = approval, queued = queued, request = "make the agents page", leftovers = leftovers)

    @Test fun theVerdictFollowsOutputCpuApprovalsAndTheProcess() {
        assertEquals(BuilderVerdict.Idle, builderVerdict(BuilderFacts(start, working = false)))
        assertEquals(BuilderVerdict.Leftovers, builderVerdict(BuilderFacts(start, working = false, leftovers = listOf(row(501)))))
        assertEquals(BuilderVerdict.Working, builderVerdict(facts(quiet = 2 * minute, cpuMoved = null)))
        assertEquals(BuilderVerdict.Quiet, builderVerdict(facts(quiet = 40 * minute, cpuMoved = minute)))
        // Quiet and still, but not for long enough to call it stalled.
        assertEquals(BuilderVerdict.Quiet, builderVerdict(facts(quiet = 9 * minute, cpuMoved = 20 * minute)))
        assertEquals(BuilderVerdict.Quiet, builderVerdict(facts(quiet = 20 * minute, cpuMoved = 9 * minute)))
        assertEquals(BuilderVerdict.Stalled, builderVerdict(facts(quiet = 11 * minute, cpuMoved = 12 * minute)))
        assertEquals(BuilderVerdict.Stalled, builderVerdict(facts(quiet = 11 * minute, cpuMoved = null)))
        // A step waiting for Boss is never stalled, however long it waits, and is never restarted.
        val waiting = facts(quiet = 45 * minute, cpuMoved = null, approval = true)
        assertEquals(BuilderVerdict.WaitingForBoss, builderVerdict(waiting)); assertFalse(restartable(builderVerdict(waiting)))
        assertEquals(BuilderVerdict.WaitingForBoss, builderVerdict(facts(quiet = 45 * minute, cpuMoved = null, approval = true, pid = null)))
        assertEquals(BuilderVerdict.Dead, builderVerdict(facts(quiet = minute, cpuMoved = null, pid = null)))
        // Still starting, or an agent whose process cannot be found, is never dead nor stalled.
        assertEquals(BuilderVerdict.Working, builderVerdict(facts(quiet = 0, cpuMoved = null, pid = null, now = start + 30_000)))
        assertEquals(BuilderVerdict.Quiet, builderVerdict(facts(quiet = 40 * minute, cpuMoved = null, pid = null, traced = false)))
        assertEquals(listOf(BuilderVerdict.Stalled, BuilderVerdict.Dead), BuilderVerdict.entries.filter(::restartable))
        // The thresholds as chosen.
        assertEquals(listOf(3 * minute, 10 * minute, 15 * minute, minute, 1_000L), listOf(QUIET_MS, STALL_MS, NOTICE_STALL_MS, DEAD_GRACE_MS, CPU_BUSY_MS_PER_MIN))
    }

    @Test fun theBoardLineSaysWhatIsWrongInOneLineAndNothingWhenIdle() {
        assertNull(healthLine(BuilderFacts(start, working = false)))
        assertEquals(
            "Builder health: quiet 12 min but computing (CPU +40 s in the last 5 min); last step 14 min ago: Agent: Build agents page.",
            healthLine(facts(quiet = 12 * minute, cpuMoved = minute)),
        )
        assertEquals(
            "Builder health: stalled: no output and no CPU for 11 min; last step 13 min ago: Agent: Build agents page; 2 messages wait behind it.",
            healthLine(facts(quiet = 11 * minute, cpuMoved = 30 * minute, queued = 2)),
        )
        assertEquals("Builder health: working, last output 1 min ago; last step 3 min ago: Agent: Build agents page.", healthLine(facts(quiet = minute, cpuMoved = null)))
        assertEquals(
            "Builder health: dead: its process is gone but its task, started 60 min ago, is still marked working; last step 3 min ago: Agent: Build agents page.",
            healthLine(facts(quiet = minute, cpuMoved = null, pid = null)),
        )
        assertTrue(healthLine(facts(quiet = 5 * minute, cpuMoved = 20 * minute))!!.startsWith("Builder health: quiet 5 min, CPU barely moving (+0 s in the last 5 min); stalled if it stays so for 10 min;"))
        assertEquals("Builder health: idle, but 1 leftover builder process from earlier tasks still running.", healthLine(BuilderFacts(start, working = false, leftovers = listOf(row(501)))))
        assertTrue(healthLine(facts(quiet = minute, cpuMoved = null, leftovers = listOf(row(1), row(2))))!!.endsWith("; also 2 leftover builder processes from earlier tasks."))
        // While a step waits for Boss, only the tool's name is told: nothing of what he is asked to approve.
        val waiting = facts(quiet = 45 * minute, cpuMoved = null, approval = true).copy(lastStep = "Bash: rm -rf build && curl -H 'Authorization: Bearer x'")
        assertEquals("Builder health: waiting for Boss to approve a step; nothing is wrong; last step 47 min ago: Bash.", healthLine(waiting))
        // A long step is cut.
        assertTrue(healthLine(facts(quiet = minute, cpuMoved = null).copy(lastStep = "Bash: " + "x".repeat(500)))!!.length < 260)
    }

    @Test fun theBoardCarriesTheLineAfterTheBuildersOwnAndIsUnchangedWithoutIt() {
        val board = BuildBoard(working = true, label = "Using Agent", request = "make the agents page", spokeAgo = 46 * minute)
        assertEquals(
            "[Board, for you only; Boss does not see it]\nBuilder: working (Using Agent) on \"make the agents page\".\nBoss last spoke to the builder 46 min ago.\n[Boss says]",
            boardText(board),
        )
        assertEquals(
            "[Board, for you only; Boss does not see it]\nBuilder: working (Using Agent) on \"make the agents page\".\nBuilder health: stalled: no output and no CPU for 11 min.\nBoss last spoke to the builder 46 min ago.\n[Boss says]",
            boardText(board.copy(health = "Builder health: stalled: no output and no CPU for 11 min.")),
        )
    }

    @Test fun whatARestartAndACleanSay() {
        assertEquals("Restarted the builder: it had been stalled 11 min.", restartedText(facts(quiet = 11 * minute, cpuMoved = 30 * minute), 0))
        assertEquals("Restarted the builder: its process had gone. The message that waited behind it was not sent; say it again.", restartedText(facts(quiet = minute, cpuMoved = null, pid = null), 1))
        assertTrue(restartedText(facts(quiet = 11 * minute, cpuMoved = 30 * minute), 3).endsWith(" The 3 messages that waited behind it were not sent; say them again."))
        assertEquals("No leftover builder processes were found.", cleanedText(0, 0))
        assertEquals("Ended 1 leftover builder process.", cleanedText(1, 1))
        assertEquals("Ended 2 leftover builder processes.", cleanedText(2, 2))
        assertEquals("Ended 1 of 2 leftover builder processes; the rest would not end.", cleanedText(2, 1))
    }

    @Test fun theStatusFileHasTheFactsAndNothingOfAnApprovalOrAKey() {
        val now = start + 60 * minute
        val approval = MacApproval("req-1", "nonce-77", "Bash", JSONObject().put("command", "curl -H 'Authorization: Bearer S3cret'").toString(), "to fetch")
        val thread = MacThread("t5e121ba2", "FF4 better menu mod", "/Users/boss/ff4", ThreadHome.Mac, "claude", "y".repeat(400), MacThreadStatus.Waiting, (now - 2 * minute) / 1000, "it said " + "z".repeat(400), 1, 9, listOf(approval))
        val facts = facts(quiet = 11 * minute, cpuMoved = 30 * minute, approval = false).copy(request = "make the agents page " + "x".repeat(500), leftovers = listOf(row(501, startedAt = now - 120 * minute, cpuMs = 9_000)))
        val status = agentsStatus(facts, MacView(listOf(thread), reachable = true, seen = now - minute), "2026-10-13 21:40:12")
        val builder = status.getJSONObject("builder")
        assertEquals("stalled", builder.getString("verdict"))
        assertEquals(HEALTH_TEXT_CHARS + 3, builder.getString("request").length)
        assertEquals(60, builder.getLong("startedMinAgo")); assertEquals(11, builder.getLong("lastOutputMinAgo")); assertEquals(13, builder.getLong("lastStepMinAgo"))
        assertEquals(699, builder.getInt("pid")); assertTrue(builder.getBoolean("alive"))
        assertFalse(builder.getBoolean("waitingForApproval")); assertEquals(0, builder.getInt("followUpsWaiting"))
        assertEquals(501, builder.getJSONArray("leftovers").getJSONObject(0).getInt("pid"))
        assertEquals(120, builder.getJSONArray("leftovers").getJSONObject(0).getLong("startedMinAgo"))
        val mac = status.getJSONObject("mac")
        val listed = mac.getJSONArray("threads").getJSONObject(0)
        assertEquals("waiting", listed.getString("status")); assertEquals(1, listed.getInt("approvalsWaiting")); assertEquals(2, listed.getLong("lastWorkedMinAgo"))
        assertTrue(listed.getString("asked").length <= HEALTH_TEXT_CHARS + 3); assertEquals(HEALTH_TEXT_CHARS, listed.getString("lastSaid").length)
        val text = status.toString()
        for (never in listOf("S3cret", "Bearer", "nonce", "req-1", "curl")) assertFalse(never, text.contains(never))
        // No Mac set up: nothing of one.
        assertFalse(agentsStatus(facts, null, "x").has("mac"))
    }

    @Test fun theGuestCommandOnlyPrintsTheFile() {
        val script = moduleFile("src/main/assets/oma-agents.sh")
        val home = Files.createTempDirectory("oma-agents-test").toFile()
        try {
            fun run(vararg arguments: String): Pair<Int, String> {
                val process = ProcessBuilder(listOf("/bin/sh", script.path) + arguments).apply { environment()["HOME"] = home.path; redirectErrorStream(true) }.start()
                val out = process.inputStream.bufferedReader().readText()
                return process.waitFor() to out
            }
            assertEquals(1, run("status").first)
            File(home, AGENTS_STATUS_FILE).apply { parentFile.mkdirs() }.writeText("{\n  \"builder\": {}\n}\n")
            val (code, out) = run("status")
            assertEquals(0, code)
            assertTrue(out, out.matches(Regex("Written \\d+ s ago; times in it are minutes before then.\n\\{\n  \"builder\": \\{}\n}\n")))
            for (other in listOf(arrayOf(), arrayOf("clean"), arrayOf("status", "--kill"))) assertEquals(2, run(*other).first)
        } finally { home.deleteRecursively() }
    }
}
