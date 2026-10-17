package dev.omalauncher.linux

/** What a guest command printed, kept apart, and how it exited (124: timed out). Shared: widgets run on every screen. */
data class ShellResult(
    val out: String,
    val err: String,
    val code: Int,
) {
    val timedOut get() = code == 124
}
