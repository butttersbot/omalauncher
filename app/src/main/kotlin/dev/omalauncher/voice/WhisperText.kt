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
    }.filter { it.text.isNotEmpty() && !madeUp(it.text) }.toList()

/**
 * What Whisper makes up from silence or noise, learned from video captions (Boss, 2026-10-15: "it keeps saying
 * thank you for watching when it first shows up"). Dropped only when it is all a segment says.
 */
fun madeUp(text: String): Boolean {
    val t = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
    return t in MADE_UP || MADE_UP_STARTS.any { t.startsWith(it) }
}

private val MADE_UP =
    setOf(
        "thank you for watching", "thanks for watching", "thank you so much for watching", "thank you very much for watching",
        "thanks for watching and see you next time", "please subscribe", "like and subscribe", "you", "bye", "bye bye",
        "see you next time", "see you in the next video", "i ll see you in the next video",
    )
private val MADE_UP_STARTS = listOf("subtitles by", "transcribed by", "captions by", "translated by", "thank you for watching")

/**
 * Whisper's text without what it says for what isn't speech: "[BLANK_AUDIO]", "(music)", "*laughs*" and
 * the like, and the spaces they leave.
 */
fun cleanHeard(text: String): String =
    collapseRepeats(
        text
            .replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(),
    )

/**
 * A word or a run of up to four words said three or more times over, back to back, once: Whisper loops so on
 * silence ("and, and, and, and…"), and nobody says a thing three times running when dictating.
 */
fun collapseRepeats(text: String): String {
    val w = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
    fun bare(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }
    var changed = true
    while (changed) {
        changed = false
        loop@ for (n in 1..4) {
            var i = 0
            while (i + 3 * n <= w.size) {
                val unit = w.subList(i, i + n).map(::bare)
                if (unit.all { it.isEmpty() }) { i++; continue }
                var times = 1
                while (i + (times + 1) * n <= w.size && w.subList(i + times * n, i + (times + 1) * n).map(::bare) == unit) times++
                if (times >= 3) {
                    // Keep the first, with the last one's ending (its full stop, not its comma).
                    val last = w.subList(i + (times - 1) * n, i + times * n).toList()
                    repeat((times - 1) * n) { w.removeAt(i + n) }
                    w[i + n - 1] = last.last()
                    changed = true
                    break@loop
                }
                i++
            }
        }
    }
    return w.joinToString(" ")
}

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
        "Oma", "Claude", "Codex", "Grok", "Cursor", "Tailscale", "Taildrop", "Fold", "Mac Pro", "MacBook", "launcher",
        "widget", "builder", "Whisper", "Arch", "Linux", "GitHub", "repo", "commit", "promote", "Opus", "Haiku",
    )

/**
 * When a pause ends what was said: once some speech was heard, [quietMs] of quiet. Levels are 0 to 1, one per
 * [stepMs] of audio, newest last.
 */
fun pauseEnded(
    levels: List<Float>,
    stepMs: Int,
    quietMs: Int = 800,
    quiet: Float = SILENCE,
): Boolean {
    val need = (quietMs + stepMs - 1) / stepMs
    if (levels.size <= need) return false
    val spoke = levels.dropLast(need).any { it > quiet * 2 }
    return spoke && levels.takeLast(need).all { it <= quiet }
}

/** A step's level at or below this is quiet. */
const val SILENCE = 0.08f

/**
 * What a dictation's end still needs read, once the live readings are over. When the Mac Pro read it live and
 * nothing was settled yet (the readings were all from his first word, [voiceFrom], in samples), the last reading,
 * if it was of all of it up to [end], is just what reading the whole once more would give: the same audio, model
 * and settings; so it is the end of it. Otherwise the whole of it is read once more: words settled from part of
 * the audio came out 2-4x less accurate (real speech, 20-60 s, through the Mac Pro's whisper-server, 2026-10-24),
 * Whisper's segment ends being too rough to cut the audio at; and the phone's live readings are its fast model's.
 */
sealed interface Finish {
    /** The whole of it, read again. */
    data object Whole : Finish

    /** Nothing new: [text], the last live reading, is the end of it. */
    data class Reuse(
        val text: String,
    ) : Finish
}

fun finishPlan(
    macLive: Boolean,
    voiceFrom: Int,
    anchor: Int,
    end: Int,
    lastFrom: Int,
    lastTo: Int,
    lastText: String?,
): Finish =
    if (macLive && anchor == voiceFrom && lastText != null && lastFrom == voiceFrom && lastTo >= end) {
        Finish.Reuse(lastText)
    } else {
        Finish.Whole
    }
