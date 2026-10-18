package dev.omalauncher.assist

import dev.omalauncher.linux.shellQuote
import java.io.File

/*
 * Grok as the front agent (Boss, 2026-10-18: the front agent goes from Haiku to Grok, with Haiku kept to switch back).
 * Grok Build's CLI reads and writes as Claude Code does: `--output-format streaming-messages-json` is Claude Code's
 * stream-json, so [quickEvent] reads it unchanged; only the command differs. Its prompt, its marks and what it may
 * run are the ones in QuickLaneRules.kt.
 */

/** Who the front agent is. [bin] is the guest command it runs on, [model] the model it is started with. */
enum class FrontAgent(val bin: String, val model: String) {
    Grok("grok", QUICK_GROK_MODEL),
    Haiku("claude", QUICK_MODEL);

    companion object {
        /**
         * The front agent for what [chosen] says (the word `oma front` wrote, or null when he never chose): Grok unless
         * he chose Haiku, and Haiku whenever Grok can't answer, not installed ([grok] false) or not signed in ([signedIn]
         * false), so a request is never sent to a Grok that would only fail. Null when neither can answer.
         */
        fun pick(chosen: String?, grok: Boolean, signedIn: Boolean, claude: Boolean): FrontAgent? {
            val wanted = if (chosen?.trim()?.lowercase() in HAIKU_WORDS) Haiku else Grok
            return when {
                wanted == Grok && grok && signedIn -> Grok
                claude -> Haiku
                else -> null
            }
        }

        private val HAIKU_WORDS = setOf("haiku", "claude")
    }
}

/** The Grok model the front agent runs on: Grok's fast one, since the front agent's answer is a few seconds' work. */
const val QUICK_GROK_MODEL = "grok-4.7-build-fast"

/** Where `oma front` keeps his choice, in the guest's home. */
const val FRONT_CHOICE = ".config/oma/front-agent"

/** Grok's sign-in, in the guest's home: the front agent is Grok only once it exists. Only its presence is looked at. */
const val GROK_AUTH = ".grok/auth.json"

/** Grok's tools that are [QUICK_TOOLS]: reading files, the shell, the web. Grok names its tools by their ids. */
val GROK_QUICK_TOOLS = listOf("read_file", "run_terminal_cmd", "web_search", "web_fetch")

/** Grok's tools in a new chat, as [QUICK_CHAT_TOOLS]: the web and nothing else. */
val GROK_QUICK_CHAT_TOOLS = listOf("web_search", "web_fetch")

/**
 * What Grok may run without asking, as [QUICK_ALLOWED]: the same Bash commands; reading as its `Read`; and the web as
 * its `WebFetch`. Grok's web search is one of its read-only tools, which run without a rule.
 */
val GROK_QUICK_ALLOWED = QUICK_ALLOWED.filter { it.startsWith("Bash(") } + listOf("Read", "WebFetch")

/** What Grok may run in a new chat: the web alone, as [QUICK_CHAT_TOOLS]. */
val GROK_QUICK_CHAT_ALLOWED = listOf("WebFetch")

/**
 * What the front agent is refused whatever else allows it, since Haiku never had it: an MCP tool (Grok keeps those
 * whatever `--tools` says, and takes servers and allow rules from the guest's Claude settings), changing a file, and
 * the `mkdir` and `touch` its read-only handling would otherwise run without asking. A deny always wins.
 */
val GROK_QUICK_DENIED = listOf("MCPTool", "Edit", "Write", "Bash(mkdir*)", "Bash(touch*)")

/**
 * Where the front agent's Grok runs, in the guest's home on the phone (Soma's folder holds its own on a computer): a
 * folder of its own holding only [GROK_FRONT_HOOK] and [GROK_FRONT_GUARD], given to Grok with `--cwd`.
 */
const val GROK_FRONT_FOLDER = ".cache/oma-front"

