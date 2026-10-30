package dev.omalauncher.connections

/**
 * The words Connections shows, as the approved Canvas cards word them (soma-widgets / Soma Connections v6), drawn only
 * from what the readings say: a headline never claims a host is reachable without a fresh transport reading for it.
 */
enum class ConnectionTone { Good, Bad, Quiet }
data class ConnectionStatus(val mark: String, val word: String, val tone: ConnectionTone) {
    val text get() = "$mark $word"
}

/** [card]: the home tile's wording ("Connected"); the expanded inventory says "Ready" for the same reading. */
fun connectionStatus(row: ConnectionReading, incidents: List<ConnectionIncident>, card: Boolean = false): ConnectionStatus {
    val state = row.state
    if (state == ConnectionState.Ready) return ConnectionStatus("✓", when (row.capability) {
        Capability.Network -> if (card) "Connected" else "Ready"
        Capability.Sync, Capability.Canvas -> "Synced"
        Capability.Optional -> "Available"
        else -> "Ready"
    }, ConnectionTone.Good)
    if (state in setOf(ConnectionState.Failed, ConnectionState.Degraded, ConnectionState.Auth)) {
        val primary = incidents.any { it.readings.first().capability == row.capability }
        val involved = incidents.any { i -> i.readings.any { it.capability == row.capability } }
        val word = when {
            state == ConnectionState.Auth -> state.label
            primary -> "Needs repair"
            involved -> "Affected"
            else -> state.label
        }
        return ConnectionStatus(state.mark, word, ConnectionTone.Bad)
    }
    return ConnectionStatus(state.mark, state.label, ConnectionTone.Quiet)
}

private val Capability.noun get() = when (this) {
    Capability.Network -> "device connections"; Capability.Agents -> "agent runs"; Capability.Phone -> "phone control"
    Capability.Sync -> "workspace sync"; Capability.Activity -> "device activity"; Capability.Files -> "file deliveries"
    Capability.Builds -> "builds"; Capability.Voice -> "voice"; Capability.Search -> "search"; Capability.Messages -> "messages"
    Capability.Canvas -> "Design Canvas"; Capability.Optional -> "optional tools"
}
private val Capability.plural get() = this in setOf(Capability.Network, Capability.Agents, Capability.Files, Capability.Builds, Capability.Messages, Capability.Optional)
private val Capability.short get() = when (this) {
    Capability.Network -> "device"; Capability.Agents -> "agent"; Capability.Phone -> "phone control"; Capability.Sync -> "sync"
    Capability.Activity -> "activity"; Capability.Files -> "delivery"; Capability.Builds -> "build"; Capability.Voice -> "voice"
    Capability.Search -> "search"; Capability.Messages -> "messages"; Capability.Canvas -> "Canvas"; Capability.Optional -> "tool"
}
private fun List<String>.spoken(and: String = "and") = when (size) {
    0 -> ""; 1 -> first(); else -> dropLast(1).joinToString(", ") + " $and " + last()
}
private fun String.capital() = replaceFirstChar { it.uppercase() }
private val NUMBERS = listOf("No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve")
private fun counted(n: Int, one: String) = "${NUMBERS.getOrElse(n) { n.toString() }} $one${if (n == 1) "" else "s"}"

/** What the tile's second line says about a failure: the host and, in a few words, what is wrong there. */
fun connectionCause(reading: ConnectionReading, key: String?): String {
    reading.checks.firstNotNullOfOrNull { it.agentTrouble() }?.let { t ->
        return listOf(reading.host.ifBlank { "This phone" }, t.agent, t.words).joinToString(" · ")
    }
    val what = when {
        reading.state == ConnectionState.Auth -> "sign-in unverified"
        reading.state == ConnectionState.Degraded -> "degraded"
        key == "network" -> "no tailnet route"
        key == "phone-binding" -> "phone control unbound"
        key?.startsWith("host:") == true -> "not answering"
        key?.startsWith("runner:") == true -> "runner not responding"
        else -> "not responding"
    }
    return listOf(reading.host.ifBlank { if (key == "network" || key == "phone-binding") "This phone" else "" }, what)
        .filter(String::isNotBlank).joinToString(" · ")
}

data class IncidentCopy(val caption: String, val headline: String, val impact: String, val steps: List<String>, val note: String)

