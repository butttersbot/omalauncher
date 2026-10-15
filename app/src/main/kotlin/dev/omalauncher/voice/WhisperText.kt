package dev.omalauncher.voice

/**
 * The plain parts of Whisper voice input (Boss, 2026-10-14: Whisper instead of Samsung's recognizer, with the
 * words showing as they are spoken). Plain Kotlin, tested without Android.
 */

/** One stretch of text Whisper heard, from [start] to [end] in hundredths of a second into the audio it was given. */
data class Heard(
    val start: Long,
    val end: Long,
    val text: String,
)

/** Reads what the bridge returns: one "start end text" line per segment. Lines it can't read are skipped. */
fun parseHeard(raw: String): List<Heard> =
    raw.lineSequence().mapNotNull { line ->
        val parts = line.split(' ', limit = 3)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Heard(start, end, cleanHeard(parts.getOrElse(2) { "" }))
    }.filter { it.text.isNotEmpty() }.toList()

/**
 * Whisper's text without what it says for what isn't speech: "[BLANK_AUDIO]", "(music)", "*laughs*" and
 * the like, and the spaces they leave.
 */
fun cleanHeard(text: String): String =
    text
        .replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

/**
 * The words two readings in a row agree on from the start (LocalAgreement, as whisper_streaming does): what
 * the live view shows as settled. The rest may still change as more audio comes in. Compared without case or
 * punctuation, since Whisper adds a comma or a capital to a word it heard before; [now]'s own words are kept.
 */
fun agreedWords(
    before: List<String>,
    now: List<String>,
): List<String> {
    fun bare(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }
    var n = 0
    while (n < before.size && n < now.size && bare(before[n]) == bare(now[n])) n++
    return now.take(n)
}

fun words(text: String) = text.split(' ').filter { it.isNotBlank() }

/** [a] and [b] as one text, a space between when both have words. */
fun joinHeard(
    a: String,
    b: String,
) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

/**
 * What the live view shows: the words settled for good ([kept]), then the latest reading of the audio after
 * them ([reading]). Its first [agreed] words are settled too; the rest are the ones that may still change.
 */
data class LiveText(
    val kept: String = "",
    val reading: String = "",
    val agreed: Int = 0,
) {
    val text get() = joinHeard(kept, reading)

    /** The settled part: [kept] and the agreed start of [reading]. */
    val settled get() = joinHeard(kept, words(reading).take(agreed).joinToString(" "))

    /** A new reading of the audio after [kept]. */
    fun read(now: String): LiveText {
        val agree = agreedWords(words(reading), words(now)).size
        return copy(reading = now, agreed = agree)
    }

    /** [heard] is settled for good and its audio is left behind: it joins [kept] and readings start after it. */
    fun keep(heard: String) = LiveText(joinHeard(kept, heard), "", 0)
}

/**
 * The words Whisper is told it may hear, so it spells them as Boss does (its initial prompt). Oma's own names,
 * then his own list (one word or name per line, from ~/.config/omalauncher/voice-words.txt).
 */
fun voicePrompt(extra: List<String> = emptyList()): String {
    val words = VOICE_WORDS + extra.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    return words.distinct().take(80).joinToString(", ") + "."
}

val VOICE_WORDS =
    listOf(
        "Oma", "Claude", "Codex", "Cursor", "Tailscale", "Taildrop", "Fold", "Mac Pro", "MacBook", "launcher",
        "widget", "builder", "Whisper", "Arch", "Linux", "GitHub", "repo", "commit", "promote", "Opus", "Haiku",
    )

/**
 * When a pause ends what was said: once some speech was heard, [quietMs] of quiet. Levels are 0 to 1, one per
 * [stepMs] of audio, newest last.
 */
fun pauseEnded(
    levels: List<Float>,
    stepMs: Int,
    quietMs: Int = 1_200,
    quiet: Float = SILENCE,
): Boolean {
    val need = (quietMs + stepMs - 1) / stepMs
    if (levels.size <= need) return false
    val spoke = levels.dropLast(need).any { it > quiet * 2 }
    return spoke && levels.takeLast(need).all { it <= quiet }
}

/** A step's level at or below this is quiet. */
const val SILENCE = 0.08f
