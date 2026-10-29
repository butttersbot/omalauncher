package dev.omalauncher.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `soma --mesh` (Boss, 2026-10-28: "we build two-way roads"): every missing leg between Somas and runners, by name. */
class MeshTest {
    @Test fun theAuditOf20261029SaysEveryMissingLegByName() {
        // As found: the Mac Pro's runner lets the Fold, the Mac Pro and the MacBook in; the MacBook's and MG1's only the Fold.
        // The Mac Pro's Soma has a key for its own runner alone.
        val seen = listOf(
            RunnerSeen("jacks-mac-pro", keyed = true, accepted = true, lets = setOf("galaxy-z-fold8", "jacks-mac-pro", "macbook-pro")),
            RunnerSeen("macbook-pro", keyed = false, accepted = false, lets = null),
            RunnerSeen("omarchy-mg1", keyed = false, accepted = false, lets = null),
        )
        val gaps = meshGaps("jacks-mac-pro", seen).map { "${it.soma}->${it.runner}" }.toSet()
        assertEquals(setOf("jacks-mac-pro->macbook-pro", "jacks-mac-pro->omarchy-mg1", "omarchy-mg1->jacks-mac-pro"), gaps)
        val fix = meshGaps("jacks-mac-pro", seen).first { it.runner == "macbook-pro" }.fix
        assertEquals("on macbook-pro: OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair jacks-mac-pro", fix)
        val table = meshTable("jacks-mac-pro", seen)
        assertTrue(table, table.lines().first { it.startsWith("jacks-mac-pro (this)") }.contains("MISSING"))
        assertTrue(table, table.lines().first { it.startsWith("macbook-pro") }.trim().split(Regex("\\s+")).take(3) == listOf("macbook-pro", "yes", "?"))
    }

    @Test fun everyRoadBothWaysIsClean() {
        val all = setOf("jacks-mac-pro", "macbook-pro", "omarchy-mg1", "galaxy-z-fold8")
        val seen = listOf("jacks-mac-pro", "macbook-pro", "omarchy-mg1").map { RunnerSeen(it, keyed = true, accepted = true, lets = all) }
        assertTrue(meshGaps("macbook-pro", seen).isEmpty())
        assertTrue(meshTable("macbook-pro", seen).lines().drop(1).none { "MISSING" in it || "?" in it })
    }

    @Test fun thePhonesRunnerAwaitsItsBundleAndIsNoGap() {
        val all = setOf("jacks-mac-pro", "macbook-pro")
        val seen = listOf(RunnerSeen("jacks-mac-pro", true, true, all), RunnerSeen("macbook-pro", true, true, all), RunnerSeen("galaxy-z-fold8", false, false, null, awaiting = true))
        assertTrue(meshGaps("jacks-mac-pro", seen).isEmpty())
        val table = meshTable("jacks-mac-pro", seen)
        assertTrue(table, table.lines().drop(1).all { "awaiting bundle" in it })
        assertTrue(table, table.lines().none { it.startsWith("galaxy-z-fold8") })
    }

    @Test fun aKeyThatIsNoLongerLetInIsAMissingLegToo() {
        val seen = listOf(RunnerSeen("macbook-pro", keyed = true, accepted = false, lets = null))
        assertEquals(listOf("jacks-mac-pro->macbook-pro"), meshGaps("jacks-mac-pro", seen).map { "${it.soma}->${it.runner}" })
    }
}
