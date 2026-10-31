package dev.omalauncher.connections

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The fleet as the runners will report it, through serviceInventoryReadings and the rows and incidents the widget shows: the Mac Pro,
 * the MacBook and MG1 (Linux, no checks for most services, no Android SDK), with the phone. Simulated /services replies; no network.
 */
class RunnerFleetTest {
    private val now = 1_800_000_000_000L
    private fun check(name: String, result: String, code: String = "x", capability: String? = null, vararg fields: Pair<String, Any>) =
        JSONObject().put("check", name).put("code", code).put("result", result)
            .put("fields", JSONObject().also { o -> capability?.let { o.put("capability", it) }; fields.forEach { o.put(it.first, it.second) } })
    private fun service(id: String, vararg checks: JSONObject, installed: Boolean = true) = JSONObject().put("id", id).put("state", "running").put("pid", 1).put("installed", installed)
        .put("restart", JSONObject().put("supported", true))
        .put("probe", JSONObject().put("checked_at", now).put("checks", JSONArray(checks.toList())))
    private fun reply(host: String, vararg services: JSONObject) = ConnectionReply(200, JSONObject().put("ok", true).put("host", host).put("checked_at", now)
        .put("services", JSONArray(services.toList())).toString())
    private fun pass(name: String, cap: String? = null) = check(name, "pass", "ok", cap)

    /** A Mac whose runner has the new checks. [github]: pass | fail | unverified. */
    private fun mac(host: String, github: String, builds: Boolean = true) = reply(host.lowercase().replace(" ", "-"),
        service("runner", pass("runner_self"), check("github_access", github, "github_" + github, "GitHub"), check("disk_space", "pass", "ok", "Disk", "free_gb" to 80.0),
            *(if (builds) arrayOf(pass("build_toolchain.jdk", "Builds"), pass("build_toolchain.android_sdk", "Builds")) else emptyArray())),
        service("whisper", pass("whisper_model")), service("embeddings", pass("embed_model")), service("herd", pass("herd_reporter")),
        service("canvas", pass("canvas_health")), service("tailshare-serve", pass("tailshare-serve_identity")),
        service("imessage-api", pass("imessage_health"), check("imessage_incoming", "not_checked", "cant_tell", "Messages", "detail" to "Can't tell whether messages arrive.")),
        service("soma", pass("soma_identity")), service("tailscaled", pass("tailnet_backend")))

    /** MG1: Linux, the runner has no Android SDK, and no other service is read. */
    private fun mg1() = reply("mg1",
        service("runner", pass("runner_self"), check("github_access", "pass", "github_ok", "GitHub"), check("disk_space", "pass", "ok", "Disk", "free_gb" to 120.0),
            pass("build_toolchain.jdk", "Builds").put("result", "not_installed"), check("build_toolchain.android_sdk", "not_installed", "no_sdk", "Builds")),
        *listOf("whisper", "embeddings", "herd", "canvas", "tailshare-serve", "imessage-api", "soma", "tailscaled").map { service(it, check("not_checked", "not_checked", "not_checked_on_this_host")) }.toTypedArray())

    private fun snapshot(github: Map<String, String>): ConnectionsSnapshot {
        val readings = mutableListOf<ConnectionReading>()
        readings += serviceInventoryReadings(mac("Mac Pro", github.getValue("Mac Pro")), "Mac Pro", "mac-pro", now)
        readings += serviceInventoryReadings(mac("MacBook", github.getValue("MacBook")), "MacBook", "macbook", now)
        readings += serviceInventoryReadings(mg1(), "MG1", "mg1", now)
        readings += ConnectionReading(Capability.Network, ConnectionState.Ready, "route", now, "network", "Mac Pro", "mac-pro")
        readings += ConnectionReading(Capability.Phone, ConnectionState.Ready, "bound", now)
        readings += ConnectionReading(Capability.Mail, ConnectionState.Ready, "no mail lost", now)
        readings += githubReading("github_ok" to "pass", now)
        readings += diskReading(50L * 1024 * 1024 * 1024, now)
        readings += phoneModelReadings(listOf(InstalledAgent("codex", "Codex")), emptyList(), emptyList(), now)
        return ConnectionsSnapshot(readings, now)
    }
    private fun row(s: ConnectionsSnapshot, c: Capability) = s.rows(now).single { it.capability == c }