/**
 * The front agent's command guard, a `PreToolUse` hook (Grok's own way to allow only some commands; 2026-10-18, seen
 * on the Mac: Claude Code's `"defaultMode": "auto"` in ~/.claude/settings.json outranks `--permission-mode dontAsk`,
 * and Grok then ran `python3 -c`). Only in a run that sets OMA_FRONT_GUARD=1, so no other Grok run is touched: a shell
 * command runs only when it is one plain command that matches a line of OMA_FRONT_ALLOW, and anything it can't read is
 * refused. Grok runs a hook that crashes as if it allowed the call, so nothing here may fail on its own.
 */
const val GROK_FRONT_GUARD = """#!/bin/sh
# Soma: the front agent's command guard. Written by Soma; changes here are replaced.
[ "${'$'}{OMA_FRONT_GUARD:-}" = 1 ] || exit 0
deny() { printf '{"decision":"deny","reason":"%s"}\n' "${'$'}1"; exit 2; }
command -v jq >/dev/null 2>&1 || deny "Only listed commands run here, and they can't be checked without jq."
input=${'$'}(cat) || deny "Unreadable request."
cmd=${'$'}(printf '%s' "${'$'}input" | jq -r '.toolInput.command // .tool_input.command // empty' 2>/dev/null) || deny "Unreadable command."
[ -n "${'$'}cmd" ] || deny "Nothing to run."
case ${'$'}cmd in
  *'
'*|*';'*|*'&'*|*'|'*|*'<'*|*'>'*|*'${'$'}'*|*'`'*|*'('*|*')'*|*'\'*) deny "Only one plain command runs here." ;;
esac
cmd=${'$'}(printf '%s' "${'$'}cmd" | sed 's/^ *//; s/ *${'$'}//')
IFS='
'
for pattern in ${'$'}{OMA_FRONT_ALLOW:-}; do
  case ${'$'}cmd in ${'$'}pattern) exit 0 ;; esac
done
deny "Not a command the front agent may run."
"""

/** The hook file that runs [GROK_FRONT_GUARD] before every shell command, in `.grok/hooks/` of [GROK_FRONT_FOLDER]. */
const val GROK_FRONT_HOOK = """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"oma-front.sh","timeout":5}]}]}}
"""

/** Puts the guard in [folder] (made when missing), rewriting what differs; true when it is there as it should be. */
fun writeGrokGuard(folder: File): Boolean = runCatching {
    val hooks = File(folder, ".grok/hooks").apply { mkdirs() }
    for ((name, text) in listOf("oma-front.json" to GROK_FRONT_HOOK, "oma-front.sh" to GROK_FRONT_GUARD)) {
        val file = File(hooks, name)
        if (!file.isFile || file.readText() != text) file.writeText(text)
        if (name.endsWith(".sh") && !file.canExecute()) file.setExecutable(true)
    }
    File(hooks, "oma-front.sh").canExecute()
}.getOrDefault(false)

/**
 * What the run is told to let the guard check: on, the commands its `Bash(...)` rules in [allowed] name as patterns
 * (one to a line), and Grok's folder trust off so the guard folder's hook loads without asking. Nothing else of the
 * folder is there to load.
 */
fun grokGuardEnv(allowed: List<String>): String =
    "GROK_FOLDER_TRUST=0 OMA_FRONT_GUARD=1 OMA_FRONT_ALLOW=" +
        shellQuote(allowed.filter { it.startsWith("Bash(") && it.endsWith(")") }.joinToString("\n") { it.removePrefix("Bash(").removeSuffix(")") }) + " "

/**
 * The guest command that answers [prompt] with Grok, as [quickCommand] does with Claude: the same prompt, told the same
 * rules ([quickSystemPrompt]) through `--rules`. `dontAsk` runs only what [GROK_QUICK_ALLOWED] allows and refuses the
 * rest without asking, which is what nobody answering its prompts means for Claude. No subagents and no plan mode: the
 * front agent answers in seconds or hands over. The prompt is given as `--single=` so one starting with a dash is still
 * his words, never an option.
 */
