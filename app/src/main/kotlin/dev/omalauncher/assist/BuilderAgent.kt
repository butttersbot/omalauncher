package dev.omalauncher.assist

import dev.omalauncher.linux.Agent

const val PHONE_D_CODEX_MODEL = "gpt-6.1-terra"

const val PHONE_B_CODEX_MODEL = "gpt-6.1-sol"

/** The provider and model for one request; choosing a provider does not change its permission policy. */
data class BuilderAgent(val agent: Agent, val model: String? = null, val note: String? = null)

/**
 * A keeps the launcher default; B uses the installed Codex and C the installed Claude Code (Boss, 2026-10-24), each
 * with a visible fallback to the default when its own is absent.
 */
fun builderAgent(id: BuilderId, installed: List<Agent>, default: Agent?): BuilderAgent? {
    // D never falls back to another provider or model. It has no owner-facing model picker.
    if (id == BuilderId.D) return installed.firstOrNull { it.bin == "codex" }?.let { BuilderAgent(it, PHONE_D_CODEX_MODEL) }
    if (id == BuilderId.B) {
        installed.firstOrNull { it.bin == "codex" }?.let { return BuilderAgent(it, PHONE_B_CODEX_MODEL) }
        return default?.let { BuilderAgent(it, note = "Codex is not installed. Phone B is using ${it.name}.") }
    }
    if (id == BuilderId.C) {
        installed.firstOrNull { it.bin == "claude" }?.let { return BuilderAgent(it) }
        return default?.let { BuilderAgent(it, note = "Claude Code is not installed. Phone C is using ${it.name}.") }
    }
    return default?.let { BuilderAgent(it) }
}
