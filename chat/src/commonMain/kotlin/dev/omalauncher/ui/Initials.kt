package dev.omalauncher.ui

/* An app or shortcut's initials, shown where it has no icon; shared with the desktop app. */


fun initials(name: String): String {
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
        words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
        else -> "?"
    }
}
