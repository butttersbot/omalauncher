package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/** A stopped run is ended whole (Boss, 2026-10-14): proot, the agent it started, their children and what proot traces. */
class RunEndTest {
    private fun row(pid: Int, ppid: Int, tracer: Int = 0) = ProcRow(pid, ppid, tracer, 0L, 0L, emptyList(), guest = pid != 10)

    @Test fun theRunIsItsProotAndEverythingUnderOrTracedByIt() {
        val rows = listOf(
            row(10, 1), // proot, the launcher's child
            row(11, 10, tracer = 10), // sh -c
            row(12, 11, tracer = 10), // claude
            row(13, 12, tracer = 10), // a tool it runs
            row(14, 1, tracer = 10), // a build daemon that left its parent, still traced
            row(20, 1), // another builder's proot
            row(21, 20, tracer = 20),
        )
        assertEquals(listOf(10, 11, 12, 13, 14), runTree(rows, 10).map { it.pid })
        assertEquals(listOf(20, 21), runTree(rows, 20).map { it.pid })
        assertEquals(emptyList<Int>(), runTree(rows, 99).map { it.pid })
    }
}