fun grokQuickCommand(prompt: String, resume: String? = null, mac: Boolean = false, newChat: Boolean = false): String =
    grokGuardEnv(if (newChat) GROK_QUICK_CHAT_ALLOWED else GROK_QUICK_ALLOWED) + "grok --cwd \"\$HOME/$GROK_FRONT_FOLDER\" --output-format streaming-messages-json --include-partial-messages --model $QUICK_GROK_MODEL " +
        (resume?.takeIf(GROK_SESSION::matches)?.let { "--resume $it " } ?: "") +
        "--permission-mode dontAsk --no-subagents --no-plan --rules ${shellQuote(quickSystemPrompt(mac, newChat))} " +
        "--tools ${(if (newChat) GROK_QUICK_CHAT_TOOLS else GROK_QUICK_TOOLS).joinToString(",")} " +
        (if (newChat) GROK_QUICK_CHAT_ALLOWED else GROK_QUICK_ALLOWED).joinToString(" ") { "--allow ${shellQuote(it)}" } + " " +
        GROK_QUICK_DENIED.joinToString(" ") { "--deny ${shellQuote(it)}" } +
        " --single=${shellQuote(prompt)} < /dev/null"

/** The front agent's command for [agent]: [grokQuickCommand] for Grok, [quickCommand] for Haiku. */
fun frontCommand(agent: FrontAgent, prompt: String, resume: String? = null, mac: Boolean = false, newChat: Boolean = false): String =
    when (agent) {
        FrontAgent.Grok -> grokQuickCommand(prompt, resume, mac, newChat)
        FrontAgent.Haiku -> quickCommand(prompt, resume, mac, newChat)
    }

/**
 * A conversation of the front agent's, as kept between requests: which agent it is with, then its session id, so a
 * Claude session is never resumed with Grok or the other way round after he switches. One kept before there was a
 * choice is Haiku's.
 */
fun frontThread(agent: FrontAgent, session: String?): String? = session?.let { "${agent.name}:$it" }

/** The session id of [kept] when it is [agent]'s conversation; null when it is the other agent's, or there is none. */
fun frontSession(agent: FrontAgent, kept: String?): String? {
    if (kept == null) return null
    val (who, id) = if (':' in kept) kept.substringBefore(':') to kept.substringAfter(':') else FrontAgent.Haiku.name to kept
    return id.takeIf { who == agent.name && it.isNotEmpty() }
}

/** A conversation id as Grok gives it, a UUID: anything else after `--resume` would be searched for as a title. */
private val GROK_SESSION = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * The command that answers [prompt] with Grok on a computer, as [quickDesktopCommand] does with Claude: told
 * [quickDesktopPrompt], with the web, and the shell for `soma canvas` alone when it may arrange the canvas ([canvas]).
 */
fun grokDesktopCommand(prompt: String, resume: String? = null, newChat: Boolean = false, canvas: Boolean = false): String =
    grokGuardEnv(GROK_QUICK_CHAT_ALLOWED + if (canvas) DESKTOP_CANVAS_ALLOWED else emptyList()) + "grok --output-format streaming-messages-json --include-partial-messages --model $QUICK_GROK_MODEL " +
        (resume?.takeIf(GROK_SESSION::matches)?.let { "--resume $it " } ?: "") +
        "--permission-mode dontAsk --no-subagents --no-plan --rules ${shellQuote(quickDesktopPrompt(newChat, canvas))} " +
        "--tools ${(GROK_QUICK_CHAT_TOOLS + if (canvas) listOf("run_terminal_cmd") else emptyList()).joinToString(",")} " +
        (GROK_QUICK_CHAT_ALLOWED + if (canvas) DESKTOP_CANVAS_ALLOWED else emptyList()).joinToString(" ") { "--allow ${shellQuote(it)}" } + " " +
        GROK_QUICK_DENIED.joinToString(" ") { "--deny ${shellQuote(it)}" } +
        " --single=${shellQuote(prompt)} < /dev/null"

/** The front agent's command on a computer for [agent]: [grokDesktopCommand] for Grok, [quickDesktopCommand] for Haiku. */
fun desktopFrontCommand(agent: FrontAgent, prompt: String, resume: String? = null, newChat: Boolean = false, canvas: Boolean = false): String =
    when (agent) {
        FrontAgent.Grok -> grokDesktopCommand(prompt, resume, newChat, canvas)
        FrontAgent.Haiku -> quickDesktopCommand(prompt, resume, newChat, canvas)
    }
