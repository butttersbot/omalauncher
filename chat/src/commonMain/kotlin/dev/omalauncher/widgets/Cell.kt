package dev.omalauncher.widgets

/*
 * The grid's unit, shared by every screen's canvas (Soma Canvas, cockpit/canvas/PRD.md): the Fold's home pages, Soma
 * desktop's canvas and the car's. Moved here from the launcher's WidgetLayout.kt unchanged.
 */

/** A widget's place on its page's grid, in cells. */
data class Cell(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    val right get() = x + w
    val bottom get() = y + h

    fun overlaps(o: Cell) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
}

/** Columns of the home grid. Saved with the layout, so changing it rescales saved cells rather than scrambling them. */
const val GRID_COLUMNS = 6
