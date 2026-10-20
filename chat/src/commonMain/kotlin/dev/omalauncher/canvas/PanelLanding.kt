package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.OVERFLOW_PANELS
import dev.omalauncher.widgets.layout.PanelLayouts
import kotlin.math.abs

/*
 * Where an agent's module or widget lands on a manga page (the Manga look, piece B): in an empty panel, never in cells between
 * a page's panel lines, so a page cut into panels keeps one thing to a panel whoever arranges it.
 */

/**
 * The empty panel of the manga page [page] nearest in size to [w] by [h] (the first of equals, in the layout's order), or null
 * when none is empty. [moving]'s own panel counts as empty: what is moved within its page may stay.
 */
internal fun panelSpot(n: Notebook, page: NotebookPage, w: Int, h: Int, moving: String?, rules: ScreenRules): Cell? {
    val panels = PanelLayouts.panels(page.panels ?: return null, n.columns, n.rows)
    val busy = page.items.filter { it.key != moving }.mapNotNull { it.cell } + rules.pinned(n)
    return panels.map { it.cell }.filter { c -> busy.none { it.overlaps(c) } }.minByOrNull { abs(it.w - w) + abs(it.h - h) }
}

/**
 * How a page made for what had no room anywhere is cut, when the page asked for is a manga page: the overflow strip (or that
 * page's own layout where the strip can't be cut); null for a free page, whose new page is free too.
 */
internal fun overflowCut(n: Notebook, asked: String): String? =
    n.pages.firstOrNull { it.id == asked }?.panels?.let { own -> OVERFLOW_PANELS.takeIf { PanelLayouts.fits(it, n.columns, n.rows) } ?: own }
