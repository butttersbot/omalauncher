package dev.omalauncher.widgets.layout

/**
 * How a foldable is held, as Android names its device states. A phone that doesn't fold is [Closed] and a
 * tablet [Opened]: see [posture].
 */
enum class Posture { Closed, Tent, HalfOpened, Opened }

/** What the hinge reports, when the window has one across it. */
enum class Hinge { Flat, HalfOpened }

/** A window whose short side is at least this many dp is an unfolded one: the cover of a foldable, like any phone, is narrower. */
const val UNFOLDED_MIN_DP = 600f

/**
 * The posture, from what the launcher can see. The hinge is only reported while the window lies across it,
 * and it doesn't say which panel the window is on, so the window's size decides folded or unfolded and the
 * [hinge] only tells a half-opened device from a flat one.
 */
fun posture(
    widthDp: Float,
    heightDp: Float,
    hinge: Hinge?,
): Posture =
    when {
        minOf(widthDp, heightDp) < UNFOLDED_MIN_DP -> Posture.Closed
        hinge == Hinge.HalfOpened -> Posture.HalfOpened
        else -> Posture.Opened
    }

/**
 * What home shows of the notebook: [per] pages side by side in each of [slots] pager slots, and the [slot]
 * to be at. [plus] says the last slot's right half has no page, and offers to add one.
 */
data class FoldView(
    val per: Int,
    val slots: Int,
    val slot: Int,
    val plus: Boolean,
)

/**
 * The notebook's fold rule, for a window of [widthDp] by [heightDp]:
 *  - folded ([Posture.Closed], or a window whose short side is under [UNFOLDED_MIN_DP]): one page;
 *  - unfolded and flat and wider than tall: two fixed neighbours by position (1+2, 3+4, ...), and with an odd
 *    number of pages an empty right half after the last;
 *  - unfolded but taller than wide, or [Posture.Tent], or [Posture.HalfOpened]: one page.
 *
 * [lastTouched] is the position, from 0, of the page last touched: folding shows that page, unfolding the pair it is in.
 */
fun foldView(
    widthDp: Float,
    heightDp: Float,
    posture: Posture,
    lastTouched: Int,
    pageCount: Int,
): FoldView {
    val count = pageCount.coerceAtLeast(1)
    val touched = lastTouched.coerceIn(0, count - 1)
    val unfolded = posture != Posture.Closed && minOf(widthDp, heightDp) >= UNFOLDED_MIN_DP
    val spread = unfolded && posture == Posture.Opened && widthDp > heightDp
    return if (spread) FoldView(2, (count + 1) / 2, touched / 2, count % 2 == 1) else FoldView(1, count, touched, false)
}

/**
 * Whether the dock stands in a rail beside the page instead of under it: only in a phone's landscape (a folded
 * foldable's too), which is too short for it below. An unfolded window keeps the dock across the bottom.
 */
fun dockBeside(
    widthDp: Float,
    heightDp: Float,
) = widthDp > heightDp && heightDp < UNFOLDED_MIN_DP
