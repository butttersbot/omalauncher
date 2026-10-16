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
)

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
        Managed(File(rootfs, "$OMA_LIB/msg"), Policy.Owned, executable = true, content = msg)

/** [omaTools] for the provisioner, read from the assets. */
fun omaGuestTools(context: Context, rootfs: File): List<Managed> =
    omaTools(
        rootfs,
        { context.assets.open(OMA_ASSET).use { it.readBytes() } },
        { context.assets.open(DROP_ASSET).use { it.readBytes() } },
        { context.assets.open(MSG_ASSET).use { it.readBytes() } },
    )
