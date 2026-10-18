package dev.omalauncher.linux

import android.content.Context
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/*
 * `oma`, the one guest command for the launcher's small tools (Boss, 2026-10-14: "Fold the small tools into one
 * `oma` command"). assets/oma.sh only chooses; each subcommand is carried out by the script that was a command of
 * its own before, now installed in [OMA_LIB] by its own provider (macGuestTools, keepGuestTools,
 * agentsGuestTools), so none of their code changed for the fold. The old names stay as thin aliases so that what
 * agents, notes and scripts already wrote with them keeps working. `oma drop` came after the fold, so it has no old
 * name; its script is installed here, by [omaGuestTools].
 */

/** Where the command is installed in the rootfs. */
const val OMA_COMMAND = "usr/local/bin/oma"

/** The dispatcher, in the app's assets. */
const val OMA_ASSET = "oma.sh"

/** Where the tools that carry out the subcommands are installed; oma.sh finds them as ../lib/oma from itself. */
const val OMA_LIB = "usr/local/lib/oma"

/** `oma drop`, files between the Macs and the phone (Taildrop in, the Mac runner out), in the app's assets. */
const val DROP_ASSET = "oma-drop.sh"

/** `oma msg`, agent mail to the computers' agents (assist/AgentMail.kt has the other way), in the app's assets. */
const val MSG_ASSET = "oma-msg.sh"

/**
 * One subcommand of `oma`: [word] as typed, the tool in [OMA_LIB] named [lib] that carries it out, and
 * [oldName], the command it was before, null for one that was never a command of its own. [oldWord] is the word
 * the old command had to be given first, which its alias takes off; null when the old command's words are the
 * subcommand's own.
 */
class OmaPart(val word: String, val lib: String, val oldName: String?, val oldWord: String? = null)

/** What `oma` does besides `help`; oma.sh's case must match it, which OmaCommandTest checks. */
val OMA_PARTS = listOf(
    OmaPart("threads", "thread", "oma-thread"),
    OmaPart("keep", "keep", "oma-keep"),
    OmaPart("status", "agents", "oma-agents", oldWord = "status"),
    OmaPart("drop", "drop", oldName = null),
    OmaPart("msg", "msg", oldName = null),
    OmaPart("front", "front", oldName = null),
)

/**
 * `oma front`, who answers first in the chat (Boss, 2026-10-18: Grok, with Haiku kept to switch back): `oma front haiku`
 * or `oma front grok` writes his choice where QuickLane reads it (dev.omalauncher.assist.FRONT_CHOICE), and bare
 * `oma front` says who answers now and why. Grok answers only once it is installed and signed in here; until then Haiku
 * does, whatever was chosen.
 */
const val OMA_FRONT_SCRIPT = """#!/bin/sh
# Omalauncher: `oma front`, who the chat's front agent is. Written by the launcher; changes here are replaced.
choice="${'$'}{HOME:?}/.config/oma/front-agent"
case "${'$'}{1-}" in
  grok | haiku)
    mkdir -p "${'$'}(dirname "${'$'}choice")" && printf '%s\n' "${'$'}1" > "${'$'}choice" || exit 1
    echo "Chosen: ${'$'}1, from the next request." ;;
  "") ;;
  help | -h | --help) echo "usage: oma front [grok|haiku]"; exit 0 ;;
  *) echo "usage: oma front [grok|haiku]" >&2; exit 2 ;;
esac
chosen=${'$'}(cat "${'$'}choice" 2>/dev/null)
[ "${'$'}chosen" = haiku ] || chosen=grok
if [ "${'$'}chosen" = haiku ] && ! command -v claude >/dev/null 2>&1; then
  echo "Haiku is chosen but Claude Code is not installed here, so nobody answers first. Switch with: oma front grok"
elif [ "${'$'}chosen" = haiku ]; then
  echo "The front agent is Haiku (Claude Code). Switch with: oma front grok"
elif ! command -v grok >/dev/null 2>&1; then
  echo "Grok is chosen but not installed here, so Haiku answers if Claude Code is. Install Grok in Omalauncher's agents."
elif [ ! -f "${'$'}HOME/.grok/auth.json" ]; then
  echo "Grok is chosen but not signed in here, so Haiku answers if Claude Code is. Sign in with: grok login --device-auth"
else
  echo "The front agent is Grok. Switch back with: oma front haiku"
fi
"""

/** The old command [part] was, as a script that runs `oma` with the same words. */
fun omaAlias(part: OmaPart): String {
    val guard = part.oldWord?.let { "[ \"\${1-}\" = $it ] || { echo 'usage: oma ${part.word}' >&2; exit 2; }\nshift\n" }.orEmpty()
    return "#!/bin/sh\n# The old name of `oma ${part.word}`, kept so that what was written with it still works.\n" +
        guard + "exec \"\$(dirname \"\$0\")/oma\" ${part.word} \"\$@\"\n"
}

/**
 * `oma`, the old names' aliases and `oma drop`'s script as installed in [rootfs], their bytes from [dispatcher] and
 * [drop]: the launcher's own, rewritten with each version.
 */
fun omaTools(rootfs: File, dispatcher: () -> ByteArray, drop: () -> ByteArray, msg: () -> ByteArray): List<Managed> =
    listOf(Managed(File(rootfs, OMA_COMMAND), Policy.Owned, executable = true, content = dispatcher)) +
        OMA_PARTS.mapNotNull { part ->
            part.oldName?.let { Managed.text(File(rootfs, "usr/local/bin/$it"), Policy.Owned, executable = true) { omaAlias(part) } }
        } +
        Managed(File(rootfs, "$OMA_LIB/drop"), Policy.Owned, executable = true, content = drop) +
        Managed(File(rootfs, "$OMA_LIB/msg"), Policy.Owned, executable = true, content = msg) +
        Managed.text(File(rootfs, "$OMA_LIB/front"), Policy.Owned, executable = true) { OMA_FRONT_SCRIPT }

/** [omaTools] for the provisioner, read from the assets. */
fun omaGuestTools(context: Context, rootfs: File): List<Managed> =
    omaTools(
        rootfs,
        { context.assets.open(OMA_ASSET).use { it.readBytes() } },
        { context.assets.open(DROP_ASSET).use { it.readBytes() } },
        { context.assets.open(MSG_ASSET).use { it.readBytes() } },
    )
