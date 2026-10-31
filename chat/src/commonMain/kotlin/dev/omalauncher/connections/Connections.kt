package dev.omalauncher.connections

/** No transport response is itself proof of the capability it carries. */
enum class ConnectionState(val label: String, val mark: String) {
    Ready("Ready", "✓"), Degraded("Degraded", "~"), Failed("Unavailable", "△"),
    Auth("Auth required", "!"), Unknown("Unknown", "?"), Stale("Stale", "◷"),
    Disabled("Disabled", "–"), Paused("Paused", "Ⅱ"), Sleeping("Sleeping", "z"),
    /** This device can't tell (a Linux host with no checks for it yet, incoming messages in quiet hours): never read as passing, and it never outranks another device's Ready. */
    NotChecked("Not checked", "–")
}
enum class Capability(val title: String, val band: String? = null) {
    Network("Device connections", "Network"),
    /** The runners' own health and the phone's Linux checks: shown under Device connections, not as a row of their own. */
    Agents("Agent runs"),
    /** The agents installed on this phone, each with the health of its last real run, and any a builder needs that is missing. */
    Models("Models", "Models"),
    Phone("Phone control", "Phone"), Sync("Workspace sync", "Sync"),
    Activity("Device activity"), Files("Files & deliveries"), Builds("Builds & installs"),
    Voice("Voice"), Search("Search"), Messages("Messages"), Canvas("Design Canvas", "Canvas"),
    /** One reading per device that pushes: can it reach GitHub, and is its login good. Signing in is Boss's, so Restore never touches it. */
    GitHub("GitHub"),
    /** Free space on each device, in the detail; an incident only when it is low enough to break builds or sync. */
    Disk("Disk space"),
    /** Whether agent mail between devices is really delivered, from the phone's receipts. */
    Mail("Agent mail"),
    Optional("Optional tools")
}
/**
 * How far ahead of this device a computer's clock may stand with its stamp still counting as fresh. A probe stamped by a
 * computer reads a little ahead of the phone the moment its reply lands (28-174 ms measured on all three, 2026-10-30), and
 * every fresh check showed Stale; a stamp further ahead than this is a wrong clock, and stays Stale.
 */
private const val CLOCK_TOLERANCE_MS = 10_000L

/** The row a capability is drawn in: the agent runners' health sits under Device connections. */
val Capability.shown get() = if (this == Capability.Agents) Capability.Network else this

/** The capabilities whose reading comes once per device: the row says Ready only when every device has reported. */
private val PER_DEVICE = setOf(Capability.GitHub, Capability.Disk)

/** An incident only its owner can fix (a login, free space, an agent to install): Restore is not offered for it. */
val ConnectionIncident.ownerOnly get() = readings.isNotEmpty() && readings.all { it.capability in setOf(Capability.GitHub, Capability.Disk, Capability.Mail) ||
    (it.capability == Capability.Models) }

/** Settled: nothing to do about it. An agent with no real run yet is not a failure, so it doesn't keep "everything is ready" from being said. */
val ConnectionReading.settled get() = state == ConnectionState.Ready || state == ConnectionState.Disabled ||
    (capability == Capability.Models && state == ConnectionState.Unknown)

