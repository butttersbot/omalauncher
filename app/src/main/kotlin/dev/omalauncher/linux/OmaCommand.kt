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
 * agents, notes and scripts already wrote with them keeps working.
 */

/** Where the command is installed in the rootfs. */
const val OMA_COMMAND = "usr/local/bin/oma"

/** The dispatcher, in the app's assets. */
const val OMA_ASSET = "oma.sh"

/** Where the tools that carry out the subcommands are installed; oma.sh finds them as ../lib/oma from itself. */
const val OMA_LIB = "usr/local/lib/oma"

/**
 * One subcommand of `oma`: [word] as typed, the tool in [OMA_LIB] named [lib] that carries it out, and
 * [oldName], the command it was before. [oldWord] is the word the old command had to be given first, which
 * its alias takes off; null when the old command's words are the subcommand's own.
 */
class OmaPart(val word: String, val lib: String, val oldName: String, val oldWord: String? = null)

/** What `oma` does besides `help`; oma.sh's case must match it, which OmaCommandTest checks. */
val OMA_PARTS = listOf(
    OmaPart("threads", "thread", "oma-thread"),
    OmaPart("keep", "keep", "oma-keep"),
    OmaPart("status", "agents", "oma-agents", oldWord = "status"),
)

/** The old command [part] was, as a script that runs `oma` with the same words. */
fun omaAlias(part: OmaPart): String {
    val guard = part.oldWord?.let { "[ \"\${1-}\" = $it ] || { echo 'usage: oma ${part.word}' >&2; exit 2; }\nshift\n" }.orEmpty()
    return "#!/bin/sh\n# The old name of `oma ${part.word}`, kept so that what was written with it still works.\n" +
        guard + "exec \"\$(dirname \"\$0\")/oma\" ${part.word} \"\$@\"\n"
}

/** `oma` and the old names' aliases as installed in [rootfs], the dispatcher's bytes from [dispatcher]: the launcher's own, rewritten with each version. */
fun omaTools(rootfs: File, dispatcher: () -> ByteArray): List<Managed> =
    listOf(Managed(File(rootfs, OMA_COMMAND), Policy.Owned, executable = true, content = dispatcher)) +
        OMA_PARTS.map { part -> Managed.text(File(rootfs, "usr/local/bin/${part.oldName}"), Policy.Owned, executable = true) { omaAlias(part) } }

/** [omaTools] for the provisioner, read from the assets. */
fun omaGuestTools(context: Context, rootfs: File): List<Managed> = omaTools(rootfs) { context.assets.open(OMA_ASSET).use { it.readBytes() } }
