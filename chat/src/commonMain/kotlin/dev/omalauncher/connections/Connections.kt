package dev.omalauncher.connections

/** No transport response is itself proof of the capability it carries. */
enum class ConnectionState(val label: String, val mark: String) {
    Ready("Ready", "✓"), Degraded("Degraded", "~"), Failed("Unavailable", "△"),
    Auth("Auth required", "!"), Unknown("Unknown", "?"), Stale("Stale", "◷"),
    Disabled("Disabled", "–"), Paused("Paused", "Ⅱ"), Sleeping("Sleeping", "z")
}
enum class Capability(val title: String, val band: String? = null) {
    Network("Device connections", "Network"), Agents("Agent runs", "Agents"),
    Phone("Phone control", "Phone"), Sync("Workspace sync", "Sync"),
    Activity("Device activity"), Files("Files & deliveries"), Builds("Builds & installs"),
    Voice("Voice"), Search("Search"), Messages("Messages"), Canvas("Design Canvas", "Canvas"), Optional("Optional tools")
}
data class ConnectionReading(
    val capability: Capability, val state: ConnectionState, val detail: String,
    val checkedAt: Long, val root: String? = null, val host: String = "", val sourceHost: String = "",
) {
    fun current(now: Long): ConnectionReading = if (state !in setOf(ConnectionState.Disabled, ConnectionState.Paused) &&
        (checkedAt <= 0 || now < checkedAt || now - checkedAt > 90_000)) copy(state = ConnectionState.Stale, detail = "Last check is no longer current. $detail") else this
}
data class ConnectionIncident(val key: String, val readings: List<ConnectionReading>)
data class ConnectionsSnapshot(val readings: List<ConnectionReading> = emptyList(), val checkedAt: Long = 0) {
    fun rows(now: Long) = Capability.entries.map { c ->
        val parts = readings.filter { it.capability == c }.map { it.current(now) }
        val priority = listOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Stale, ConnectionState.Degraded,
            ConnectionState.Unknown, ConnectionState.Paused, ConnectionState.Sleeping, ConnectionState.Ready, ConnectionState.Disabled)
        val worst = parts.minByOrNull { priority.indexOf(it.state) }
        worst?.copy(detail = parts.joinToString("\n\n") { listOf(it.host, it.detail).filter(String::isNotBlank).joinToString(": ") })
            ?: ConnectionReading(c, ConnectionState.Unknown, "Not checked yet.", now)
    }
    fun incidents(now: Long): List<ConnectionIncident> = readings.map { it.current(now) }
        .filter { it.root != null && it.state in setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Degraded) }
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
