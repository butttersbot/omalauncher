package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private fun text(vararg spans: MdSpan) = listOf(*spans)

    private fun plain(text: String) = listOf(MdSpan(text))

    private fun paragraph(text: String) = listOf(MdBlock.Paragraph(plain(text)))

    @Test fun plainTextIsOneParagraphWithTheSameText() {
        assertEquals(paragraph("Task completed."), Markdown.parse("Task completed."))
        assertEquals(paragraph("The agent exited with status 1. Open its terminal to check sign-in or permissions."), Markdown.parse("The agent exited with status 1. Open its terminal to check sign-in or permissions."))
        assertEquals(emptyList<MdBlock>(), Markdown.parse(""))
        assertEquals(emptyList<MdBlock>(), Markdown.parse(" \n\n"))
    }

    @Test fun aParagraphKeepsItsLinesAndABlankLineStartsAnother() {
        assertEquals(
            listOf(MdBlock.Paragraph(plain("first line\nsecond line")), MdBlock.Paragraph(plain("another"))),
            Markdown.parse("first line\nsecond line\r\n\nanother"),
        )
    }

    @Test fun headingsKeepTheirLevelAndNeedASpace() {
        assertEquals(listOf(MdBlock.Heading(1, plain("Title"))), Markdown.parse("# Title"))
        assertEquals(listOf(MdBlock.Heading(6, plain("Deep"))), Markdown.parse("###### Deep ######"))
        assertEquals(listOf(MdBlock.Heading(2, text(MdSpan("Using "), MdSpan("C#", code = true)))), Markdown.parse("## Using `C#`"))
        assertEquals(listOf(MdBlock.Heading(2, plain("C#"))), Markdown.parse("## C#"))
        assertEquals(paragraph("#hashtag"), Markdown.parse("#hashtag"))
        assertEquals(paragraph("####### seven"), Markdown.parse("####### seven"))
    }

    @Test fun bulletsAndNumbersNestByIndentation() {
        val source =
            """
            - one
              - inner **bold**
                1. deepest
              - inner again
            * two
            3. three
            4) four
            """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Bullet(0, plain("one")),
                MdBlock.Bullet(1, text(MdSpan("inner "), MdSpan("bold", bold = true))),
                MdBlock.Numbered(2, "1", plain("deepest")),
                MdBlock.Bullet(1, plain("inner again")),
                MdBlock.Bullet(0, plain("two")),
                MdBlock.Numbered(0, "3", plain("three")),
                MdBlock.Numbered(0, "4", plain("four")),
            ),
            Markdown.parse(source),
        )
    }

    @Test fun aListItemTakesTheLinesUnderItAndABlankLineEndsIt() {
        assertEquals(
            listOf(MdBlock.Bullet(0, plain("one\ncontinued")), MdBlock.Bullet(0, plain("two")), MdBlock.Paragraph(plain("after"))),
            Markdown.parse("- one\n  continued\n\n- two\n\nafter"),
        )
        // Not lists: no space after the mark, and arithmetic at the start of a line.
        assertEquals(paragraph("-5 degrees"), Markdown.parse("-5 degrees"))
        assertEquals(paragraph("*not a bullet"), Markdown.parse("*not a bullet"))
        assertEquals(paragraph("1.5 litres"), Markdown.parse("1.5 litres"))
    }

    @Test fun aFencedBlockIsVerbatim() {
        val source = "Run:\n```kotlin\nval a = **b**\n\n    indented_name # not a heading\n- not a list\n```\ndone"
        assertEquals(
            listOf(
                MdBlock.Paragraph(plain("Run:")),
                MdBlock.Code("kotlin", "val a = **b**\n\n    indented_name # not a heading\n- not a list"),
                MdBlock.Paragraph(plain("done")),
            ),
            Markdown.parse(source),
        )
        assertEquals(listOf(MdBlock.Code("", "a\n```\nb")), Markdown.parse("~~~~\na\n```\nb\n~~~~"))
        // Inside a list item the fence is indented with it, and that indent is not code.
        assertEquals(
            listOf(MdBlock.Numbered(0, "1", plain("step")), MdBlock.Code("sh", "ls\n  -la"), MdBlock.Numbered(0, "2", plain("next"))),
            Markdown.parse("1. step\n   ```sh\n   ls\n     -la\n   ```\n2. next"),
        )
    }

    @Test fun anUnclosedFenceShowsWhatHasArrivedAsCode() {
        assertEquals(listOf(MdBlock.Paragraph(plain("Here:")), MdBlock.Code("py", "print(1)\n# still coming")), Markdown.parse("Here:\n```py\nprint(1)\n# still coming"))
        assertEquals(listOf(MdBlock.Code("", "")), Markdown.parse("```"))
        assertEquals(listOf(MdBlock.Code("json", "")), Markdown.parse("```json"))
        // Three backticks that are closed on their own line are inline code, not a fence.
        assertEquals(listOf(MdBlock.Paragraph(text(MdSpan("x", code = true), MdSpan(" is set")))), Markdown.parse("```x``` is set"))
    }

    @Test fun quotesHoldBlocksOfTheirOwn() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Paragraph(plain("said\nmore")), MdBlock.Bullet(0, plain("point")), MdBlock.Quote(listOf(MdBlock.Paragraph(plain("deeper")))))), MdBlock.Paragraph(plain("out"))),
            Markdown.parse("> said\n> more\n>\n> - point\n> > deeper\n\nout"),
        )
        // Nothing is lost however deep a quote goes.
        val deep = Markdown.parse(">".repeat(5_000) + " end")
        assertEquals(1, deep.size)
        assertTrue(deep[0] is MdBlock.Quote)
    }

    @Test fun rulesAreNotListsOrEmphasis() {
        assertEquals(listOf(MdBlock.Paragraph(plain("a")), MdBlock.Rule, MdBlock.Paragraph(plain("b")), MdBlock.Rule, MdBlock.Rule), Markdown.parse("a\n\n---\nb\n* * *\n___"))
        assertEquals(paragraph("--"), Markdown.parse("--"))
    }

    @Test fun aPipeTableIsAHeaderAndRowsOfTheSameWidth() {
        val source =
            """
            Before
            | Name | Size |
            |:-----|-----:|
            | `a.kt` | **12** |
            | b \| c |
            | d | e | extra |
            After
            """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Paragraph(plain("Before")),
                MdBlock.Table(
                    listOf(plain("Name"), plain("Size")),
                    listOf(
                        listOf(text(MdSpan("a.kt", code = true)), text(MdSpan("12", bold = true))),
                        listOf(plain("b | c"), emptyList()),
                        listOf(plain("d"), plain("e")),
                    ),
                ),
                MdBlock.Paragraph(plain("After")),
            ),
            Markdown.parse(source),
        )
        assertEquals(listOf(MdBlock.Table(listOf(plain("a"), plain("b")), emptyList())), Markdown.parse("a | b\n--|--"))
        // A bar in a sentence is not a table, and neither is a header whose row of dashes hasn't arrived.
        assertEquals(paragraph("a | b\nc | d"), Markdown.parse("a | b\nc | d"))
        assertEquals(listOf(MdBlock.Paragraph(plain("yes | no")), MdBlock.Rule), Markdown.parse("yes | no\n---"))
    }

    @Test fun boldItalicCodeAndStrikethrough() {
        assertEquals(text(MdSpan("a "), MdSpan("bold", bold = true), MdSpan(" b")), Markdown.inline("a **bold** b"))
        assertEquals(text(MdSpan("bold", bold = true)), Markdown.inline("__bold__"))
        assertEquals(text(MdSpan("it", italic = true), MdSpan(" and "), MdSpan("it", italic = true)), Markdown.inline("*it* and _it_"))
        assertEquals(text(MdSpan("both", bold = true, italic = true)), Markdown.inline("***both***"))
        assertEquals(text(MdSpan("both", bold = true, italic = true)), Markdown.inline("___both___"))
        assertEquals(text(MdSpan("gone", strike = true), MdSpan(" ~5 min")), Markdown.inline("~~gone~~ ~5 min"))
        assertEquals(text(MdSpan("run "), MdSpan("ls -la", code = true)), Markdown.inline("run `ls -la`"))
        assertEquals(text(MdSpan("a`b", code = true)), Markdown.inline("`` a`b ``"))
        // Inside code nothing else is read.
        assertEquals(text(MdSpan("**x** [y](z) \\*", code = true)), Markdown.inline("`**x** [y](z) \\*`"))
        assertEquals(text(MdSpan("Note:", bold = true), MdSpan(" done")), Markdown.inline("**Note:** done"))
    }

    @Test fun emphasisNests() {
        assertEquals(text(MdSpan("a ", bold = true), MdSpan("b", bold = true, italic = true), MdSpan(" c", bold = true)), Markdown.inline("**a *b* c**"))
        assertEquals(text(MdSpan("a ", italic = true), MdSpan("b", bold = true, italic = true), MdSpan(" c", italic = true)), Markdown.inline("*a **b** c*"))
        assertEquals(text(MdSpan("a ", italic = true), MdSpan("b", bold = true, italic = true)), Markdown.inline("*a **b***"))
        assertEquals(text(MdSpan("a", bold = true, italic = true), MdSpan(" b", italic = true)), Markdown.inline("***a** b*"))
        assertEquals(text(MdSpan("a", bold = true, italic = true), MdSpan(" b", bold = true)), Markdown.inline("***a* b**"))
        assertEquals(text(MdSpan("x ", bold = true), MdSpan("y", bold = true, code = true), MdSpan(" ", bold = true), MdSpan("z", bold = true, strike = true)), Markdown.inline("**x `y` ~~z~~**"))
        assertEquals(text(MdSpan("a ", bold = true), MdSpan("b", bold = true, italic = true)), Markdown.inline("**a _b_**"))
    }

    @Test fun namesAndArithmeticAreNotEmphasis() {
        for (literal in listOf("snake_case_name", "2*3*4", "5 * 3 * 2", "a_b and c_d", "_private_var", "2**3**4", "*args and **kwargs", "*.kt and *.md", "MAX_PROMPT * 2", "x_ and y_", "a ~ b ~~ c")) {
            assertEquals(literal, plain(literal), Markdown.inline(literal))
        }
        // A Korean particle is written on the word it follows, and the word before it is still bold.
        assertEquals(text(MdSpan("단어", bold = true), MdSpan("는 좋다")), Markdown.inline("**단어**는 좋다"))
    }

    @Test fun unterminatedMarkersAreText() {
        for (literal in listOf("**bold", "*it", "__bold", "_it", "~~gone", "`code", "``a`", "[text", "[text](", "[text](url", "[text] (url)", "a ** b ** c", "**a*", "****", "a * b", "trailing *", "trailing `", "![alt", "<https://x.dev")) {
            assertEquals(literal, literal, Markdown.inline(literal).joinToString("") { it.text })
            assertTrue(literal, Markdown.inline(literal).none { it.bold || it.italic || it.strike || it.code })
        }
        assertEquals(text(MdSpan("a ", bold = true), MdSpan("b", bold = true, code = true), MdSpan(" *c")), Markdown.inline("**a `b`** *c"))
    }

    @Test fun linksAndBareAddresses() {
        assertEquals(text(MdSpan("see "), MdSpan("the docs", link = "https://x.dev/a_b"), MdSpan(".")), Markdown.inline("see [the docs](https://x.dev/a_b)."))
        assertEquals(text(MdSpan("the ", link = "https://x.dev"), MdSpan("docs", bold = true, link = "https://x.dev")), Markdown.inline("[the **docs**](https://x.dev \"title\")"))
        assertEquals(text(MdSpan("a link", bold = true, link = "http://x.dev")), Markdown.inline("**[a link](http://x.dev)**"))
        assertEquals(text(MdSpan("wiki", link = "https://en.wikipedia.org/wiki/A_(b)")), Markdown.inline("[wiki](https://en.wikipedia.org/wiki/A_(b))"))
        assertEquals(text(MdSpan("Go to "), MdSpan("https://x.dev/a_b?c=1", link = "https://x.dev/a_b?c=1"), MdSpan(".")), Markdown.inline("Go to https://x.dev/a_b?c=1."))
        assertEquals(text(MdSpan("("), MdSpan("https://x.dev/a_(b)", link = "https://x.dev/a_(b)"), MdSpan(")")), Markdown.inline("(https://x.dev/a_(b))"))
        assertEquals(text(MdSpan("https://x.dev", link = "https://x.dev"), MdSpan(" and "), MdSpan("https://y.dev", bold = true, link = "https://y.dev")), Markdown.inline("<https://x.dev> and **https://y.dev**"))
        assertEquals(text(MdSpan("Main.kt", link = "src/Main.kt")), Markdown.inline("[Main.kt](src/Main.kt)"))
        assertEquals(text(MdSpan("a chart", link = "https://x.dev/c.png")), Markdown.inline("![a chart](https://x.dev/c.png)"))
        assertEquals(text(MdSpan("https://x.dev/c.png", link = "https://x.dev/c.png")), Markdown.inline("![](https://x.dev/c.png)"))
        // An address in the middle of a word, or with nothing after the scheme, is text.
        assertEquals(plain("xhttps://x.dev"), Markdown.inline("xhttps://x.dev"))
        assertEquals(plain("https://"), Markdown.inline("https://"))
        assertEquals(plain("array[0] and f(x)"), Markdown.inline("array[0] and f(x)"))
    }

    @Test fun onlyWebLinksOpen() {
        assertTrue(Markdown.isWebLink("https://x.dev"))
        assertTrue(Markdown.isWebLink("HTTP://x.dev"))
        for (other in listOf("src/Main.kt", "file:///sdcard/a", "intent://x#Intent;end", "javascript:alert(1)", "tel:112", "content://x", " https://x.dev", "")) assertFalse(other, Markdown.isWebLink(other))
    }

    @Test fun aBackslashMakesAMarkText() {
        assertEquals(plain("*not italic* and `not code` and [not](a link)"), Markdown.inline("\\*not italic\\* and \\`not code\\` and \\[not\\](a link)"))
        assertEquals(plain("a\\b and C:\\dir"), Markdown.inline("a\\b and C:\\dir"))
        assertEquals(text(MdSpan("2 * 3", bold = true)), Markdown.inline("**2 \\* 3**"))
        assertEquals(paragraph("# not a heading"), Markdown.parse("\\# not a heading"))
    }

    @Test fun aLongAnswerIsBoundedAndWhole() {
        val lines = List(5_000) { "- item $it" }
        val blocks = Markdown.parse(lines.joinToString("\n"))
        assertEquals(Markdown.MAX_BLOCKS + 1, blocks.size)
        assertEquals(MdBlock.Bullet(0, plain("item 0")), blocks.first())
        assertEquals(MdBlock.Paragraph(plain(lines.drop(Markdown.MAX_BLOCKS).joinToString("\n"))), blocks.last())
    }

    @Test fun strayMarkersInALongAnswerDoNotHang() {
        val started = System.nanoTime()
        for (unit in listOf("*a ", "[a ", "`a ``b ", "**a _b ", "> ", "| a ", "~~a ", "\\", "<https://a ", "![", "*a* **b** `c` [d](e) ")) {
            val source = unit.repeat(40_000 / unit.length)
            val whole = Markdown.inline(source)
            assertTrue(unit, whole.sumOf { it.text.length } <= source.length)
            Markdown.parse(source)
        }
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
    }
}
