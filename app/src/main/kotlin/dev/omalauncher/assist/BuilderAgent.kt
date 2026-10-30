package dev.omalauncher.assist

import dev.omalauncher.connections.AgentNeed
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent

const val PHONE_D_CODEX_MODEL = "gpt-6.1-terra"

const val PHONE_B_CODEX_MODEL = "gpt-6.1-sol"

/** The provider and model for one request; choosing a provider does not change its permission policy. */
data class BuilderAgent(val agent: Agent, val model: String? = null, val note: String? = null)

/** The agent each builder runs on its own, which [builderAgent] looks for first (D with no other choice). */
val BUILDER_OWN_AGENT = mapOf(BuilderId.B to "codex", BuilderId.C to "claude", BuilderId.D to "codex")

/**
 * What this phone needs installed, read from the choices above: each builder's own agent, however it falls back when it
 * is missing (Phone B falling back to another agent doesn't make Codex any less needed), and the front agent Boss chose.
 */
fun agentNeeds(front: FrontAgent): List<AgentNeed> {
    val by = linkedMapOf<String, MutableList<String>>()
    for ((builder, bin) in BUILDER_OWN_AGENT) by.getOrPut(bin) { mutableListOf() } += "Phone ${builder.letter}"
    by.getOrPut(front.bin) { mutableListOf() } += "the front agent"
    return by.map { (bin, who) -> AgentNeed(bin, AGENTS.firstOrNull { it.bin == bin }?.name ?: bin.replaceFirstChar { it.uppercase() }, who) }
}

/**
 * A keeps the launcher default; B uses the installed Codex and C the installed Claude Code (Boss, 2026-10-24), each
 * with a visible fallback to the default when its own is absent.
 */
fun builderAgent(id: BuilderId, installed: List<Agent>, default: Agent?): BuilderAgent? {
    // D never falls back to another provider or model. It has no owner-facing model picker.
    if (id == BuilderId.D) return installed.firstOrNull { it.bin == BUILDER_OWN_AGENT.getValue(BuilderId.D) }?.let { BuilderAgent(it, PHONE_D_CODEX_MODEL) }
    if (id == BuilderId.B) {
        installed.firstOrNull { it.bin == BUILDER_OWN_AGENT.getValue(BuilderId.B) }?.let { return BuilderAgent(it, PHONE_B_CODEX_MODEL) }
        return default?.let { BuilderAgent(it, note = "Codex is not installed. Phone B is using ${it.name}.") }
    }
    if (id == BuilderId.C) {
        installed.firstOrNull { it.bin == BUILDER_OWN_AGENT.getValue(BuilderId.C) }?.let { return BuilderAgent(it) }
        return default?.let { BuilderAgent(it, note = "Claude Code is not installed. Phone C is using ${it.name}.") }
    }
    return default?.let { BuilderAgent(it) }
}

/**
 * [builderAgent] for a task about to start: when none is found, [rescan] re-reads the installed list from disk once (an
 * agent installed from a shell is not seen until Soma restarts) and the look is repeated.
 */
fun builderAgentFresh(id: BuilderId, find: () -> BuilderAgent?, rescan: () -> Unit): BuilderAgent? = find() ?: run { rescan(); find() }

/** What is wrong when [builderAgent] finds nothing: the agent that builder needs, by name, not a general sign-in line. */
fun missingBuilderAgentMessage(id: BuilderId): String = when (id) {
    BuilderId.D -> "Phone D needs Codex, which isn't installed on the phone."
    BuilderId.B -> "Phone B needs Codex (or another agent), and none is installed on the phone."
    BuilderId.C -> "Phone C needs Claude Code (or another agent), and none is installed on the phone."
    else -> "Install and sign in to an agent in Omalauncher first."
}

/** Why mail for [builder] cannot start, or null when it can (or is not for a builder): the sender should see this, not "Sent". */
fun builderMailRefusal(builder: BuilderId?, find: (BuilderId) -> BuilderAgent?): String? =
    if (builder == null || find(builder) != null) null else missingBuilderAgentMessage(builder)

/** The model a phone builder's Codex runs by default, where Soma knows it: B and D have theirs; a turn from another builder says nothing. */
fun phoneModelOf(builder: String, agent: String): String? = when {
    !agent.contains("codex", ignoreCase = true) -> null
    builder == BuilderId.D.letter -> PHONE_D_CODEX_MODEL
    builder == BuilderId.B.letter -> PHONE_B_CODEX_MODEL
    else -> null
}
