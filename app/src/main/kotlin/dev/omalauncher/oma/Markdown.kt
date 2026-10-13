package dev.omalauncher.oma

/** A run of an answer's text in one style. [link] is where it leads, as the agent wrote it. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/** One block of an answer, top to bottom. */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** [level] is 1 to 6, as written. */
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock

    /** [depth] is 0 for an item of the outermost list. */
    data class Bullet(val depth: Int, val spans: List<MdSpan>) : MdBlock

    /** [number] is the one the agent wrote, so a list that starts at 3 or is split by a code block still counts right. */
    data class Numbered(val depth: Int, val number: String, val spans: List<MdSpan>) : MdBlock

    /** A fenced block, [code] exactly as it came. */
    data class Code(val language: String, val code: String) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data object Rule : MdBlock

    /** Every row has as many cells as [header]. */
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
}

/**
 * The Markdown the agent answers in, read into blocks for the chat to draw. It is read as far as it has come, so
 * an answer still being written shows what has arrived: a fence that isn't closed yet is code to the end, and a
 * marker with no partner yet is the character itself. Nothing is ever left out of the text.
 */
object Markdown {
    /** Past this many blocks the rest of an answer is one plain paragraph, so no answer is too long to lay out. */
    const val MAX_BLOCKS = 600

    /** A quote inside a quote deeper than this is read as text: each level is a call deeper. */
    private const val MAX_QUOTES = 6

    private val item = Regex("""^(\s*)(?:([-*+])|(\d{1,9})[.)])\s+(.*)$""")
    private val delimiter = Regex(""":?-+:?""")

    fun parse(text: String): List<MdBlock> = blocks(text.lines(), 0)

    /** The spans of one line or paragraph of running text. */
    fun inline(text: String): List<MdSpan> = Inline(text).spans()

