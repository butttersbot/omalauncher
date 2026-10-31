package dev.omalauncher.connections

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneChecksTest {
    private val now = 100_000_000L

    @Test fun onlyARefusedLoginIsAGitHubFailureAndItsOutageIsUnreachable() {
        assertEquals("github_ok" to "pass", classifyGithub(0, "abc refs/heads/x", 0, "Everything up-to-date"))
        assertEquals("github_ok" to "pass", classifyGithub(0, "", 1, " ! [rejected] a -> b (fetch first)"))
        assertEquals("github_login_invalid" to "fail", classifyGithub(0, "", 128, "fatal: could not read Username for 'https://github.com': terminal prompts disabled"))
        assertEquals("github_login_invalid" to "fail", classifyGithub(255, "git@github.com: Permission denied (publickey).", null, ""))
        assertEquals("github_login_invalid" to "fail", classifyGithub(128, "Host key verification failed.", null, ""))
        assertEquals("github_login_invalid" to "fail", classifyGithub(128, "fatal: Authentication failed for 'https://github.com/x'", null, ""))
        assertEquals("github_unreachable" to "unverified", classifyGithub(128, "fatal: unable to access: Could not resolve host: github.com", null, ""))
        assertEquals("github_unreachable" to "unverified", classifyGithub(128, "The requested URL returned error: 503", null, ""))
        assertEquals("github_push_untested" to "unverified", classifyGithub(0, "x", null, ""))
    }
    @Test fun githubReadingsNameThePhoneAndTheFixAndNoCheckoutIsNoFailure() {
        val bad = githubReading("github_login_invalid" to "fail", now)
        assertEquals(ConnectionState.Failed, bad.state)
        assertEquals("This phone can't push to GitHub: its SSH key was refused. Check it with ssh -T git@github.com.", bad.detail) // no gh on the phone
        val snapshot = ConnectionsSnapshot(listOf(bad), now)
        val incident = snapshot.incidents(now).single()
        assertTrue(incident.ownerOnly) // signing in is his: Restore is not offered
        assertEquals("This phone can't push to GitHub.\nIts SSH key needs checking.", incidentCopy(incident, snapshot, now).headline)
        assertEquals(ConnectionState.Unknown, githubReading("github_unreachable" to "unverified", now).state) // not an incident
        assertTrue(ConnectionsSnapshot(listOf(githubReading("github_unreachable" to "unverified", now)), now).incidents(now).isEmpty())
        assertEquals(ConnectionState.Disabled, githubReading(null, now).state)
        assertEquals(ConnectionState.Ready, githubReading("github_ok" to "pass", now).state)
    }
    @Test fun diskFreeIsAlwaysSaidAndOnlyLowSpaceIsAnIncident() {
        val gb = 1024L * 1024 * 1024
        assertEquals("This phone has 50.0 GB free.", diskReading(50 * gb, now).detail)
        assertEquals(ConnectionState.Failed, diskReading((1.5 * gb).toLong(), now).state)
        assertTrue(diskReading((1.5 * gb).toLong(), now).detail.contains("low enough"))
        assertEquals(ConnectionState.Ready, diskReading(3 * gb, now).state)
        assertEquals(ConnectionState.Unknown, diskReading(null, now).state)
    }
    @Test fun mailThatWasHandedOffButNeverDeliveredIsAnIncidentNamingItsRoute() {
        fun r(state: String, ago: Long, sender: String = "phone:A@galaxy") = MailReceipt("m", sender, now - ago, state, now - ago)
        assertEquals(ConnectionState.Ready, mailReading(emptyList(), false, now).state)
        // Waiting behind a running task is normal; a minute-old queue is too.
        assertEquals(ConnectionState.Ready, mailReading(listOf(r("queued", 3 * 3_600_000L)), builderBusy = true, now = now).state)
        assertEquals(ConnectionState.Ready, mailReading(listOf(r("queued", 60_000L)), false, now).state)
        val stuck = mailReading(listOf(r("queued", 45 * 60_000L, "mac-pro:t9b152c95")), false, now)
        assertEquals(ConnectionState.Failed, stuck.state)
        assertTrue(stuck.detail, stuck.detail.contains("mac-pro:t9b152c95"))
        val lost = mailReading(listOf(r("not-delivered", 3_600_000L)), false, now)
        assertEquals(ConnectionState.Failed, lost.state)
        assertEquals(ConnectionState.Ready, mailReading(listOf(r("not-delivered", 30 * 3_600_000L), r("delivered", 1000)), false, now).state)
        assertTrue(ConnectionsSnapshot(listOf(stuck), now).incidents(now).single().ownerOnly)
    }
    @Test fun aDeadKeeperIsARealFailureAndAnUnaskableOneIsOnlyUnknown() {
        assertEquals("pass", keeperCheck(0, "Nothing is kept.\n").result)
        assertEquals("fail", keeperCheck(1, "oma keep: the keeper is not running. It starts with the launcher's Linux").result)
        assertEquals("unverified", keeperCheck(null, "").result)
        assertEquals("unverified", keeperCheck(2, "usage").result)
        val dead = keeperReading(keeperCheck(1, "oma keep: the keeper is not running."), now)
        assertEquals(ConnectionState.Failed, dead.state)
        val snapshot = ConnectionsSnapshot(listOf(dead), now)
        assertEquals("phone-keeper", snapshot.incidents(now).single().key)
        assertEquals(ConnectionState.Unknown, keeperReading(keeperCheck(null, ""), now).state)
    }
    private fun inventory(vararg checks: JSONObject) = ConnectionReply(200, JSONObject().put("ok", true).put("host", "macbook").put("checked_at", now)
        .put("services", JSONArray().put(JSONObject().put("id", "runner").put("state", "running").put("probe", JSONObject()
            .put("checks", JSONArray(checks.toList())).put("checked_at", now)))).toString())
    private fun check(name: String, result: String, vararg fields: Pair<String, Any>, capability: String) = JSONObject().put("check", name).put("code", fields.toMap()["code"] ?: "x")
        .put("result", result).put("fields", JSONObject().put("capability", capability).also { o -> fields.forEach { o.put(it.first, it.second) } })

    @Test fun aRunnersGithubAndDiskChecksBecomeTheirOwnRowsNamingTheDevice() {
        val readings = serviceInventoryReadings(inventory(
            JSONObject().put("check", "runner_self").put("code", "ok").put("result", "pass"),
            check("github_access", "fail", "code" to "github_login_invalid", capability = "GitHub"),
            check("disk_space", "pass", "code" to "ok", "free_gb" to 42.5, capability = "Disk")), "MacBook", "macbook", now)
        val github = readings.single { it.capability == Capability.GitHub }
        assertEquals(ConnectionState.Failed, github.state)
        assertEquals("MacBook: GitHub login expired. Run gh auth login.", github.detail)
        assertEquals("MacBook has 42.5 GB free.", readings.single { it.capability == Capability.Disk }.detail)
        val snapshot = ConnectionsSnapshot(readings, now)
        val incident = snapshot.incidents(now).single()
        assertEquals(Capability.GitHub, incident.readings.single().capability)
        assertTrue(incident.ownerOnly)
        assertEquals("MacBook: GitHub login expired.\nRun gh auth login on it.", incidentCopy(incident, snapshot, now).headline)
        assertEquals("MacBook · GitHub login expired", connectionCause(incident.readings.single(), incident.key))
    }
    @Test fun aDeviceWithoutGithubOrAnSdkIsNotAFailureAndGithubOutageIsNotALoginProblem() {
        val readings = serviceInventoryReadings(inventory(
            check("github_access", "not_installed", "code" to "no_github_use", capability = "GitHub"),
            check("github_access", "unverified", "code" to "github_unreachable", capability = "GitHub").put("check", "github_access2")), "MG1", "mg1", now)
        assertTrue(ConnectionsSnapshot(readings, now).incidents(now).isEmpty())
        val unreachable = serviceInventoryReadings(inventory(check("github_access", "unverified", "code" to "github_unreachable", capability = "GitHub")), "MG1", "mg1", now).single()
        assertEquals(ConnectionState.Unknown, unreachable.state)
        assertTrue(unreachable.detail, unreachable.detail.contains("not a login problem"))
    }
    @Test fun incomingMessagesSayCantTellNeverAFalseHealthyAndAHostNotCheckedSaysSo() {
        val cant = serviceInventoryReadings(inventory(check("imessage_incoming", "unverified", "code" to "cant_tell", "detail" to "Can't tell whether messages arrive.", capability = "Messages")), "Mac Pro", "mac", now)
        assertEquals(ConnectionState.Unknown, cant.single { it.capability == Capability.Messages }.state)
        assertTrue(cant.single().detail.startsWith("Can't tell"))
        val down = serviceInventoryReadings(inventory(check("imessage_incoming", "fail", "code" to "watcher_not_running", capability = "Messages")), "Mac Pro", "mac", now).single()
        assertEquals(ConnectionState.Failed, down.state)
        val linux = serviceInventoryReadings(ConnectionReply(200, JSONObject().put("ok", true).put("host", "mg1").put("checked_at", now)
            .put("services", JSONArray().put(JSONObject().put("id", "whisper").put("state", "unknown").put("installed", true).put("probe",
                JSONObject().put("checks", JSONArray().put(JSONObject().put("check", "not_checked").put("code", "x").put("result", "unverified"))).put("checked_at", now)))).toString()), "MG1", "mg1", now).single()
        assertEquals(ConnectionState.Unknown, linux.state)
        assertEquals("whisper isn't checked on MG1 yet.", linux.detail)
    }
}