    @Test fun oneLinuxHostThatCantTellNeverTurnsAnotherHostsReadyRowsUnknownAndMessagesStaysReady() {
        val s = snapshot(mapOf("Mac Pro" to "pass", "MacBook" to "pass"))
        for (c in listOf(Capability.Voice, Capability.Search, Capability.Activity, Capability.Files, Capability.Canvas, Capability.Messages, Capability.Network))
            assertEquals(c.name, ConnectionState.Ready, row(s, c).state)
        // MG1's own not-checked words are kept in the row's detail, not lost.
        assertTrue(row(s, Capability.Voice).detail, row(s, Capability.Voice).detail.contains("isn't checked on MG1 yet"))
        assertTrue(row(s, Capability.Messages).detail, row(s, Capability.Messages).detail.contains("Can't tell"))
        assertTrue(s.incidents(now).isEmpty())
    }
    @Test fun aServiceNoHostCouldCheckReadsNotCheckedNotPassing() {
        val only = ConnectionsSnapshot(serviceInventoryReadings(mg1(), "MG1", "mg1", now), now)
        val voice = only.rows(now).single { it.capability == Capability.Voice }
        assertEquals(ConnectionState.NotChecked, voice.state)
        assertEquals("– Not checked", connectionStatus(voice, emptyList()).text)
        assertTrue(only.incidents(now).isEmpty())
    }
    @Test fun anAndroidFreeLinuxHostsBuildsAreNotInstalledNotReady() {
        val builds = serviceInventoryReadings(mg1(), "MG1", "mg1", now).filter { it.capability == Capability.Builds }
        assertTrue(builds.all { it.state == ConnectionState.Disabled })
        assertEquals(ConnectionState.Disabled, ConnectionsSnapshot(builds, now).rows(now).single { it.capability == Capability.Builds }.state)
    }
    @Test fun aMacsExpiredGithubLoginIsOneIncidentNamingItAndNeverHiddenByOthersPassing() {
        val s = snapshot(mapOf("Mac Pro" to "fail", "MacBook" to "pass"))
        assertEquals(ConnectionState.Failed, row(s, Capability.GitHub).state)
        val incident = s.incidents(now).single()
        assertEquals(Capability.GitHub, incident.readings.single().capability)
        assertEquals("Mac Pro: GitHub login expired.\nRun gh auth login on it.", incidentCopy(incident, s, now).headline)
        assertTrue(incident.ownerOnly)
        assertEquals("Mac Pro: GitHub login expired. Run gh auth login.", incident.readings.single().detail)
    }
    @Test fun gitHubIsNotReadyUntilEveryDeviceHasReported() {
        // The phone says Ready, but the runners have not sent github_access yet: the row must not claim the Macs.
        val old = { host: String -> reply(host.lowercase().replace(" ", "-"), service("runner", pass("runner_self"))) }
        val readings = mutableListOf<ConnectionReading>()
        readings += serviceInventoryReadings(old("Mac Pro"), "Mac Pro", "mac-pro", now)
        readings += serviceInventoryReadings(old("MacBook"), "MacBook", "macbook", now)
        readings += githubReading("github_ok" to "pass", now)
        val s = ConnectionsSnapshot(readings, now)
        val github = s.rows(now).single { it.capability == Capability.GitHub }
        assertEquals(ConnectionState.NotChecked, github.state)
        assertTrue(github.detail, github.detail.contains("Mac Pro: not checked yet"))
        assertTrue(s.incidents(now).isEmpty())
        // Once every device reported, it can be Ready.
        assertEquals(ConnectionState.Ready, row(snapshot(mapOf("Mac Pro" to "pass", "MacBook" to "pass")), Capability.GitHub).state)
    }
    @Test fun allReadyIsReachableWhenEveryDeviceReportsFineAndNeutralRowsAreNotClaimedAsPassing() {
        val s = snapshot(mapOf("Mac Pro" to "pass", "MacBook" to "pass"))
        val blocking = s.rows(now).filterNot { it.settled }.map { it.capability to it.state }
        // What is left is only what no device could really check: it reads as not checked, never as ready.
        assertTrue(blocking.toString(), blocking.all { (_, state) -> state in setOf(ConnectionState.Unknown, ConnectionState.NotChecked) })
        assertTrue(blocking.toString(), blocking.map { it.first }.toSet() - setOf(Capability.Optional, Capability.Builds, Capability.Sync) == emptySet<Capability>())
        // Give those their one honest reading each and the header can say all ready.
        val full = ConnectionsSnapshot(s.readings + listOf(Capability.Optional, Capability.Builds, Capability.Sync).filter { c -> blocking.any { it.first == c } }.map {
            ConnectionReading(it, ConnectionState.Ready, "checked", now, checks = listOf(ConnectionCheck("soma_sync", "mac", result = "pass"))) }, now)
        assertTrue(full.rows(now).filterNot { it.settled }.toString(), full.rows(now).all { it.settled })
    }
    @Test fun servicesNamedAsHostsAreNotDevicesSoGitHubAndDiskCanBeReadyAndAllSettled() {
        val s = snapshot(mapOf("Mac Pro" to "pass", "MacBook" to "pass"))
        // The monitor's configured checks put a service's name in the host (Whisper, Herd, Embeddings, Tailshare, Canvas) with the Mac's source host.
        val withServices = ConnectionsSnapshot(s.readings + listOf("Whisper", "Herd", "Embeddings", "Tailshare", "Configured Design Canvas").map {
            ConnectionReading(Capability.Network, ConnectionState.Ready, "Service route responded.", now, "network", it, "mac-pro") }, now)
        assertEquals(ConnectionState.Ready, row(withServices, Capability.GitHub).state)
        assertEquals(ConnectionState.Ready, row(withServices, Capability.Disk).state)
        assertTrue(withServices.rows(now).filter { it.capability in setOf(Capability.GitHub, Capability.Disk) }.all { it.settled })
        // A real failure still counts: the Mac Pro failing is one incident.
        val failing = snapshot(mapOf("Mac Pro" to "fail", "MacBook" to "pass"))
        val fails = ConnectionsSnapshot(failing.readings + ConnectionReading(Capability.Network, ConnectionState.Ready, "route", now, "network", "Whisper", "mac-pro"), now)
        assertEquals(1, fails.incidents(now).size)
        assertEquals(ConnectionState.Failed, row(fails, Capability.GitHub).state)
    }
}