data class ConnectionReading(
    val capability: Capability, val state: ConnectionState, val detail: String,
    val checkedAt: Long, val root: String? = null, val host: String = "", val sourceHost: String = "", val checks: List<ConnectionCheck> = emptyList(),
    /** What a Models line is called: the agent, and the model it ran where that is known. */
    val label: String = "",
) {
    /**
     * What this reading says at [now]. A Ready or Unknown reading with no recent check is Stale. A failure is not: an old
     * Failed, Auth or Degraded reading keeps its word and says when it was last checked, so Stale never replaces or hides it.
     * A stamp from the future (a wrong clock) or none is Stale whatever it said.
     */
    fun current(now: Long): ConnectionReading {
        if (state == ConnectionState.Disabled || state == ConnectionState.Paused) return this
        val wrongClock = checkedAt <= 0 || checkedAt - now > CLOCK_TOLERANCE_MS
        val old = now - checkedAt > FRESH_MS
        if (!wrongClock && !old) return this
        if (!wrongClock && state in FAILURES) return aged(now)
        return copy(state = ConnectionState.Stale, detail = "Last check is no longer current. $detail")
    }
    /** The same reading with when it was last checked in its words, once; its state is untouched. */
    fun aged(now: Long): ConnectionReading =
        if (now - checkedAt <= FRESH_MS || detail.startsWith("Last checked")) this else copy(detail = "Last checked ${ageWords(now - checkedAt)} ago. $detail")
}
/** "3 min", "2 h": how long ago, in a few words. */
fun ageWords(ms: Long): String = when {
    ms < 90_000 -> "${maxOf(1, ms / 1000)} s"
    ms < 90 * 60_000 -> "${(ms + 30_000) / 60_000} min"
    ms < 36 * 3_600_000L -> "${(ms + 1_800_000) / 3_600_000} h"
    else -> "${ms / 86_400_000} d"
}
private const val FRESH_MS = 90_000L
private val FAILURES = setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Degraded)
data class ConnectionIncident(val key: String, val readings: List<ConnectionReading>)
data class ConnectionsSnapshot(val readings: List<ConnectionReading> = emptyList(), val checkedAt: Long = 0) {
    fun rows(now: Long) = Capability.entries.filter { it != Capability.Agents }.map { c ->
        var parts = readings.filter { it.capability.shown == c }.map { it.current(now) }
        // The runners' Unknowns (the phone keeper's protocol check) are not the device connection's own word: they stand aside while anything else has spoken.
        if (c == Capability.Network && parts.any { it.capability == Capability.Network && it.state != ConnectionState.Unknown })
            parts = parts.filterNot { it.capability == Capability.Agents && it.state == ConnectionState.Unknown }
        // Ready outranks a device that can't tell (it is no failure and not a pass): so one Linux host with no checks never turns every row Unknown.
        val priority = listOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Stale, ConnectionState.Degraded,
            ConnectionState.Unknown, ConnectionState.Paused, ConnectionState.Sleeping, ConnectionState.Ready, ConnectionState.NotChecked, ConnectionState.Disabled)
        // An agent with no run yet says nothing about the others' health.
        val judged = if (c == Capability.Models && parts.any { it.state != ConnectionState.Unknown }) parts.filter { it.state != ConnectionState.Unknown } else parts
        var worst = judged.minByOrNull { priority.indexOf(it.state) }
        // A per-device row (GitHub, disk space) can't claim every device from one that reported: a device not heard from is not checked.
        val silent = if (c in PER_DEVICE) deviceHosts().filter { h -> parts.none { it.host == h } } else emptyList()
        if (silent.isNotEmpty() && (worst == null || worst.state in setOf(ConnectionState.Ready, ConnectionState.Disabled, ConnectionState.NotChecked, ConnectionState.Unknown))) {
            worst = ConnectionReading(c, ConnectionState.NotChecked, "", now)
        }
        val said = parts.joinToString("\n\n") { listOf(it.host, it.detail).filter(String::isNotBlank).joinToString(": ") } +
            silent.joinToString("") { "\n\n$it: not checked yet (it has not reported)." }
        worst?.copy(capability = c, detail = said.trim())
            ?: ConnectionReading(c, ConnectionState.Unknown, "Not checked yet.", now)
    }
    /**
     * Every computer a runner has been heard from, by its display name: only readings that carry the runner's own identity check count. Other
     * rows put a service's name in the host (Whisper, Herd, a configured Canvas), and those are not devices that will ever report GitHub or disk.
     */
    private fun deviceHosts() = readings.filter { r -> r.sourceHost.isNotBlank() && r.host.isNotBlank() && r.checks.any { it.name == "runner_self" || it.name == "runner_identity" } }
        .map { it.host }.distinct()
    /** True when no reading is fresh: nothing has been checked lately, which a header must not call fine. */
    fun nothingFresh(now: Long) = readings.none { it.checkedAt > 0 && it.checkedAt - now <= CLOCK_TOLERANCE_MS && now - it.checkedAt <= FRESH_MS }

    fun incidents(now: Long): List<ConnectionIncident> = readings
        .filter { it.root != null && it.state in setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Degraded) }
        .map { it.aged(now) }
        .groupBy { it.root!! }.map { ConnectionIncident(it.key, it.value.distinctBy { r -> r.capability }) }

}