    /** True for the only links the chat opens: web pages. A file path or any other scheme stays text. */
    fun isWebLink(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** Running text still being collected: a paragraph or a list item, which the lines after it may continue. */
    private class Open(val listed: Boolean, val make: (List<MdSpan>) -> MdBlock) {
        val lines = ArrayList<String>()
    }

    private fun blocks(
        lines: List<String>,
        quotes: Int,
    ): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var open: Open? = null
        // The indents of the lists an item could belong to, outermost first.
        val levels = ArrayList<Int>()

        fun flush() {
            open?.let { out += it.make(inline(it.lines.joinToString("\n"))) }
            open = null
        }

        var i = 0
        while (i < lines.size) {
            if (out.size + (if (open == null) 0 else 1) >= MAX_BLOCKS) {
                flush()
                val rest = lines.subList(i, lines.size).joinToString("\n").trim()
                if (rest.isNotEmpty()) out += MdBlock.Paragraph(listOf(MdSpan(rest)))
                return out
            }
            val line = lines[i]
            val trimmed = line.trim()
            val fence = fenceOf(trimmed)
            when {
                fence != null -> {
                    flush()
                    val indent = line.length - line.trimStart().length
                    var end = i + 1
                    while (end < lines.size && !closes(lines[end], fence)) end++
                    // A fenced block inside a list item is indented with it; that much is not part of the code.
                    val code = lines.subList(i + 1, end).joinToString("\n") { row -> row.drop(minOf(indent, row.length - row.trimStart().length)) }
                    out += MdBlock.Code(trimmed.drop(fence.length).trim().takeWhile { !it.isWhitespace() }, code)
                    i = end + 1
                }
                trimmed.isEmpty() -> {
                    flush()
                    i++
                }
                trimmed.startsWith('>') && quotes < MAX_QUOTES -> {
                    flush()
                    levels.clear()
                    var end = i
                    while (end < lines.size && lines[end].trimStart().startsWith('>')) end++
                    out += MdBlock.Quote(blocks(lines.subList(i, end).map { it.trimStart().drop(1).removePrefix(" ") }, quotes + 1))
                    i = end
                }
                headingLevel(trimmed) > 0 -> {
                    flush()
                    levels.clear()
                    val level = headingLevel(trimmed)
                    out += MdBlock.Heading(level, inline(headingText(trimmed.drop(level).trim())))
                    i++
                }
                isRule(trimmed) -> {
                    flush()
                    levels.clear()
                    out += MdBlock.Rule
                    i++
                }
                isTableHead(lines, i) -> {
                    flush()
                    levels.clear()
                    val header = cells(line)
                    var end = i + 2
                    val rows = ArrayList<List<List<MdSpan>>>()
                    while (end < lines.size && lines[end].isNotBlank() && '|' in lines[end]) {
                        val row = cells(lines[end])
                        rows += List(header.size) { inline(row.getOrElse(it) { "" }) }
                        end++
                    }
                    out += MdBlock.Table(header.map(::inline), rows)
                    i = end
                }
                else -> {
                    val listed = item.matchEntire(line.trimEnd())
                    val continued = open
                    if (listed != null) {
                        flush()
                        val indent = listed.groupValues[1].sumOf { if (it == '\t') 4 else 1 }
                        while (levels.isNotEmpty() && indent < levels.last()) levels.removeAt(levels.lastIndex)
                        if (levels.isEmpty() || indent > levels.last()) levels += indent
                        val depth = levels.size - 1
                        val number = listed.groupValues[3]
                        open = Open(listed = true) { if (number.isEmpty()) MdBlock.Bullet(depth, it) else MdBlock.Numbered(depth, number, it) }.also { it.lines += listed.groupValues[4] }
                    } else if (continued != null) {
                        // A list item's next lines are indented under it; a paragraph's keep their own shape.
                        continued.lines += if (continued.listed) trimmed else line.trimEnd()
                    } else {
                        if (!line.first().isWhitespace()) levels.clear()
                        open = Open(listed = false) { MdBlock.Paragraph(it) }.also { it.lines += line.trimEnd() }
                    }
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** The run of backticks or tildes that opens a fence on this line, or null. Backticks after it make it inline code instead. */
    private fun fenceOf(trimmed: String): String? {
        val mark = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = trimmed.takeWhile { it == mark }
        return run.takeIf { it.length >= 3 && (mark == '~' || '`' !in trimmed.drop(run.length)) }
    }

    private fun closes(
        line: String,
        fence: String,
    ): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence[0] }
    }

    /** 1 to 6 for a heading line, else 0. `#hashtag` has no space after the marks and is text. */
    private fun headingLevel(trimmed: String): Int {
        val marks = trimmed.takeWhile { it == '#' }.length
        return if (marks in 1..6 && (trimmed.length == marks || trimmed[marks].isWhitespace())) marks else 0
    }

    /** A heading's text without the closing marks some write after it; `C#` keeps its own. */
    private fun headingText(text: String): String {
        val open = text.trimEnd('#')
        return if (open.length < text.length && (open.isEmpty() || open.last().isWhitespace())) open.trim() else text
    }

    private fun isRule(trimmed: String): Boolean {
        val marks = trimmed.filterNot { it == ' ' || it == '\t' }
        return marks.length >= 3 && marks[0] in "-*_" && marks.all { it == marks[0] }
    }

    /** True when line [i] is a table's header: the line under it is the row of dashes, with as many cells. */
    private fun isTableHead(
        lines: List<String>,
        i: Int,
    ): Boolean {
        if ('|' !in lines[i] || i + 1 >= lines.size || '|' !in lines[i + 1]) return false
        val under = cells(lines[i + 1])
        return under.all { delimiter.matches(it) } && under.size == cells(lines[i]).size
    }

    /** A table row's cells. `\|` is a bar inside a cell. */
    private fun cells(line: String): List<String> {
        val row = line.trim().removePrefix("|")
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (c == '\\' && i + 1 < row.length && row[i + 1] == '|') {
                cell.append('|')
                i++
            } else if (c == '|') {
                out += cell.toString().trim()
                cell.setLength(0)
            } else {
                cell.append(c)
            }
            i++
        }
        // What follows the last bar is a cell only when there is something in it: rows usually end with a bar.
        if (cell.isNotBlank() || out.isEmpty()) out += cell.toString().trim()
        return out
    }

    /** One pass over running text, left to right; a marker is looked ahead for its partner before it means anything. */
    private class Inline(private val s: String) {
        private val out = ArrayList<MdSpan>()

        /** A link's text ends at [textEnd] and the whole of it at [end]. */
        private class Link(val textEnd: Int, val url: String, val end: Int)

        fun spans(): List<MdSpan> {
            walk(0, s.length, MdSpan(""), 0)
            return out
        }

        private fun emit(
            text: CharSequence,
            style: MdSpan,
        ) {
            if (text.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == style) out[out.lastIndex] = last.copy(text = last.text + text) else out += style.copy(text = text.toString())
        }

        /** Reads from [from] up to [to] in [style], which has no text of its own. [nested] is how many markers deep this is. */
        private fun walk(
            from: Int,
            to: Int,
            style: MdSpan,
            nested: Int,
        ) {
            val plain = StringBuilder()

            fun flush() {
                emit(plain, style)
                plain.setLength(0)
            }

            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < to && s[i + 1] in ESCAPABLE -> {
                        plain.append(s[i + 1])
                        i += 2
                    }
                    c == '`' -> {
                        val n = run(i, to)
                        val close = closingTicks(i + n, to, n)
                        if (close < 0) {
                            plain.append(s, i, i + n)
                            i += n
                        } else {
                            flush()
                            emit(codeText(s.substring(i + n, close)), style.copy(code = true))
                            i = close + n
                        }
                    }
                    c == '*' || c == '_' || c == '~' -> {
                        val n = run(i, to)
                        val size = if (nested < MAX_NESTING) emphasis(i, n, to) else 0
                        if (size == 0) {
                            plain.append(s, i, i + n)
                            i += n
                        } else {
                            // Of three marks, the ones that don't open here open inside, and are closed first.
                            val close = closer(c, size, i + n, minOf(to, i + MAX_SPAN), n - size)
                            flush()
                            walk(i + size, close, if (c == '~') style.copy(strike = true) else style.copy(bold = style.bold || size >= 2, italic = style.italic || size != 2), nested + 1)
                            i = close + size
                        }
                    }
                    (c == '[' || (c == '!' && i + 1 < to && s[i + 1] == '[')) && style.link == null && nested < MAX_NESTING -> {
                        val bracket = if (c == '!') i + 1 else i
                        val link = link(bracket, to)
                        if (link == null) {
                            plain.append(c)
                            i++
                        } else {
                            flush()
                            val linked = style.copy(link = link.url)
                            // A link with no words of its own, or a picture with no caption, shows where it leads.
                            if (s.substring(bracket + 1, link.textEnd).isBlank()) emit(link.url, linked) else walk(bracket + 1, link.textEnd, linked, nested + 1)
                            i = link.end
                        }
                    }
                    c == '<' && style.link == null && startsUrl(i + 1, to) -> {
                        val end = s.indexOf('>', i)
                        if (end in i + 1 until to && s.substring(i + 1, end).none { it.isWhitespace() }) {
                            flush()
                            emit(s.substring(i + 1, end), style.copy(link = s.substring(i + 1, end)))
                            i = end + 1
                        } else {
                            plain.append(c)
                            i++
                        }
                    }
                    c == 'h' && style.link == null && startsUrl(i, to) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                        val end = urlEnd(i, to)
                        flush()
                        emit(s.substring(i, end), style.copy(link = s.substring(i, end)))
                        i = end
                    }
                    else -> {
                        plain.append(c)
                        i++
                    }
                }
            }
            flush()
        }

        /** How many of the character at [i] stand together, up to [to]. */
        private fun run(
            i: Int,
            to: Int,
        ): Int {
            var end = i + 1
            while (end < to && s[end] == s[i]) end++
            return end - i
        }

        /** Where a run of exactly [n] backticks starts between [from] and [to], or -1. */
        private fun closingTicks(
            from: Int,
            to: Int,
            n: Int,
        ): Int {
            var j = from
            while (j < to) {
                if (s[j] == '`') {
                    val m = run(j, to)
                    if (m == n) return j
                    j += m
                } else {
                    j++
                }
            }
            return -1
        }

        /** Inline code as it reads: on one line, without the spaces that only keep a backtick off the marks. */
        private fun codeText(raw: String): String {
            val line = raw.replace('\n', ' ')
            return if (line.length > 2 && line.startsWith(' ') && line.endsWith(' ') && line.isNotBlank()) line.substring(1, line.length - 1) else line
        }

        /**
         * True when the [n] marks at [i] could start emphasis: text follows them, and they don't stand inside or at
         * the end of a word. That leaves `snake_case`, `2*3*4` and `5 * 3` as they are.
         */
        private fun opens(
            i: Int,
            n: Int,
        ): Boolean {
            val next = s.getOrNull(i + n) ?: return false
            val before = s.getOrNull(i - 1)
            return !next.isWhitespace() && (before == null || !before.isLetterOrDigit())
        }

        /**
         * True when the [n] marks at [i] could end emphasis: text is before them, and a word doesn't go on after
         * them. Stars may close against the next word, as a Korean particle follows the word it is written on.
         */
        private fun closes(
            i: Int,
            n: Int,
        ): Boolean {
            val before = s.getOrNull(i - 1) ?: return false
            val next = s.getOrNull(i + n)
            return !before.isWhitespace() && (next == null || !next.isLetterOrDigit() || (s[i] != '_' && before.isLetterOrDigit()))
        }

        /**
         * How many of the [n] marks at [i] open emphasis that is closed before [to]: 1 is italic, 2 bold or struck
         * through, 3 both. 0 when none do, and the marks are text. Of three, one or two may open and the rest open
         * inside them, as in `***bold** then italic*`.
         */
        private fun emphasis(
            i: Int,
            n: Int,
            to: Int,
        ): Int {
            if (!opens(i, n)) return 0
            val limit = minOf(to, i + MAX_SPAN)
            if (s[i] == '~') return if (n == 2 && closer('~', 2, i + n, limit, 0) >= 0) 2 else 0
            if (n > 3) return 0
            if (closer(s[i], n, i + n, limit, 0) >= 0) return n
            if (n == 3) for (size in 2 downTo 1) if (closer(s[i], size, i + n, limit, n - size) >= 0) return size
            return 0
        }

        /**
         * Where the [size] marks of [mark] that close an opener start, looking from [from] up to [to], or -1.
         * [inside] is how many marks opened after that opener and are still to be closed: those are closed first,
         * so `*a **b** c*` ends at its last star. Code and escaped characters are stepped over.
         */
        private fun closer(
            mark: Char,
            size: Int,
            from: Int,
            to: Int,
            inside: Int,
        ): Int {
            var open = inside
            var j = from
            while (j < to) {
                val c = s[j]
                if (c == '\\') {
                    j += 2
                } else if (c == '`') {
                    val n = run(j, to)
                    val close = closingTicks(j + n, to, n)
                    j = if (close < 0) j + n else close + n
                } else if (c == mark) {
                    val n = run(j, to)
                    if (closes(j, n)) {
                        val inner = minOf(open, n)
                        open -= inner
                        if (n - inner >= size) return j + inner
                    } else if (opens(j, n)) {
                        open += n
                    }
                    j += n
                } else {
                    j++
                }
            }
            return -1
        }

        /** The link whose text opens with the bracket at [bracket]: `[text](url)`, with an optional title after the url. */
        private fun link(
            bracket: Int,
            to: Int,
        ): Link? {
            val limit = minOf(to, bracket + MAX_SPAN)
            var depth = 1
            var j = bracket + 1
            while (j < limit) {
                val c = s[j]
                if (c == '\\') {
                    j++
                } else if (c == '`') {
                    val n = run(j, limit)
                    val close = closingTicks(j + n, limit, n)
                    j = if (close < 0) j + n - 1 else close + n - 1
                } else if (c == '[') {
                    depth++
                } else if (c == ']' && --depth == 0) {
                    break
                }
                j++
            }
            if (j >= limit || j + 1 >= to || s[j + 1] != '(') return null
            var parens = 1
            var k = j + 2
            while (k < limit) {
                val c = s[k]
                if (c == '\n') return null
                if (c == '\\') {
                    k++
                } else if (c == '(') {
                    parens++
                } else if (c == ')' && --parens == 0) {
                    break
                }
                k++
            }
            if (k >= limit) return null
            val url = s.substring(j + 2, k).trim().takeWhile { !it.isWhitespace() }.removeSurrounding("<", ">")
            return if (url.isEmpty()) null else Link(j, url, k + 1)
        }

        private fun startsUrl(
            i: Int,
            to: Int,
        ) = (s.startsWith("https://", i) && i + 8 < to) || (s.startsWith("http://", i) && i + 7 < to)

        /** Where the bare address at [i] ends: at a space, without the punctuation of the sentence it stands in. */
        private fun urlEnd(
            i: Int,
            to: Int,
        ): Int {
            var end = i
            while (end < to && !s[end].isWhitespace() && s[end] !in "<>\"`") end++
            while (end > i) {
                val last = s[end - 1]
                val closesNothing = last == ')' && (i until end).count { s[it] == '(' } < (i until end).count { s[it] == ')' }
                if (last in ".,;:!?'*_~" || closesNothing) end-- else break
            }
            return end
        }

        private companion object {
            const val ESCAPABLE = "\\`*_{}[]()<>#+-.!|~\"'&:;,/=?@^$%"

            /** Emphasis and a link's text and address are each looked for this far, so a stray marker costs no more than this. */
            const val MAX_SPAN = 2_000

            /** Markers inside markers deeper than this are text: each level is a call deeper. */
            const val MAX_NESTING = 12
        }
    }
}
