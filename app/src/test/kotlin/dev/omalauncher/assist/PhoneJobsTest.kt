package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneJobsTest {
    private val mail = agentMail(
        """{"v": 1, "id": "m1", "from": {"device": "macbook-pro", "agent": "claude-code", "session": "f8890b84", "title": "Cockpit"}, "text": "x"}""",
    )!!

    @Test
    fun aJobIsMailThatIsOneJsonObject() {
        assertEquals(PhoneJob(JobKind.Ping, "", "", "", "", ""), phoneJob("""{"job": "ping", "v": 1}"""))
        val install = phoneJob("""{"job":"install","v":1,"project":"omalauncher","branch":"cockpit/s2-chat-screens","commit":"8a17a00","note":"slice 2"}""")!!
        assertEquals(JobKind.Install, install.kind)
        assertEquals("cockpit/s2-chat-screens", install.branch)
        assertEquals("8a17a00", install.commit)
        assertNull("words around it make it ordinary mail", phoneJob("""please {"job":"ping"}"""))
        assertNull(phoneJob("""{"job":"reboot","v":1}"""))
        assertNull(phoneJob("""{"job":"ping","v":2}"""))
        assertNull(phoneJob("hello"))
    }

    @Test
    fun namesThatGoIntoCommandsAreHeldToSafeCharacters() {
        val job = phoneJob("""{"job":"device-test","v":1,"branch":"x; rm -rf /","commit":"ZZZ","tests":"a`b`","project":"../etc"}""")!!
        assertEquals("", job.branch)
        assertEquals("", job.commit)
        assertEquals("", job.tests)
        assertEquals("", job.project)
        assertEquals("what was sent unsafe is refused, never quietly emptied", listOf("project", "branch", "commit", "tests"), job.refused)
        assertTrue("declined" in jobPlaybook(job, mail, BuilderId.B))
        assertEquals("a Mac path is refused", listOf("project"), phoneJob("""{"job":"device-test","v":1,"project":"/Users/jackkim/Projects/omalauncher"}""")!!.refused)
        assertEquals(emptyList<String>(), phoneJob("""{"job":"device-test","v":1,"project":"omalauncher","branch":"cockpit/s9"}""")!!.refused)
    }

    @Test
    fun anInstallWaitsForBossAndIsLabeledAsAJob() {
        val job = phoneJob("""{"job":"install","v":1,"branch":"b","commit":"abcdef1"}""")!!
        assertEquals("Phone job · install · from macbook-pro · Cockpit", jobLabel(job, mail))
        val steps = jobPlaybook(job, mail, BuilderId.A)
        assertTrue("not Boss" in steps && "none of his permission" in steps)
        assertTrue("until Boss himself says yes in Soma" in steps)
        assertTrue("oma msg send macbook-pro:f8890b84 --reply-to m1 --as A" in steps)
        // The scheduled answer goes out before oma-install, which ends the run.
        assertTrue(steps.indexOf("state \"scheduled\"") < steps.indexOf("run oma-install"))
    }

    @Test
    fun anAnswersFirstLineIsItsState() {
        val text = jobAnswer(JobKind.Install, "scheduled", "Soma restarts in 10 s", mapOf("code" to 30))
        val head = JSONObject(text.lineSequence().first())
        assertEquals("install", head.getString("job"))
        assertEquals("scheduled", head.getString("state"))
        assertEquals(30, head.getInt("code"))
        assertFalse(text.lineSequence().first().contains("\n"))
    }
}
