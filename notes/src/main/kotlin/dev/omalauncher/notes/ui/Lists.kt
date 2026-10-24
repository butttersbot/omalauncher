package dev.omalauncher.notes.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Checklists as typed on a page: Markdown's "- [ ] " lines, which every editor and agent reads. */
object Lists {
    const val BOX = "- [ ] "

    /**
     * [next] as typed after [was]: Enter at the end of a checklist line starts the next one; Enter on an empty one
     * ends the list.
     */
    fun typed(
        was: TextFieldValue,
        next: TextFieldValue,
    ): TextFieldValue {
        val t = next.text
        val at = next.selection.start
        if (!next.selection.collapsed || t.length != was.text.length + 1 || at == 0 || t[at - 1] != '\n') return next
        val lineStart = t.lastIndexOf('\n', at - 2) + 1
        val line = t.substring(lineStart, at - 1)
        val item = Regex("^(\\s*)[-*] \\[[ xX]] ").find(line) ?: return next
        if (line.length == item.value.length) {
            // An empty item: the list ends here.
            val cut = t.removeRange(lineStart, at)
            return TextFieldValue(cut, TextRange(lineStart))
        }
        val add = item.groupValues[1] + BOX
        return TextFieldValue(t.substring(0, at) + add + t.substring(at), TextRange(at + add.length))
    }

    /** The line at the cursor made a checklist item, or plain again. */
    fun toggleLine(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val at = v.selection.start
        val start = t.lastIndexOf('\n', at - 1) + 1
        val m = Regex("^[-*] \\[[ xX]] ").find(t.substring(start))
        return if (m != null) {
            TextFieldValue(t.removeRange(start, start + m.value.length), TextRange((at - m.value.length).coerceAtLeast(start)))
        } else {
            TextFieldValue(t.substring(0, start) + BOX + t.substring(start), TextRange(at + BOX.length))
        }
    }

    /** [words] put in at the cursor, with a space before when they follow a word. */
    fun insert(
        v: TextFieldValue,
        words: String,
    ): TextFieldValue {
        val t = v.text
        val at = v.selection.end.coerceIn(0, t.length)
        val gap = if (at > 0 && !t[at - 1].isWhitespace()) " " else ""
        val add = gap + words
        return TextFieldValue(t.substring(0, at) + add + t.substring(at), TextRange(at + add.length))
    }
}
