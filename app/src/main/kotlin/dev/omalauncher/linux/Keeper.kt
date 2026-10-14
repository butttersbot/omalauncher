package dev.omalauncher.linux

import android.content.Context
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/*
 * oma-keep (assets/oma-keep.py): keeps a guest command running after the agent turn that started it ends (Boss,
 * 2026-10-13). An agent's turn has a proot of its own, started with --kill-on-exit, so whatever it starts dies
 * with the turn. The keeper runs instead in a proot of the launcher's that lasts as long as Linux does, as
 * oma-door does: Runtime starts `oma-keep serve`, and the command asks it over a unix socket only root in the
 * guest can open. What it keeps ends with it when Linux stops or the launcher restarts.
 */

/** Where the command is installed in the rootfs. */
const val KEEP_COMMAND = "usr/local/bin/oma-keep"

/** The script, in the app's assets. */
const val KEEP_ASSET = "oma-keep.py"

/** The keeper is a Python script; Linux without python3 has no keeper, and the command says so. */
const val KEEP_PYTHON = "usr/bin/python3"

/** The command as it is installed, from [script]: the launcher's own, rewritten with each version. */
fun keepTool(rootfs: File, script: () -> ByteArray) = Managed(File(rootfs, KEEP_COMMAND), Policy.Owned, executable = true, content = script)

/** [keepTool] for the provisioner, read from the assets. */
fun keepGuestTools(context: Context, rootfs: File): List<Managed> = listOf(keepTool(rootfs) { context.assets.open(KEEP_ASSET).use { it.readBytes() } })
