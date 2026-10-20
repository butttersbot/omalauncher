package dev.omalauncher.oma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import dev.omalauncher.ui.rememberWebOpener
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.delay

/** How far each level of a nested list steps in, and the deepest that still does: a cover screen has no room for more. */
private val ListIndent = 14.dp
private const val MAX_INDENT = 5

/** A table cell wraps at this width, so one long cell can't push the next column a screen away. */
private val CellWidth = 220.dp
private val ColumnGap = 16.dp
private val RowGap = 8.dp

/** What an answer is drawn with: the pack's voices and colors, read once for all its blocks. */
private class MarkdownLook(
    val body: TextStyle,
    val code: SpanStyle,
    val link: Color,
    val onLink: LinkInteractionListener,
) {
    val strong = body.copy(fontWeight = FontWeight.Bold)

    /** The same look with its text in [color]. */
    fun inked(color: Color) = MarkdownLook(body.copy(color = color), code, link, onLink)

    fun styled(spans: List<MdSpan>): AnnotatedString =
        buildAnnotatedString {
            for (span in spans) {
                var style = if (span.code) code else SpanStyle()
                if (span.bold) style = style.copy(fontWeight = FontWeight.Bold)
                if (span.italic) style = style.copy(fontStyle = FontStyle.Italic)
                if (span.strike) style = style.copy(textDecoration = TextDecoration.LineThrough)
                // Only a web page opens from here, so only that looks like a link; a file's path keeps its words.
                val url = span.link?.takeIf(Markdown::isWebLink)
                if (url != null) {
                    style = style.copy(color = link)
                    pushLink(LinkAnnotation.Clickable(url, linkInteractionListener = onLink))
                }
                pushStyle(style)
                append(span.text)
                pop()
                if (url != null) pop()
            }
        }
}

/** Opens a tapped link where the platform opens web pages ([rememberWebOpener]). */
private fun webLinks(open: (String) -> Unit) =
    object : LinkInteractionListener {
        override fun onClick(link: LinkAnnotation) {
            val url = (link as? LinkAnnotation.Clickable)?.tag?.takeIf(Markdown::isWebLink) ?: return
            open(url)
        }
    }

/**
 * The agent's answer with its Markdown drawn: headings, lists, quotes, code, tables and links. The text is read
 * into blocks once for each change to it, not each time the thread is drawn again.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val open = rememberWebOpener()
    val pack = Pack.look
    val look = remember(pack, open) { MarkdownLook(Pack.body(), Pack.mono().toSpanStyle().copy(background = Pack.surface), Pack.accent, webLinks(open)) }
    Blocks(blocks, look, modifier)
}

@Composable
private fun Blocks(
    blocks: List<MdBlock>,
    look: MarkdownLook,
    modifier: Modifier = Modifier,
) {
    val digits = remember(blocks) { numberDigits(blocks) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            // The items of one list stand closer together than blocks do.
            val above = if (index == 0) 0.dp else if (block.listed && blocks[index - 1].listed) 3.dp else 8.dp
            Block(block, digits[index], look, Modifier.padding(top = above))
        }
    }
}

/**
 * For each numbered item, the digits in the longest number of its list at its depth, a list being the items
 * with nothing else between them; 0 for every other block. The numbers of one list are set in a column that
 * wide, so "9." and "10." end in line and the words after them start in line.
 */
fun numberDigits(blocks: List<MdBlock>): IntArray {
    val digits = IntArray(blocks.size)
    var start = 0
    while (start < blocks.size) {
        var end = start
        while (end < blocks.size && blocks[end].listed) end++
        if (end == start) {
            start++
            continue
        }
        val widest = HashMap<Int, Int>()
        for (i in start until end) (blocks[i] as? MdBlock.Numbered)?.let { widest[it.depth] = maxOf(widest[it.depth] ?: 0, it.number.length) }
        for (i in start until end) (blocks[i] as? MdBlock.Numbered)?.let { digits[i] = widest.getValue(it.depth) }
        start = end
    }
    return digits
}

private val MdBlock.listed get() = this is MdBlock.Bullet || this is MdBlock.Numbered

@Composable
private fun Block(
    block: MdBlock,
    digits: Int,
    look: MarkdownLook,
    modifier: Modifier,
) {
    when (block) {
        is MdBlock.Paragraph -> Spans(block.spans, look, look.body, modifier)
        is MdBlock.Heading -> Heading(block, look, modifier)
        is MdBlock.Bullet -> Item("•", 0, block.depth, block.spans, look, modifier)
        is MdBlock.Numbered -> Item("${block.number}.", digits, block.depth, block.spans, look, modifier)
        is MdBlock.Code -> CodeBlock(block, modifier)
        is MdBlock.Quote -> {
            val line = Pack.border
            val quiet = remember(look) { look.inked(Pack.muted) }
            Blocks(block.blocks, quiet, modifier.drawBehind { drawRect(line, size = Size(3.dp.toPx(), size.height)) }.padding(start = 12.dp))
        }
        MdBlock.Rule -> Box(modifier.padding(vertical = 4.dp).fillMaxWidth().height(Pack.hair).background(Pack.border))
        is MdBlock.Table -> Table(block, look, modifier)
    }
}

