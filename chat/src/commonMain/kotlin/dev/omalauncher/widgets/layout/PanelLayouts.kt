package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * The Manga look's panel layouts (features/manga-look in ClaudeSync; the mockup's SHEETS and panelsOf are the reference).
 * A panel is a plain rectangle of cells underneath; the slanted ink and the gutters are only drawn on top, so everything
 * that places cells keeps working. Each cut snaps to the grid once, where it crosses the middle of the cells it splits,
 * so a layout's panels tile the page exactly for any number of rows.
 */

/** A point on a page, in fractions of its width ([x]) and height ([y]), 0 to 1. */
data class PanelPoint(val x: Double, val y: Double)

/** A rectangle on a page, in fractions of its width and height. */
data class PanelRect(val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * One panel of a page: the [cell] its contents take on the grid, the [outline] its ink is drawn along (a convex polygon,
 * inside the page's margins and gutters), the [bleed] shape its art fills when the panel is as wide as the page (null for
 * every narrower panel, whose art keeps the margins), and the [inner] rectangle a widget is drawn in, clear of the slants.
 */
data class Panel(val cell: Cell, val outline: List<PanelPoint>, val bleed: List<PanelPoint>?, val inner: PanelRect) {
    /** The outline's area, in fractions of the page: how big the panel looks, which refitting goes by. */
    val area: Double get() = polygonArea(outline)
}

/**
 * How a page is cut, in the mockup's page units: a page 60 wide and 96 tall. A cut [Across] runs from the left edge at
 * [Across.from] to the right edge at [Across.to] (unequal ends slant it), [Across.above] and [Across.below] are what it
 * leaves; a cut [Down] runs from the top at [Down.from] to the bottom at [Down.to].
 */
sealed interface PanelCut {
    data object Leaf : PanelCut

    data class Across(val from: Double, val to: Double, val above: PanelCut, val below: PanelCut) : PanelCut

    data class Down(val from: Double, val to: Double, val left: PanelCut, val right: PanelCut) : PanelCut
}

/**
 * The page's margins and gutters, in the same page units (60 by 96): top and bottom deeper, as in a printed volume, and
 * the gaps between rows wider than between panels side by side. [pad] is how far a widget keeps inside a panel's ink.
 * A look's `[panels]` style may set its own.
 */
data class PanelStyle(
    val marginLeft: Double = 2.4,
    val marginRight: Double = 2.4,
    val marginTop: Double = 3.0,
    val marginBottom: Double = 4.4,
    val gutterRows: Double = 2.3,
    val gutterCols: Double = 1.1,
    val pad: Double = 1.1,
)

/** A panel layout: its [id] as notebooks save it, the [name] people see, and its cuts. */
data class PanelLayout(val id: String, val name: String, val tree: PanelCut)

object PanelLayouts {
    /** The page's size in the units cuts and styles are given in. */
    const val PAGE_W = 60.0
    const val PAGE_H = 96.0

    private val L = PanelCut.Leaf
    private fun h(from: Double, to: Double, above: PanelCut, below: PanelCut) = PanelCut.Across(from, to, above, below)
    private fun v(from: Double, to: Double, left: PanelCut, right: PanelCut) = PanelCut.Down(from, to, left, right)

    /** Every layout, in the order they are offered. At most one slanted cut on a page. */
    val all: List<PanelLayout> = listOf(
        PanelLayout("opening", "Opening", h(38.0, 38.0, L, h(62.0, 57.0, v(30.0, 30.0, L, L), v(23.0, 23.0, L, L)))),
        PanelLayout("beats", "Three beats", h(33.0, 33.0, L, h(63.0, 63.0, L, L))),
        PanelLayout("tallstack", "Tall + stack", v(29.0, 29.0, L, h(35.0, 35.0, L, h(65.0, 65.0, L, L)))),
        PanelLayout("action5", "Action 5", h(36.0, 36.0, v(27.0, 33.0, L, L), h(66.0, 66.0, L, v(36.0, 36.0, L, L)))),
        PanelLayout("splash3", "Splash + 3", h(47.0, 47.0, L, v(31.0, 31.0, L, h(72.0, 72.0, L, L)))),
        PanelLayout("yonkoma", "4-koma strip", h(25.5, 25.5, L, h(48.0, 48.0, L, h(70.5, 70.5, L, L)))),
        PanelLayout("grid6", "Grid of 6", h(33.0, 33.0, v(30.0, 30.0, L, L), h(64.0, 64.0, v(30.0, 30.0, L, L), v(30.0, 30.0, L, L)))),
        PanelLayout("splash", "Full splash", L),
    )

    val ids: List<String> = all.map { it.id }

    fun known(id: String?): Boolean = id != null && all.any { it.id == id }

    fun layout(id: String): PanelLayout? = all.firstOrNull { it.id == id }

    /** The name people see for [id], or null for a layout this build doesn't know. */
    fun name(id: String): String? = layout(id)?.name

    /**
     * The panels of the layout [id] on a page of [columns] by [rows] cells, in the layout's order (each cut's first side
     * before its second): a panel's place in this list is its number. Empty when [id] is unknown or the grid is too small
     * for one of its cuts to leave a cell on each side.
     */
    fun panels(id: String, columns: Int, rows: Int, style: PanelStyle = PanelStyle()): List<Panel> {
        val tree = layout(id)?.tree ?: return emptyList()
        if (columns < 1 || rows < 1) return emptyList()
        return cut(tree, columns, rows, style) ?: emptyList()
    }

    /** Whether [id] can be cut on a page of [columns] by [rows]. */
    fun fits(id: String, columns: Int, rows: Int): Boolean = panels(id, columns, rows).isNotEmpty()

    private class Cells(val c0: Int, val r0: Int, val c1: Int, val r1: Int)

    private class Raw(val pts: List<PanelPoint>, val bleed: List<PanelPoint>, val cells: Cells)

    private fun cut(tree: PanelCut, columns: Int, rows: Int, style: PanelStyle): List<Panel>? {
        val cellW = PAGE_W / columns
        val cellH = PAGE_H / rows
        val out = ArrayList<Raw>()
        fun rec(node: PanelCut, poly: List<PanelPoint>, bleed: List<PanelPoint>, cells: Cells): Boolean {
            val p1: PanelPoint
            val p2: PanelPoint
            val gutter: Double
            val a: Cells
            val b: Cells
            val first: PanelCut
            val second: PanelCut
            when (node) {
                PanelCut.Leaf -> { out += Raw(poly, bleed, cells); return poly.size >= 3 }
                is PanelCut.Across -> {
                    if (cells.r1 - cells.r0 < 2) return false
                    // Where the cut crosses the middle of the cells it splits, to the nearest row line.
                    val mx = (cells.c0 + cells.c1) / 2.0 * cellW
                    val y = node.from + (node.to - node.from) * mx / PAGE_W
                    val r = (y / cellH).roundToInt().coerceIn(cells.r0 + 1, cells.r1 - 1)
                    p1 = PanelPoint(0.0, node.from); p2 = PanelPoint(PAGE_W, node.to); gutter = style.gutterRows
                    a = Cells(cells.c0, cells.r0, cells.c1, r); b = Cells(cells.c0, r, cells.c1, cells.r1)
                    first = node.above; second = node.below
                }
                is PanelCut.Down -> {
                    if (cells.c1 - cells.c0 < 2) return false
                    val my = (cells.r0 + cells.r1) / 2.0 * cellH
                    val x = node.from + (node.to - node.from) * my / PAGE_H
                    val c = (x / cellW).roundToInt().coerceIn(cells.c0 + 1, cells.c1 - 1)
                    p1 = PanelPoint(node.to, PAGE_H); p2 = PanelPoint(node.from, 0.0); gutter = style.gutterCols
                    a = Cells(cells.c0, cells.r0, c, cells.r1); b = Cells(c, cells.r0, cells.c1, cells.r1)
                    first = node.left; second = node.right
                }
            }
            return rec(first, clip(poly, p1, p2, -1.0, gutter / 2), clip(bleed, p1, p2, -1.0, gutter / 2), a) &&
                rec(second, clip(poly, p1, p2, 1.0, gutter / 2), clip(bleed, p1, p2, 1.0, gutter / 2), b)
        }
        val page = listOf(
            PanelPoint(style.marginLeft, style.marginTop), PanelPoint(PAGE_W - style.marginRight, style.marginTop),
            PanelPoint(PAGE_W - style.marginRight, PAGE_H - style.marginBottom), PanelPoint(style.marginLeft, PAGE_H - style.marginBottom),
        )
        val sheet = listOf(PanelPoint(0.0, 0.0), PanelPoint(PAGE_W, 0.0), PanelPoint(PAGE_W, PAGE_H), PanelPoint(0.0, PAGE_H))
        if (!rec(tree, page, sheet, Cells(0, 0, columns, rows))) return null
        fun frac(pts: List<PanelPoint>) = pts.map { PanelPoint(it.x / PAGE_W, it.y / PAGE_H) }
        return out.map { raw ->
            val xs = raw.pts.map { it.x }.sorted()
            val ys = raw.pts.map { it.y }.sorted()
            val n = raw.pts.size
            val pad = style.pad
            // Inside the slants: the second-outermost corners on each side, then the pad.
            val inner = PanelRect(
                (xs[1] + pad) / PAGE_W, (ys[1] + pad) / PAGE_H,
                ((xs[n - 2] - xs[1] - 2 * pad) / PAGE_W).coerceAtLeast(0.0), ((ys[n - 2] - ys[1] - 2 * pad) / PAGE_H).coerceAtLeast(0.0),
            )
            // Only a panel as wide as the page bleeds (an establishing shot); narrower art keeps the page's margins.
            val wide = raw.cells.c0 == 0 && raw.cells.c1 == columns
            Panel(
                Cell(raw.cells.c0, raw.cells.r0, raw.cells.c1 - raw.cells.c0, raw.cells.r1 - raw.cells.r0),
                frac(raw.pts), if (wide) frac(raw.bleed) else null, inner,
            )
        }
    }

    /** The part of the convex polygon [poly] on one side ([sign]) of the line [p1]→[p2], at least [off] away from it. */
    private fun clip(poly: List<PanelPoint>, p1: PanelPoint, p2: PanelPoint, sign: Double, off: Double): List<PanelPoint> {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val len = hypot(dx, dy)
        fun f(p: PanelPoint) = sign * ((dx * (p.y - p1.y) - dy * (p.x - p1.x)) / len) - off
        val out = ArrayList<PanelPoint>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val fa = f(a)
            val fb = f(b)
            if (fa >= 0) out += a
            if ((fa >= 0) != (fb >= 0)) {
                val t = fa / (fa - fb)
                out += PanelPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return out
    }
}

/** The area of the polygon [pts], in the units its points are in. */
internal fun polygonArea(pts: List<PanelPoint>): Double =
    abs(pts.indices.sumOf { i -> val p = pts[i]; val q = pts[(i + 1) % pts.size]; p.x * q.y - q.x * p.y }) / 2
