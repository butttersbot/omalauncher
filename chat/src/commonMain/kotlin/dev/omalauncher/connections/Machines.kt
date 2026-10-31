package dev.omalauncher.connections

import dev.omalauncher.canvas.HerdMachine
import dev.omalauncher.canvas.HerdSnapshot

/** One line of the Machines section: information only, never an incident and never a change to a status. */
data class MachineRow(val name: String, val state: MachineState, val cpu: String, val ram: String) {
    val text get() = when (state) { MachineState.Offline -> "offline"; MachineState.Unread -> "–"; MachineState.Online -> "CPU $cpu  RAM $ram" }
}
enum class MachineState { Online, Offline, Unread }

/** The machines in the order the sheet shows them, with the tailnet name herd knows each by. */
val MACHINES = listOf("Mac Pro" to "jacks-mac-pro", "MacBook" to "macbook-pro", "MG1" to "omarchy-mg1", "Fold" to "galaxy-z-fold8")

private fun percent(value: Double?) = value?.let { "${it.toInt()}%" } ?: "–"

/**
 * One row per machine. CPU as herd says it (0-100), RAM as used over total in percent, either a dash when the machine can't say; a machine herd
 * reports offline, or does not list, reads offline; herd itself not answering reads a dash (nothing is known, so nothing is claimed).
 */
fun machineRows(snapshot: HerdSnapshot?): List<MachineRow> = MACHINES.map { (label, host) ->
    if (snapshot == null) return@map MachineRow(label, MachineState.Unread, "–", "–")
    val m: HerdMachine? = snapshot.machines.firstOrNull { it.name.equals(host, true) }
    if (m == null || !m.online) MachineRow(label, MachineState.Offline, "–", "–")
    else MachineRow(label, MachineState.Online, percent(m.cpuPercent),
        percent(if (m.ramUsedMb != null && m.ramTotalMb != null && m.ramTotalMb > 0) m.ramUsedMb * 100.0 / m.ramTotalMb else null))
}