fun incidentCopy(incident: ConnectionIncident, snapshot: ConnectionsSnapshot, now: Long): IncidentCopy {
    val key = incident.key
    val readings = incident.readings
    val capabilities = readings.map { it.capability }.distinct()
    val host = readings.map { it.host }.firstOrNull(String::isNotBlank) ?: "This phone"
    val sources = readings.map { it.sourceHost }.filter(String::isNotBlank).toSet()
    // Only a fresh transport reading to the same machine lets the headline say it is connected.
    val reachable = sources.isNotEmpty() && snapshot.readings.map { it.current(now) }
        .any { it.root == "network" && it.state == ConnectionState.Ready && it.sourceHost in sources }
    val nouns = capabilities.map { it.noun }
    val are = if (capabilities.size > 1 || capabilities.first().plural) "are" else "is"
    val others = snapshot.rows(now).filter { it.capability !in capabilities }
    val rest = if (others.all { it.state == ConnectionState.Ready || it.state == ConnectionState.Disabled }) " Other Soma features are ready."
        else " Some other checks aren't confirmed yet."
    val verify = "Verify ${capabilities.map { it.short }.spoken("&")} connections"
    val note = "One repair. ${counted(capabilities.size, "feature")} back."
    val primary = capabilities.first()
    val trouble = readings.flatMap { it.checks }.firstNotNullOfOrNull { it.agentTrouble() }
    return when {
        // A restart wouldn't help an agent's service, so nothing here says to do one.
        trouble != null -> IncidentCopy("$host / ${trouble.agent}", "${trouble.agent} is failing on $host.\n${trouble.words.capital()}.",
            "Its last turn ended with: ${trouble.error.ifBlank { trouble.words }}. Nothing was restarted.",
            listOf("Read the error", if (trouble.code == "sign_in") "Sign ${trouble.agent} in again" else "Wait for its service", "Send it a message: a good turn clears this"), note)
        key == "network" -> IncidentCopy("This phone / tailnet", "This phone has no tailnet route.\nTailscale needs a look.",
            "Soma can't reach your other devices until it is back. Nothing was changed.",
            listOf("Check Tailscale on this phone", "Reconnect to the tailnet", "Verify device connections"), note)
        key == "phone-binding" -> IncidentCopy("This phone / phone control", "Phone control is unbound.\nAndroid needs your OK.",
            "Agents can't use this phone's screen until it is back. No permission was changed.",
            listOf("Open Phone control", "Turn its accessibility access back on", "Verify phone control"), note)
        readings.all { it.state == ConnectionState.Auth } -> IncidentCopy("$host / sign-in", "$host is reachable.\nIts sign-in needs checking.",
            "${nouns.spoken().capital()} on $host $are unverified.$rest",
            listOf("Check this device's pairing", "Pair again on $host if it changed", verify), note)
        key.startsWith("host:") -> IncidentCopy("$host / whole device", "$host isn't answering.\nIt may be asleep or offline.",
            "${nouns.spoken().capital()} on $host $are unavailable.$rest",
            listOf("Check $host is awake & online", "Wake or reconnect it", verify), note)
        key.startsWith("runner:") -> IncidentCopy("$host / agent runner",
            if (reachable) "Your $host is connected.\nIts runner needs a restart." else "$host's runner isn't responding.\nIt may need a restart.",
            "${nouns.spoken().capital()} on $host $are unavailable.$rest",
            listOf("Check device & active jobs", "Restart the agent runner", verify), note)
        else -> IncidentCopy("$host / ${primary.title}",
            if (reachable) "Your $host is connected.\nIts ${primary.noun} service needs a restart." else "${primary.noun.capital()} on $host isn't responding.",
            "${nouns.spoken().capital()} on $host $are unavailable.$rest",
            listOf("Check device & active jobs", "Restart the ${primary.noun} service", verify), note)
    }
}

/** A monitoring round's change of incidents clears the sheet's line, unless it says what a restart waits on and nothing else needs the sheet: a new incident speaks for itself, and the row keeps saying it. */
fun repairLineStays(repair: String?, incidentsNow: Boolean) = repair?.startsWith("Restarted") == true && !incidentsNow

/** The sheet's line after "Restarted": once a row says real traffic has passed since, the line says so too. */
fun repairAfterTraffic(repair: String?, snapshot: ConnectionsSnapshot): String? {
    val said = repair?.takeIf { it.startsWith("Restarted · ") } ?: return repair
    val (host, service) = said.removePrefix("Restarted · ").substringBefore(". ").split(": ").takeIf { it.size == 2 } ?: return repair
    val confirmed = snapshot.readings.flatMap { it.checks }.any { it.name == "restart_confirmed" && it.result == "pass" && it.fields["service"] == service && it.fields["host"] == host }
    return if (confirmed) "Confirmed · $host: $service. Real traffic has passed since the restart." else repair
}

/** The three bars and steps: which step a repair is on, read from the restorer's own words. -1 idle, 3 all done. */
fun repairStage(repair: String?, checking: Boolean): Int {
    val text = repair.orEmpty()
    return when {
        text.startsWith("Restored", ignoreCase = true) || text.startsWith("Confirmed", ignoreCase = true) -> 3
        text.startsWith("Verifying", ignoreCase = true) || text.startsWith("Restarted", ignoreCase = true) -> 2
        text.startsWith("Owner approval", ignoreCase = true) || text.contains("in progress", ignoreCase = true) -> 1
        checking || text.startsWith("Checking", ignoreCase = true) -> 0
        else -> -1
    }
}
