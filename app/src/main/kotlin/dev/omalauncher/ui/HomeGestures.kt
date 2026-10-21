package dev.omalauncher.ui

import kotlin.math.abs
import kotlin.math.hypot

/** Where a touch on home came down. */
enum class TouchStart {
    /** Bare page: no tile there, the room under the grid, or the empty half of the last pair. */
    Page,

    /** An app or a folder, or an app in the open folder: it only takes taps. */
    Tile,

    /**
     * A widget, whose content takes a sideways drag for itself (a slider); straight up and down are home's, the app
     * list and the search, as from the page (Boss, 2026-10-20: "the swipe up and the swipe down... even when I'm on
     * a widget").
     */
    Widget,

    /** A widget that scrolls up and down itself, a terminal: it keeps every drag. */
    ScrollingWidget,

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
    /** One finger draws on the page (PRD AC-22). */
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

    /** A drag upwards from the bare page, an app or a folder: the app list comes up under the finger (PRD AC-1). */
    Drawer,

    /** A drag downwards from the bare page, an app or a folder: Soma's search comes down (Boss, 2026-10-20). */
    Search,

    /** A slanted drag downwards, or upwards over an open folder, which home has no use for. */
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

    /** A sticky note or a sticker lifted by a long press, or the selected one taken hold of: it is carried over the page. */
    Note,

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
 * for a second finger would drop it somewhere he didn't choose, and the app list once it is coming up.
 *
 * A sticky note or a sticker is a tile that floats: a tap is its own, a drag across it turns the page or
 * opens the app list as from any app, and only a long press lifts it. Lifted, it is the [Owner.Note]'s, which
 * carries it over the page and into no cell.
 *
 * A drag upwards opens the app list where nothing else wants it: from the bare page, an app or a folder, with
 * no tile being edited, no folder open and no drawing. A widget keeps its own drags, upwards too (it may be a
 * list, or a terminal). The list is harder to claim than a page turn or a tap: the finger has to go upwards
 * at least [DRAWER_STEEP] times as far as sideways, and [DRAWER_SLOPS] times the slop, before it is the list's.
 * A slanted swipe is the pager's, and a tap that drifts a little is nobody's and still lands.
 */
class GestureArbiter {
    var owner = Owner.Undecided
        private set

    private var start = TouchStart.Page
    private var folder = false

    /** The touch came down on a note or a sticker. */
    private var note = false
    private var pointers = 0

    /** More than one finger has been down since the first: no long press comes of this sequence, nor a one-finger drag. */
    private var several = false

    /** The first finger has left the slop without anyone taking the touch yet: resting now is no long press. */
    private var wandered = false

    /** The owner can't change any more: a pinch under way, or a tile that has moved under the finger. */
    private var held = false

    /**
     * The first finger came down on [start] while home was in [modes]. With [note] what it came down on is a
     * sticky note or a sticker, or one's handle or bar while it is the thing being edited, rather than a tile.
     */
    fun down(
        start: TouchStart,
        modes: HomeModes,
        note: Boolean = false,
    ): Owner {
        this.start = start
        this.note = note
        folder = modes.folderOpen
        pointers = 1
        several = false
        wandered = false
        held = false
        owner =
            when {
                modes.drawing -> Owner.Draw
                modes.folderOpen -> if (start == TouchStart.Tile) Owner.Undecided else Owner.Editor
                modes.editing ->
                    when (start) {
                        TouchStart.Bar -> Owner.Buttons
                        TouchStart.Handle -> Owner.Resize
                        TouchStart.Selected -> if (note) Owner.Note else Owner.Arrange
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
        if (owner == Owner.Undecided && !several && !wandered && pointers == 1) {
            owner =
                when {
                    note -> Owner.Note
                    start == TouchStart.Page -> Owner.Menu
                    else -> Owner.Arrange
                }
        }
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
                    wandered = true
                    val home = start == TouchStart.Page || start == TouchStart.Tile || start == TouchStart.Widget
                    val upwards = dy < 0 && !folder && home
                    // Downwards from the same places, as steeply and as far, brings the search down: the list's mirror.
                    val downwards = dy > 0 && !folder && home
                    owner =
                        when {
                            start == TouchStart.ScrollingWidget -> Owner.Widget
                            // Clearly upwards: the app list's, but only once it has gone far enough to be meant. Until
                            // then nothing is decided, and a tap whose finger drifted up is still a tap.
                            upwards && -dy >= DRAWER_STEEP * abs(dx) -> if (hypot(dx, dy) > DRAWER_SLOPS * slop) Owner.Drawer else Owner.Undecided
                            downwards && dy >= DRAWER_STEEP * abs(dx) -> if (hypot(dx, dy) > DRAWER_SLOPS * slop) Owner.Search else Owner.Undecided
                            // Any other drag on a widget is its own: a slider's, or the pager's through it.
                            start == TouchStart.Widget -> Owner.Widget
                            // Sideways, or upwards at a slant a thumb's arc has: the pager's.
                            upwards || abs(dx) > abs(dy) -> Owner.Pager
                            else -> Owner.Nobody
                        }
                    // The app list is under the finger from here on: a second finger doesn't let go of it half open.
                    if (owner == Owner.Drawer) held = true
                }
            Owner.Arrange, Owner.Resize, Owner.Note -> held = true
            else -> {}
        }
        return owner
    }

    /** Every finger is up. */
    fun up() {
        owner = Owner.Undecided
        note = false
        pointers = 0
        several = false
        wandered = false
        held = false
    }
}

/** How many times as far upwards as sideways a drag goes before it is the app list's rather than the pager's. */
const val DRAWER_STEEP = 1.5f

/** How many times the touch slop a drag upwards travels before it is the app list's: a tap's drift is shorter. */
const val DRAWER_SLOPS = 2f

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
