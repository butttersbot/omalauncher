package dev.omalauncher.notes.engine

/**
 * Markdown as the reader shows it: the parts agents write (headings, paragraphs, lists and checklists, quotes, code,
 * tables, links), made into HTML for the reader's own page. Whatever HTML is inside the Markdown is shown as text,
 * never run. Each checklist item carries its line in the source (data-line), so a tap ticks it there. Pure, for the
 * JVM tests.
 */
object Markdown {
    fun html(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = ArrayList<String>()

        fun flush() {
            if (para.isEmpty()) return
            out.append("<p>").append(para.joinToString("<br>") { inline(it) }).append("</p>\n")
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> {
                    flush()
                    i++
                }
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                    i++
                    out.append("<pre><button class=\"copy\">Copy</button><code>").append(escape(code.joinToString("\n"))).append("</code></pre>\n")
                }
                HEADING.matches(t) -> {
                    flush()
                    val m = HEADING.find(t)!!
                    val n = m.groupValues[1].length
                    out.append("<h$n>").append(inline(m.groupValues[2].trimEnd('#', ' '))).append("</h$n>\n")
                    i++
                }
                RULE.matches(t) -> {
                    flush()
                    out.append("<hr>\n")
                    i++
                }
                t.startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").removePrefix(" ")
                    out.append("<blockquote>").append(html(quote.joinToString("\n")).trim()).append("</blockquote>\n")
                }
                t.startsWith("|") && i + 1 < lines.size && SEPARATOR.matches(lines[i + 1].trim()) -> {
                    flush()
                    val head = cells(t)
                    i += 2
                    out.append("<table><thead><tr>")
                    head.forEach { out.append("<th>").append(inline(it)).append("</th>") }
                    out.append("</tr></thead><tbody>\n")
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.append("<tr>")
                        cells(lines[i].trim()).forEachIndexed { c, cell ->
                            out.append("<td data-h=\"").append(escape(head.getOrElse(c) { "" }.replace(Regex("[*_`]"), ""))).append("\">").append(inline(cell)).append("</td>")
                        }
                        out.append("</tr>\n")
                        i++
                    }
                    out.append("</tbody></table>\n")
                }
                ITEM.matches(line) -> {
                    flush()
                    val ordered = ITEM.find(line)!!.groupValues[2].first().isDigit()
                    out.append(if (ordered) "<ol>" else "<ul>")
                    while (i < lines.size && ITEM.matches(lines[i])) {
                        val text = ITEM.find(lines[i])!!.groupValues[3]
                        val task = TASK.find(text)
                        if (task != null) {
                            val done = task.groupValues[1] != " "
                            out.append("<li class=\"task").append(if (done) " done" else "").append("\" data-line=\"").append(i).append("\"><span class=\"box\">")
                                .append(if (done) "✓" else "").append("</span><span>").append(inline(task.groupValues[2])).append("</span></li>")
                        } else {
                            out.append("<li>").append(inline(text)).append("</li>")
                        }
                        i++
                        // A wrapped item's next lines, indented.
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() && !ITEM.matches(lines[i])) {
                            out.setLength(out.length - "</li>".length)
                            out.append(" ").append(inline(lines[i].trim())).append("</li>")
                            i++
                        }
                    }
                    out.append(if (ordered) "</ol>\n" else "</ul>\n")
                }
                else -> {
                    para += t
                    i++
                }
            }
        }
        flush()
        return out.toString()
    }

    /** Line [line] of [md], its checklist box ticked or cleared; [md] as it was when that line isn't an item. */
    fun toggle(
        md: String,
        line: Int,
    ): String {
        val lines = md.split('\n').toMutableList()
        val l = lines.getOrNull(line) ?: return md
        val m = Regex("^(\\s*(?:[-*+]|\\d+[.)]) )\\[([ xX])](.*)$").find(l) ?: return md
        val now = if (m.groupValues[2] == " ") "x" else " "
        lines[line] = m.groupValues[1] + "[" + now + "]" + m.groupValues[3]
        return lines.joinToString("\n")
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Inline Markdown: code first (its insides kept as they are), then links, bold and italics. */
    fun inline(s: String): String {
        val codes = ArrayList<String>()
        var t = Regex("`([^`]+)`").replace(s) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
        t = escape(t)
        t =
            Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").replace(t) {
                val href = it.groupValues[2]
                if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) "<a href=\"$href\">${it.groupValues[1]}</a>" else it.groupValues[1]
            }
        t = Regex("(?<![\"=>])\\bhttps?://[^\\s<]+[^\\s<.,;:!?)\\]]").replace(t) { "<a href=\"${it.value}\">${it.value}</a>" }
        t = Regex("\\*\\*(.+?)\\*\\*").replace(t, "<b>$1</b>")
        t = Regex("__(.+?)__").replace(t, "<b>$1</b>")
        t = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])").replace(t, "<i>$1</i>")
        t = Regex("(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])").replace(t, "<i>$1</i>")
        t = Regex("~~(.+?)~~").replace(t, "<s>$1</s>")
        return Regex("\u0000(\\d+)\u0000").replace(t) { "<code>" + escape(codes[it.groupValues[1].toInt()]) + "</code>" }
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val SEPARATOR = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val ITEM = Regex("^(\\s{0,3})([-*+]|\\d+[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
}
