package dev.omalauncher.ui

import kotlin.math.abs
import kotlin.math.hypot

/** Where a touch on home came down. */
enum class TouchStart {
    /** Bare page: no tile there, the room under the grid, or the empty half of the last pair. */
    Page,

    /** An app or a folder, or an app in the open folder: it only takes taps. */
    Tile,

    /** A widget, whose content may take a drag for itself: a slider, a list, a terminal. */
    Widget,

    /** The tile being edited. */
    Selected,

    /** A resize handle of the tile being edited, or its frame. */
    Handle,

    /** The edit bar's buttons. */
    Bar,
}

/** What home is in the middle of when a touch comes down. */
data class HomeModes(
    /** A tile is selected: its handles and edit bar show. */
    val editing: Boolean = false,
    /** A folder's panel is open over the page. */
    val folderOpen: Boolean = false,
    /** One finger draws on the page. Nothing sets it yet: drawing comes with a later slice (PRD AC-22). */
    val drawing: Boolean = false,
)

/** Who a touch sequence on home belongs to. */
enum class Owner {
    /** Not known yet: a tap, a long press or a drag may still come of it. */
    Undecided,

    /** A sideways drag: the pager turns the page. */
    Pager,

    /** A drag that began on a widget: its content takes what it wants, and the pager what it leaves. */
    Widget,

    /** A drag upwards: the app list. */
    Drawer,

    /** A drag downwards, which home has no use for. */
    Nobody,

    /** A long press on the bare page: the home menu. */
    Menu,

    /** A tile lifted by a long press, or the selected one taken hold of: it is carried. */
    Arrange,

    /** A handle of the selected tile: it is resized. */
    Resize,

    /** A touch outside the tile being edited, or outside the open folder's apps: it ends that, and does nothing else. */
    Editor,

    /** The edit bar: its buttons take their own taps. */
    Buttons,

    /** One finger in drawing mode. */
    Draw,

    /** Two fingers moving apart or together. */
    Pinch,
}

/**
 * Decides who owns a touch sequence on home, from where it came down, what home was doing, how many fingers
 * are down and how they moved. Home's handlers ask it instead of each deciding for itself, so two of them
 * never act on the same touch.
 *
 * One finger follows the rules home always had: sideways turns the page, a long press lifts a tile or opens
 * the menu, the selected tile is carried or resized, and a widget's own drag is the widget's. Two fingers
 * moving apart or together are the pinch from wherever they came down, a widget that drags or a page being
 * drawn on included; only a tile already being carried or resized keeps the touch, since letting go of it
 * for a second finger would drop it somewhere he didn't choose.
 */
class GestureArbiter {
    var owner = Owner.Undecided
        private set

    private var start = TouchStart.Page
    private var pointers = 0

    /** More than one finger has been down since the first: no long press comes of this sequence, nor a one-finger drag. */
    private var several = false

    /** The owner can't change any more: a pinch under way, or a tile that has moved under the finger. */
    private var held = false

    /** The first finger came down on [start] while home was in [modes]. */
    fun down(
        start: TouchStart,
        modes: HomeModes,
    ): Owner {
        this.start = start
        pointers = 1
        several = false
        held = false
        owner =
            when {
                modes.drawing -> Owner.Draw
                modes.folderOpen -> if (start == TouchStart.Tile) Owner.Undecided else Owner.Editor
                modes.editing ->
                    when (start) {
                        TouchStart.Bar -> Owner.Buttons
                        TouchStart.Handle -> Owner.Resize
                        TouchStart.Selected -> Owner.Arrange
                        else -> Owner.Editor
                    }
                else -> Owner.Undecided
            }
        return owner
    }

    /** [count] fingers are down now. */
    fun pointers(count: Int): Owner {
        pointers = count
        if (count > 1) several = true
        return owner
    }

    /** The first finger has rested where it came down for as long as a long press takes. */
    fun longPress(): Owner {
        if (owner == Owner.Undecided && !several && pointers == 1) owner = if (start == TouchStart.Page) Owner.Menu else Owner.Arrange
        return owner
    }

    /**
     * The fingers moved: the first by [dx], [dy] since it came down, and with two down the distance between
     * them by [spread] since the second landed. [slop] is how far a finger may wander before it counts.
     */
    fun moved(
        dx: Float,
        dy: Float,
        spread: Float,
        slop: Float,
    ): Owner {
        if (pointers >= 2) {
            if (!held && abs(spread) > slop) {
                owner = Owner.Pinch
                held = true
            }
            return owner
        }
        if (pointers != 1 || hypot(dx, dy) <= slop) return owner
        when (owner) {
            Owner.Undecided ->
                if (!several) {
                    owner =
                        when {
                            start == TouchStart.Widget -> Owner.Widget
                            abs(dx) > abs(dy) -> Owner.Pager
                            dy < 0 -> Owner.Drawer
                            else -> Owner.Nobody
                        }
                }
            Owner.Arrange, Owner.Resize -> held = true
            else -> {}
        }
        return owner
    }

    /** Every finger is up. */
    fun up() {
        owner = Owner.Undecided
        pointers = 0
        several = false
        held = false
    }
}

/** A pinch released at this scale or less settles into the overview of all pages (PRD AC-13). */
const val OVERVIEW_AT = 0.82f

/** A pinch outwards on a page of the overview, released at this scale or more, opens that page. */
const val OPEN_AT = 1.18f

/** How far the page shrinks under a pinch, and how far it stretches the other way before it stops following. */
private const val PINCH_MIN = 0.5f
private const val PINCH_MAX = 1.08f

/** The distance between two fingers now over what it was when the second landed; 1 where that can't be said. */
fun spanRatio(
    startSpan: Float,
    span: Float,
): Float = if (startSpan > 0f && span >= 0f && span.isFinite()) span / startSpan else 1f

/** The scale a pinch on home has reached: [spanRatio], within what the page follows. */
fun pinchScale(
    startSpan: Float,
    span: Float,
): Float = spanRatio(startSpan, span).coerceIn(PINCH_MIN, PINCH_MAX)

/** Whether a pinch on home let go at [scale] settles into the overview; otherwise the page springs back. */
fun settlesIntoOverview(scale: Float) = scale <= OVERVIEW_AT

/** Whether a pinch on a page in the overview let go at [scale] opens that page; otherwise the overview stays. */
fun opensFromOverview(scale: Float) = scale >= OPEN_AT
