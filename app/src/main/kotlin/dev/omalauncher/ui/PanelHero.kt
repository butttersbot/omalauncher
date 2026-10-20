package dev.omalauncher.ui

import dev.omalauncher.theme.pack.PackPanels
import dev.omalauncher.widgets.ArtItem
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.layout.Panel
import dev.omalauncher.widgets.panelsOf
import dev.omalauncher.widgets.style
import kotlin.math.abs
import kotlin.math.floor

/*
 * A manga page's hero (the Manga look): art in a panel as wide as the page at its top, which runs on up past the page's
 * name and under the status bar to the top of the screen, as the mockup's opening scenery does. Nothing else moves for it:
 * the page's cells and ink stay where they are, and only the picture's top corners go higher. Over it the page's name is
 * lettered light and the status bar's icons are light.
 */

/**
 * Which of [panels] is the page's hero: the top row's panel as wide as the page (its [Panel.bleed] reaches the page's top
 * edge) while it holds art ([held], one occupant per panel) that isn't being carried ([carried]). Null when there is none.
 */
internal fun heroPanel(
    panels: List<Panel>,
    held: List<HomeItem?>,
    carried: String?,
): Int? =
    panels.indices.firstOrNull { i ->
        val panel = panels[i]
        val item = held.getOrNull(i)
        item is ArtItem && item.key != carried && panel.cell.y == 0 && panel.bleed?.any { abs(it.y) < 1e-6 } == true
    }

/** Whether [page] (its panel [layout], null for the free grid) has a hero in the look's [look] panels, as [heroPanel]. */
internal fun hasHero(
    layout: String?,
    rows: Int,
    look: PackPanels?,
    items: List<HomeItem>,
    cellOf: (String) -> Cell?,
    carried: String?,
): Boolean {
    val panels = panelsOf(layout, dev.omalauncher.widgets.GRID_COLUMNS, rows, (look ?: return false).style()) ?: return false
    return heroPanel(panels, occupants(panels, items, cellOf), carried) != null
}

/**
 * Whether a hero is under the point [x] across the screen (0 its left edge, 1 its right), with the pager at [position]
 * (its current slot plus how far it is turned from it) and [per] pages to a slot: [hero] says whether the page at a side
 * (0 the left one) of a slot has one. A turn slides the hero off from under the point; until it has gone, it is still there.
 */
internal fun heroUnder(
    x: Float,
    position: Float,
    per: Int,
    hero: (slot: Int, side: Int) -> Boolean,
): Boolean {
    val at = position + x.coerceIn(0f, 0.9999f)
    val slot = floor(at).toInt()
    if (slot < 0) return false
    val side = ((at - slot) * per.coerceAtLeast(1)).toInt().coerceIn(0, per.coerceAtLeast(1) - 1)
    return hero(slot, side)
}

/**
 * Whether heroes run under the whole status bar at pager [slot]: every one of its [per] sides has a page with a hero
 * ([hero]). On a spread with one hero the bar's other end is over paper, where light icons would vanish.
 */
internal fun heroAcross(
    slot: Int,
    per: Int,
    hero: (slot: Int, side: Int) -> Boolean,
): Boolean = (0 until per.coerceAtLeast(1)).all { hero(slot, it) }

/**
 * The status bar's clock and icons over home: dark where what is behind them is light ([onLight], from the look), but light
 * while a hero is under them ([hero]) and nothing covers home's top: not the app list most of the way up ([drawer]), nor a
 * full-screen panel ([panel]).
 */
internal fun statusBarOnLight(
    onLight: Boolean,
    hero: Boolean,
    drawer: Boolean,
    panel: Boolean,
): Boolean = if (hero && !drawer && !panel) false else onLight
