package dev.omalauncher.linux

/* Shell quoting, shared with the Mac app; the guest's shell is in Shell.kt. */

/** [s] as one single-quoted shell word. */
fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
