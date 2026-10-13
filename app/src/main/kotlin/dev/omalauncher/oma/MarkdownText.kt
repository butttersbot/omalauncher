package dev.omalauncher.oma

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Pack

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

/** Opens a tapped link where Android opens web pages. */
private fun webLinks(context: Context) =
    object : LinkInteractionListener {
        override fun onClick(link: LinkAnnotation) {
            val url = (link as? LinkAnnotation.Clickable)?.tag?.takeIf(Markdown::isWebLink) ?: return
            // A phone with no browser has nowhere to open it, and the address is still there to read and copy.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

/**
 * The agent's answer with its Markdown drawn: headings, lists, quotes, code, tables and links. The text is read
 * into blocks once for each change to it, not each time the thread is drawn again.
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val context = LocalContext.current
    val pack = Pack.look
    val look = remember(pack, context) { MarkdownLook(Pack.body(), Pack.mono().toSpanStyle().copy(background = Pack.surface), Pack.accent, webLinks(context)) }
    Blocks(blocks, look, modifier)
}

@Composable
private fun Blocks(
    blocks: List<MdBlock>,
    look: MarkdownLook,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            // The items of one list stand closer together than blocks do.
            val above = if (index == 0) 0.dp else if (block.listed && blocks[index - 1].listed) 3.dp else 8.dp
            Block(block, look, Modifier.padding(top = above))
        }
    }
}

private val MdBlock.listed get() = this is MdBlock.Bullet || this is MdBlock.Numbered

@Composable
private fun Block(
    block: MdBlock,
    look: MarkdownLook,
    modifier: Modifier,
) {
    when (block) {
        is MdBlock.Paragraph -> Spans(block.spans, look, look.body, modifier)
        is MdBlock.Heading -> Heading(block, look, modifier)
        is MdBlock.Bullet -> Item("•", block.depth, block.spans, look, modifier)
        is MdBlock.Numbered -> Item("${block.number}.", block.depth, block.spans, look, modifier)
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
    depth: Int,
    spans: List<MdSpan>,
    look: MarkdownLook,
    modifier: Modifier,
) {
    Row(modifier.padding(start = ListIndent * minOf(depth, MAX_INDENT))) {
        BasicText(marker, Modifier.padding(end = 8.dp), style = look.body.copy(color = Pack.muted))
        Spans(spans, look, look.body, Modifier.weight(1f))
    }
}

/** Code keeps its lines: a long one is scrolled sideways to, not wrapped. */
@Composable
private fun CodeBlock(
    block: MdBlock.Code,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().clip(Pack.shape(8.dp)).background(Pack.surface).padding(10.dp)) {
        if (block.language.isNotEmpty()) BasicText(Pack.caption(block.language), Modifier.padding(bottom = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted))
        BasicText(block.code, Modifier.horizontalScroll(rememberScrollState()), style = Pack.mono(Pack.size.small), softWrap = false)
    }
}

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