/** Stateful debounce, persisted by the Android host; no recovery inferred from missing/stale data. */
class ConnectionAlerts {
    data class Pending(val since: Long, val announced: Boolean, val lastSeen: Long = since, val checks: Set<String> = emptySet())
    data class Notice(val key: String, val recovered: Boolean)
    val pending = linkedMapOf<String, Pending>()
    fun update(snapshot: ConnectionsSnapshot, now: Long, quiet: Boolean): List<Notice> {
        if (quiet) { pending.clear(); return emptyList() }
        val incidents = snapshot.incidents(now).associateBy { it.key }
        val notices = mutableListOf<Notice>()
        for ((key, incident) in incidents) {
            val old = pending[key]
            val since = old?.since?.takeIf { it <= now && (old.announced || now - old.lastSeen <= 90_000) } ?: now
            val announce = old?.announced != true && now - since >= 60_000
            pending[key] = Pending(since, old?.announced == true || announce, now,
                old?.checks.orEmpty() + incident.readings.map { "${it.capability.name}:${it.host}" })
            if (announce) notices += Notice(key, false)
        }
        for ((key, old) in pending.toMap()) if (key !in incidents) {
            // Only explicit fresh Ready observations of this root's capabilities can recover it.
            val relevant = snapshot.readings.filter { it.root == key }.map { it.current(now) }
            if (relevant.isNotEmpty() && relevant.all { it.state == ConnectionState.Ready } &&
                relevant.map { "${it.capability.name}:${it.host}" }.toSet().containsAll(old.checks)) {
                if (old.announced) notices += Notice(key, true)
                pending.remove(key)
            } else if (!old.announced) pending.remove(key) // unknown interrupts a sustained-failure window
        }
        return notices
    }
}

/** Restore never restarts a runner whose authenticated work inventory cannot be read. */
fun restoreGuard(working: Int?, queued: Int?, controlSupported: Boolean): String? = when {
    working == null || queued == null -> "Blocked: active jobs cannot be verified. No service was restarted."
    working > 0 || queued > 0 -> "Blocked: active or queued work must finish first. No service was restarted."
    !controlSupported -> "Guided repair needed: this host has no supported service-control API. Restart only the affected service on its host, then check again."
    else -> null
}

/** Group a host's dependent connection failures without guessing whether it slept or panicked. */
fun groupConnectionFailures(readings: List<ConnectionReading>, retainedHosts: Set<String> = emptySet()): List<ConnectionReading> {
    val grouped = readings.filter { it.sourceHost.isNotBlank() }.groupBy { it.sourceHost }
    val unavailable = grouped.filterValues { rows ->
        rows.filter { it.state == ConnectionState.Failed && it.root != "network" }.map { it.capability }.distinct().size >= 2 &&
            rows.none { it.state == ConnectionState.Ready }
    }.keys + retainedHosts
    return readings.map { row ->
        if (row.sourceHost in unavailable && row.root != null && row.root != "network") row.copy(root = "host:${row.sourceHost}") else row
    }
}

enum class ConnectionGuide { PhoneControl, Tailscale }
data class ConnectionRepairAdvice(val text: String, val guide: ConnectionGuide? = null)
fun connectionRepairAdvice(snapshot: ConnectionsSnapshot, now: Long, active: Int?, queued: Int?): ConnectionRepairAdvice {
    val incidents = snapshot.incidents(now)
    if (incidents.isEmpty()) return ConnectionRepairAdvice("Check complete. No confirmed failure requires a restart; unknown checks still need evidence. Nothing was restarted.")
    if (incidents.any { it.key == "network" }) return ConnectionRepairAdvice(
        "No verified tailnet route. Inspect Tailscale on this phone first. No connection, setting or service was changed.", ConnectionGuide.Tailscale)
    if (incidents.all { it.key == "phone-binding" }) return ConnectionRepairAdvice(
        "Android accessibility binding needs owner attention. Open Phone control to inspect it. No permission or setting was changed.", ConnectionGuide.PhoneControl)
    if (incidents.all { i -> i.readings.all { it.state == ConnectionState.Auth } }) return ConnectionRepairAdvice(
        "The host is reachable, but authenticated capability is unverified. Check this device's pairing on that host; do not restart a service to bypass authentication.")
    val alternative = if (incidents.any { i -> i.readings.any { it.capability in setOf(Capability.Agents, Capability.Builds) } }) {
        snapshot.readings.filter { it.capability == Capability.Agents && it.current(now).state == ConnectionState.Ready && it.root != "network" }
            .map { it.host }.filter(String::isNotBlank).distinct()
    } else emptyList()
    val fallback = if (alternative.isEmpty()) "" else " Authenticated alternative: ${alternative.joinToString()}. You can choose another computer for a new chat."
    return ConnectionRepairAdvice(restoreGuard(active, queued, false).orEmpty() + fallback)
}
