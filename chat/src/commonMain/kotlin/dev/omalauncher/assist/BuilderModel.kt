package dev.omalauncher.assist

/*
 * /model in a phone builder's tab (Boss, 2026-10-24: switching models should work for the phone agents as it does in a
 * bot's tab on a computer). A builder runs a new agent process for every request, resuming its conversation, so a
 * /model the agent itself takes lasts only for that one process: Claude Code answers "Set model to … for this session
 * only" and the next request is back on its default, and Codex in exec mode has no slash commands at all. So the phone
 * answers /model itself, keeps the choice per builder and per agent, and starts every later request with it.
 */

/** A /model typed in a builder's tab: [Show] what it runs on, [Choose] a model from the next request, or go back to the default ([model] null). */
sealed interface ModelCommand {
    data object Show : ModelCommand

    data class Choose(val model: String?) : ModelCommand

    /** A name that can't be a model's: what is said back, and nothing is changed. */
    data class Refused(val why: String) : ModelCommand
}

/** What a model may be called: Claude Code's aliases and ids ("opus", "claude-opus-5-5", "sonnet[1m]"), or Codex's ("gpt-6.1-sol"). */
private val MODEL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._:/\\[\\]-]{0,79}")

/** [text] as a /model command, or null when it is anything else and goes to the agent as his words. */
fun modelCommand(text: String): ModelCommand? {
    val words = text.trim().split(Regex("\\s+"))
    val head = words.firstOrNull()?.lowercase() ?: return null
    if (head != "/model" && head != "/models") return null
    if (words.size == 1 || head == "/models") return ModelCommand.Show
    if (words.size > 2) return ModelCommand.Refused("Not switched: give one model name, as in /model sonnet.")
    val name = words[1]
    if (name.lowercase() == "default") return ModelCommand.Choose(null)
    if (!MODEL_NAME.matches(name)) return ModelCommand.Refused("Not switched: \"${name.take(40)}\" is not a model name.")
    return ModelCommand.Choose(name)
}

/**
 * What every phone builder is told about /model (Boss, 2026-10-24: agents should know the slash commands). Asked to
 * switch models, an agent tried to do it itself, or offered a new thread, when Boss can type one line in its tab.
 */
const val MODEL_COMMAND_RULE =
    "Boss switches the model of a phone builder (Phone A, B, C) himself by typing `/model NAME` in its tab, from his next " +
        "message on, in the same conversation; `/model` alone shows what it runs and the choices, `/model default` goes " +
        "back. Soma answers these, not you, and you cannot switch your own model. It switches models within the tab's " +
        "agent only: Phone C runs Claude Code (opus, sonnet, haiku, fable), Phone B runs Codex, Phone A the default agent. " +
        "When he asks you to " +
        "switch models, tell him the exact /model line to type; for Codex, that is Phone B's tab."

/** The models offered for [bin] by name; any other name the agent knows works too. */
fun knownModels(bin: String): List<String> = when (bin) {
    "claude" -> listOf("opus", "sonnet", "haiku", "fable")
    "codex" -> listOf(PHONE_B_CODEX_MODEL_NAME)
    else -> emptyList()
}

/** Phone B's own Codex model, named here as well so the list above needs nothing of the app. */
const val PHONE_B_CODEX_MODEL_NAME = "gpt-6.1-sol"

/** Whether a builder running [bin] can be started on a model of his choice. Grok's builder takes no model. */
fun modelSwitchable(bin: String) = bin == "claude" || bin == "codex"

/**
 * The model a builder's next request starts on: an errand's own, else what he chose with /model for this agent, else
 * the builder's own default ([given], Phone B's Codex model), else none, which is the agent's default.
 */
fun builderModel(errand: String?, chosen: String?, given: String?): String? = errand ?: chosen ?: given

/** The words of the model on a command line: as it is when that is safe, quoted otherwise. */
fun modelArg(model: String): String = if (Regex("[A-Za-z0-9._:-]+").matches(model)) model else dev.omalauncher.linux.shellQuote(model)

/**
 * What the tab says back to [command], for [builder] running [agent] (its name, "Claude Code") as [bin]. [chosen] is
 * the model he chose before, [given] the builder's own default. A refusal changes nothing.
 */
fun modelReply(command: ModelCommand, builder: BuilderId, agent: String, bin: String, chosen: String?, given: String?): String {
    val name = builder.title
    if (!modelSwitchable(bin)) return "$name runs $agent, whose model can't be switched from here."
    val running = chosen ?: given
    fun now(model: String?) = model?.let { "$agent on $it" } ?: "$agent on its default model"
    val offered = knownModels(bin).takeIf { it.isNotEmpty() }?.let { " Models: ${it.joinToString(", ")}, or any model id $agent knows." }.orEmpty()
    return when (command) {
        ModelCommand.Show -> "$name runs ${now(running)}." + offered + "\nType /model NAME to switch from the next message, /model default to go back."
        is ModelCommand.Choose -> if (command.model == null) "Done: from the next message $name runs ${now(given)}."
            else "Done: from the next message $name runs ${now(command.model)}. It goes on with the same conversation."
        is ModelCommand.Refused -> command.why
    }
}
