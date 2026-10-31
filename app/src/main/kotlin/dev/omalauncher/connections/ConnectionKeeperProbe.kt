package dev.omalauncher.connections

import dev.omalauncher.Runtime
import java.util.concurrent.TimeUnit

/**
 * Asks the keeper from inside the guest, as `oma keep list` does. The app's own socket connect to the keeper's file never reached it
 * (the host path is past the unix socket's length limit under the app's data folder), so the old probe read an IOException for 37 hours
 * while the keeper answered `oma keep list`. Read-only; kept commands' arguments and output never enter diagnostics.
 */
internal fun connectionKeeperProbe(runtime: Runtime): ConnectionCheck = try {
    val process = runtime.proot.process("oma keep list").start()
    val output = StringBuilder()
    val reader = Thread { runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.take(64).forEach { output.appendLine(it.take(200)) } } } }.apply { isDaemon = true; start() }
    val finished = process.waitFor(20, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    reader.join(1000)
    keeperCheck(if (finished) process.exitValue() else null, output.toString())
} catch (error: Exception) {
    keeperCheck(null, "")
}
