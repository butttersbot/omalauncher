package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacProjectsRegistryTest {
    private val registry = registryFrom(JSONObject("""
        {"version": 1, "projects": [
          {"name": "omalauncher", "folders": ["~/ClaudeSync/projects/omalauncher", "~/Projects/omalauncher", "~/Projects/omalauncher-*"], "handoff": "CURRENT-STATE.md"},
          {"name": "pond", "folders": ["~/ClaudeSync/projects/pond"], "handoff": "HANDOFF.md"},
          {"name": "pond-ios", "folders": ["~/ClaudeSync/projects/pond/ios"]},
          {"name": "evil", "folders": ["/etc"], "handoff": "../../etc/passwd"},
          {"name": "Bad Name", "folders": ["~/x"]},
          {"name": "omalauncher", "folders": ["~/dup"]},
          "not an object"
        ]}
    """))

    @Test fun theRegistryReadsWhatItCanAndRefusesOddHandoffs() {
        assertEquals(listOf("omalauncher", "pond", "pond-ios", "evil"), registry.map { it.name })
        assertEquals("CURRENT-STATE.md", registry[0].handoff)
        assertNull("a handoff that isn't HANDOFF.md or CURRENT-STATE.md is never kept", registry[3].handoff)
        assertEquals(registry, registryFrom(JSONObject(registryText(registry))))
        assertTrue(registryFrom(null).isEmpty() && registryFrom(JSONObject("{}")).isEmpty())
    }

    /** The alias merge macProjectKey needs: every folder of omalauncher is one project, whichever way the home is written. */
    @Test fun aliasesAndWorktreesAreOneProject() {
        for (folder in listOf("/Users/jackkim/Projects/omalauncher", "~/Projects/omalauncher/", "/Users/jackkim/Projects/omalauncher-fold", "/Users/boss/Projects/omalauncher-s8/app/src",
            "~/ClaudeSync/projects/omalauncher/cockpit", "/home/neetzy/Projects/omalauncher-c2"))
            assertEquals(folder, "omalauncher", projectOf(folder, registry))
        // The pattern needs something after the dash, and never reaches past the folder it names.
        assertEquals("omalauncherx", projectOf("/Users/jackkim/Projects/omalauncherx", registry))
        assertEquals("omalauncher-", projectOf("/Users/jackkim/Projects/omalauncher-", registry))
        // Doubled slashes and .. steps are resolved before comparing, as herd does.
        assertEquals("omalauncher", projectOf("/Users/jackkim/Projects//omalauncher-c2/", registry))
        assertEquals("pond", projectOf("~/ClaudeSync/projects/omalauncher/../pond/src", registry))
    }

    @Test fun theDeepestFolderWinsAndTheRestFallBackToTheirName() {
        assertEquals("pond-ios", projectOf("~/ClaudeSync/projects/pond/ios/App", registry))
        assertEquals("pond", projectOf("~/ClaudeSync/projects/pond/src", registry))
        assertEquals("design-lab", projectOf("/Users/jackkim/Projects/design-lab", registry))
        assertEquals("design-lab", projectOf("/Users/jackkim/Projects/design-lab/scratch", registry))
        assertEquals("notch", projectOf("~/ClaudeSync/projects/notch/app/src", registry))
        assertEquals("src", projectOf("/opt/elsewhere/src", registry))
        assertEquals(NO_PROJECT, projectOf("/Users/jackkim/Library/Application Support/Claude/scratch-workspaces/x/scratch-2026-10-16-e9829a", registry))
        assertEquals(NO_PROJECT, projectOf("/Users/jackkim/Documents/Codex/2026-10-16/hey-i-ve-got-another-music-5", registry))
        assertEquals("", projectOf("  ", registry))
        assertEquals(NO_PROJECT, projectOf("/Users/jackkim", registry))
        assertEquals(NO_PROJECT, projectOf("~/", registry))
    }

    @Test fun theRunnersAnswersAreRead() {
        val listed = parseMacProjects(MacReply(200, """{"ok": true, "projects": [{"name": "pond", "folders": ["~/ClaudeSync/projects/pond"], "handoff": "HANDOFF.md"}]}"""))
        assertEquals(MacAnswer.Ok(listOf(RegisteredProject("pond", listOf("~/ClaudeSync/projects/pond"), "HANDOFF.md"))), listed)
        val handoff = parseMacHandoff(MacReply(200, """{"ok": true, "project": "pond", "file": "HANDOFF.md", "updated": 1792200000000, "text": "# Pond\n\n## Where things stand\n\nCards live."}"""))
        assertEquals(MacAnswer.Ok(MacHandoff("pond", "HANDOFF.md", 1792200000000, "# Pond\n\n## Where things stand\n\nCards live.")), handoff)
        // A runner that answers with some other file name is not believed.
        assertTrue(parseMacHandoff(MacReply(200, """{"ok": true, "project": "pond", "file": "/etc/passwd", "updated": 1, "text": "x"}""")) !is MacAnswer.Ok)
        assertTrue(parseMacHandoff(MacReply(404, """{"ok": false, "error": "no such project"}""")) !is MacAnswer.Ok)
        assertEquals("/projects/pond/handoff", MacRequests.handoff("pond").path)
        assertEquals("/projects/..%2Fetc/handoff", MacRequests.handoff("../etc").path)
    }
}
