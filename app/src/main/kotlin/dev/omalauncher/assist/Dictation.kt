package dev.omalauncher.assist

/** Speaking again adds to what's already in the field instead of replacing it. */
fun appendSpeech(before: String, said: String): String =
    listOf(before.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" ")
