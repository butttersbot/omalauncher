package dev.omalauncher.assist

import org.junit.Assert.*
import org.junit.Test

/** Which project on the Mac a new chat's words are for, when he is asked (Boss, 2026-10-13: "The plus button should always open up a Mac session"). */
class MacProjectsTest {
    private fun thread(id: String, name: String, project: String, worked: Long?, kind: String = "claude") =
        MacThread(id, name, project, ThreadHome.Mac, kind, "", MacThreadStatus.Idle, worked, "", 0, 0, emptyList())

    private val ff4 = thread("t1", "FF4 better menu mod", "~/ClaudeSync/projects/ffiv-ds-better-menu-mod", 100)
    private val ff4Codex = thread("t2", "FF4 better menu mod · Codex", "/Users/boss/ClaudeSync/projects/ffiv-ds-better-menu-mod/", 300, "codex")
    private val pond = thread("t3", "Pond", "~/Projects/pond", 200)
    private val oma = thread("t4", "Launcher", "~/Projects/omalauncher", null)
    private val projects = macProjects(listOf(ff4, pond, ff4Codex, oma, thread("t5", "Nowhere", " ", 400)))

    @Test fun theFoldersAreTheThreadsProjectsTheOneWorkedInLastFirst() {
        assertEquals(listOf("ffiv-ds-better-menu-mod", "pond", "omalauncher"), projects.map { it.folder })
        // A folder's thread is the one that worked there last, and all its threads' names name it.
        assertEquals(ff4Codex, projects[0].thread); assertEquals(listOf("FF4 better menu mod · Codex", "FF4 better menu mod"), projects[0].names)
        assertEquals(emptyList<MacProject>(), macProjects(emptyList()))
    }

    @Test fun theQuestionIsOneShortLineNamingEveryFolder() {
        assertEquals("Which project on the Mac? ffiv-ds-better-menu-mod, pond, omalauncher", macProjectQuestion(projects))
        assertFalse(macProjectQuestion(projects).contains("\n"))
    }

    @Test fun hisAnswerNamesAFolderByItsNameOrItsThreadsAnyCase() {
        for (said in listOf("ffiv-ds-better-menu-mod", "FFIV DS better menu mod", "ff4", "the FF4 one", "FF4 better menu mod", "on ff4 please", "better menu")) {
            assertEquals(said, "ffiv-ds-better-menu-mod", macPickProject(said, projects)?.folder)
        }
        assertEquals("pond", macPickProject("Pond", projects)?.folder)
        assertEquals("pond", macPickProject("the pond project", projects)?.folder)
        assertEquals("omalauncher", macPickProject("omalauncher", projects)?.folder)
        assertEquals("omalauncher", macPickProject("launcher", projects)?.folder)
    }

    @Test fun anAnswerThatNamesNoneOrSeveralIsAskedAgain() {
        for (said in listOf("", "  ", "hmm", "the one", "whatever you think")) assertNull(said, macPickProject(said, projects))
        assertNull(macPickProject("ff4", emptyList()))
        // Two folders a word of his fits equally: nothing is guessed.
        val two = macProjects(listOf(thread("a", "Menu mod", "~/p/menu-mod", 1), thread("b", "Menu art", "~/p/menu-art", 2)))
        assertNull(macPickProject("menu", two))
        assertEquals("menu-art", macPickProject("menu art", two)?.folder)
    }
}