@Composable
private fun Spans(
    spans: List<MdSpan>,
    look: MarkdownLook,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val text = remember(spans, look) { look.styled(spans) }
    BasicText(text, modifier, style)
}

/** Six levels in three sizes: the first two in the pack's heading voice, the rest as bold body text. */
@Composable
private fun Heading(
    heading: MdBlock.Heading,
    look: MarkdownLook,
    modifier: Modifier,
) {
    if (heading.level > 2) {
        Spans(heading.spans, look, look.strong, modifier.padding(top = 2.dp))
    } else {
        // Spelled as the pack spells a heading, except code, which is exact.
        val spans = remember(heading, look) { heading.spans.map { if (it.code) it else it.copy(text = Pack.heading(it.text)) } }
        Spans(spans, look, Pack.display(if (heading.level == 1) Pack.size.large else Pack.size.item, look.body.color), modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Item(
    marker: String,
    digits: Int,
    depth: Int,
    spans: List<MdSpan>,
    look: MarkdownLook,
    modifier: Modifier,
) {
    val style = remember(look) { look.body.copy(color = Pack.muted, textAlign = TextAlign.End) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // A number stands at the right of a column as wide as its list's longest; a bullet is as wide as itself.
    val column = if (digits > 0) remember(digits, style, density) { with(density) { measurer.measure("0".repeat(digits) + ".", style).size.width.toDp() } } else null
    Row(modifier.padding(start = ListIndent * minOf(depth, MAX_INDENT))) {
        BasicText(marker, Modifier.padding(end = 8.dp).then(if (column != null) Modifier.width(column) else Modifier), style = style)
        Spans(spans, look, look.body, Modifier.weight(1f))
    }
}

/**
 * Code keeps its lines: a long one is scrolled sideways to, not wrapped. A tap anywhere on it copies it, exactly as
 * written (Boss, 2026-10-19: "I need to be able to click it, copy to clipboard"); its corner says so for a moment.
 */
@Composable
private fun CodeBlock(
    block: MdBlock.Code,
    modifier: Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MILLIS); copied = false } }
    Column(
        modifier.fillMaxWidth().clip(Pack.shape(8.dp)).background(Pack.surface)
            .clickable(onClickLabel = "copy") { clipboard.setText(AnnotatedString(block.code)); copied = true }
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            BasicText(if (block.language.isNotEmpty()) Pack.caption(block.language) else "", Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted))
            BasicText(if (copied) "copied" else "[ copy ]", style = Pack.mono(Pack.size.small, if (copied) Pack.accent else Pack.muted))
        }
        BasicText(block.code, Modifier.horizontalScroll(rememberScrollState()), style = Pack.mono(Pack.size.small), softWrap = false)
    }
}

/** How long a code block says "copied" after a tap. */
private const val COPIED_MILLIS = 1_500L

/**
 * A table as a grid: each column as wide as its widest cell, a line under the header. One too wide for the
 * screen is scrolled sideways, so its columns stay in line on the cover screen too.
 */
@Composable
private fun Table(
    table: MdBlock.Table,
    look: MarkdownLook,
    modifier: Modifier,
) {
    val columns = table.header.size
    val hair = Pack.hair
    Layout(
        content = {
            for (cell in table.header) Spans(cell, look, look.strong)
            for (row in table.rows) for (cell in row) Spans(cell, look, look.body)
            Box(Modifier.background(Pack.border))
        },
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) { measurables, _ ->
        val cells = measurables.dropLast(1).map { it.measure(Constraints(maxWidth = CellWidth.roundToPx())) }
        val widths = IntArray(columns)
        val heights = IntArray(cells.size / columns)
        cells.forEachIndexed { index, cell ->
            widths[index % columns] = maxOf(widths[index % columns], cell.width)
            heights[index / columns] = maxOf(heights[index / columns], cell.height)
        }
        val gap = ColumnGap.roundToPx()
        val between = RowGap.roundToPx()
        val width = widths.sum() + gap * (columns - 1)
        val rule = measurables.last().measure(Constraints.fixed(width, hair.roundToPx()))
        // The line under the header stands in the gap after it, which a table with no rows yet still has.
        layout(width, heights.sum() + between * maxOf(heights.size - 1, 1)) {
            var y = 0
            heights.forEachIndexed { row, height ->
                var x = 0
                for (column in 0 until columns) {
                    cells[row * columns + column].place(x, y)
                    x += widths[column] + gap
                }
                y += height + between
                if (row == 0) rule.place(0, y - (between + rule.height) / 2)
            }
        }
    }
}
