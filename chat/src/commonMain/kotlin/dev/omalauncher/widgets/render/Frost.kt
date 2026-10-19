package dev.omalauncher.widgets.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset as PixelOffset
import dev.omalauncher.widgets.runtime.decodeWidgetImageFile
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.omalauncher.widgets.runtime.widgetLog
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a frosted node blurs: the launcher's background, and how far it has slid with the pages, or a [picture]
 * of it where the launcher draws its own (a look's page). All are read while composing or drawing, so a node
 * redraws as the pages turn and makes its blur again when the picture changes. Null where nothing of it is
 * behind, as in an app's window or a preview.
 */
class Backdrop(
    val file: () -> File? = { null },
    val offset: () -> Float = { 0.5f },
    val picture: () -> BackdropPicture? = { null },
)

/**
 * What is behind home when the launcher draws it itself, drawn into a window-sized scope. It covers the
 * window as it is, so it doesn't slide with the pages. [key] is equal for equal pictures: the blur is made
 * again only when it changes.
 */
class BackdropPicture(
    val key: Any,
    val draw: DrawScope.() -> Unit,
)

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The background small and blurred, one per background, window size and radius, shared by every frosted node. */
private object FrostCache {
    /** Blurred at an eighth of the screen: cheap to make and to draw, and the blur hides the scale. */
    const val SCALE = 8

    /** Drawn this many times larger than the small image first, then averaged down: one step from full size would skip pixels. */
    private const val OVER = 4

    private val made = HashMap<Pair<Any, Int>, CompletableDeferred<ImageBitmap?>>()

    /** The blur of [file] or [picture] (whichever isn't null) for a [window]-sized screen. */
    suspend fun get(
        file: File?,
        picture: BackdropPicture?,
        window: IntSize,
        density: Float,
        radius: Int,
    ): ImageBitmap? {
        val source: Any = picture?.key ?: "${file?.path}:${file?.lastModified()}"
        val key = listOf(source, window) to radius
        val (job, mine) =
            synchronized(made) {
                made[key]?.let { it to false } ?: run {
                    // One background at a time: the old ones go when it changes.
                    made.keys.removeAll { it.first != key.first }
                    CompletableDeferred<ImageBitmap?>().also { made[key] = it } to true
                }
            }
        if (mine) {
            try {
                job.complete(
                    withContext(Dispatchers.Default) {
                        runCatching {
                            when {
                                picture != null -> make(picture, window, density, radius)
                                file != null -> make(file, window, radius)
                                else -> null
                            }
                        }.onFailure { widgetLog("frost: $it") }.getOrNull()
                    },
                )
            } finally {
                // Cancelled mid-way (its node left the screen): the next one to ask makes it again, rather than waiting forever.
                if (!job.isCompleted) {
                    synchronized(made) { if (made[key] === job) made.remove(key) }
                    job.complete(null)
                }
            }
        }
        return job.await()
    }

    /** [picture] drawn at half the window's size, then made an eighth of it and blurred. */
    private fun make(
        picture: BackdropPicture,
        window: IntSize,
        density: Float,
        radius: Int,
    ): ImageBitmap {
        val w = max(1, window.width / SCALE)
        val h = max(1, window.height / SCALE)
        val big = ImageBitmap(w * OVER, h * OVER)
        val sx = big.width.toFloat() / window.width
        val sy = big.height.toFloat() / window.height
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(big), Size(window.width.toFloat(), window.height.toFloat())) {
            scale(sx, sy, Offset.Zero) { picture.draw(this) }
        }
        return blur(shrink(big, OVER), w, h, radius)
    }

    /** [file], covering the screen as wallpaperCrop places it, at an eighth of the screen's size, and blurred. */
    private fun make(
        file: File,
        window: IntSize,
        radius: Int,
    ): ImageBitmap {
        // Decoded no smaller than it is drawn larger below, so the eighth is made from real pixels.
        val need = max(1, min(window.width, window.height) / SCALE) * OVER
        val full = decodeWidgetImageFile(file, need) ?: error("can't decode $file")
        // Small enough that covering the screen takes it SCALE times larger.
        val cover = max(window.width.toFloat() / full.width, window.height.toFloat() / full.height) / SCALE
        val w = max(1, (full.width * cover).roundToInt())
        val h = max(1, (full.height * cover).roundToInt())
        val big = ImageBitmap(w * OVER, h * OVER)
        Canvas(big).drawImageRect(full, dstSize = IntSize(big.width, big.height), dstOffset = PixelOffset.Zero, paint = Paint().apply { filterQuality = FilterQuality.Medium })
        return blur(shrink(big, OVER), w, h, radius)
    }

    /** [image]'s pixels averaged in [f] by [f] squares. */
    private fun shrink(
        image: ImageBitmap,
        f: Int,
    ): IntArray {
        val bw = image.width
        val w = bw / f
        val h = image.height / f
        val src = IntArray(bw * image.height)
        image.readPixels(src, 0, 0, bw, image.height)
        val out = IntArray(w * h)
        val n = f * f
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until f) {
                    val row = (y * f + dy) * bw + x * f
                    for (dx in 0 until f) {
                        val c = src[row + dx]
                        a += c ushr 24
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    private fun blur(
        px: IntArray,
        w: Int,
        h: Int,
        radius: Int,
    ): ImageBitmap {
        // Three box blurs come close to a gaussian.
        val tmp = IntArray(w * h)
        repeat(3) {
            box(px, tmp, w, h, radius, 1, w)
            box(tmp, px, h, w, radius, w, 1)
        }
        return imageFromPixels(px, w, h)
    }

    /**
     * One pass of a box blur along lines of [len] pixels, [step] apart within a line and [stride] between
     * lines, from [src] into [dst]. A running sum per channel, with the edge pixels repeated past the ends.
     */
    private fun box(
        src: IntArray,
        dst: IntArray,
        len: Int,
        lines: Int,
        r: Int,
        step: Int,
        stride: Int,
    ) {
        val n = 2 * r + 1
        for (line in 0 until lines) {
            val base = line * stride
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                a += c ushr 24
                red += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
                val out = src[base + (i - r).coerceIn(0, len - 1) * step]
                val inn = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    fun radius(
        dp: Double,
        density: Float,
    ) = (dp * density / SCALE).roundToInt().coerceIn(1, 24)
}

/**
 * `frost` (owl 10): the background behind the node, blurred by that many dp, under the node's own
 * background, so `background: alpha(surface, 0.4)` tints it like frosted glass. It blurs the wallpaper or the
 * look's page, not other nodes, and shows nothing where the launcher draws nothing of its own behind.
 */
@Composable
internal fun Modifier.frost(
    p: Props,
    corner: () -> Float,
): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    val dp = p.observe("frost")?.num()?.takeIf { it > 0 } ?: return this
    val picture = backdrop.picture()
    val file = if (picture == null) backdrop.file() ?: return this else null
    // The screen it's blurred for is the root the node draws in, as the launcher's background fills it; known once placed.
    var root by remember { mutableStateOf(IntSize.Zero) }
    val placed = onGloballyPositioned { root = it.findRootCoordinates().size }
    val window = root
    if (window.width <= 0 || window.height <= 0) return placed
    val density = LocalDensity.current.density
    val radius = FrostCache.radius(dp, density)
    val key = picture?.key ?: file
    val image by produceState<ImageBitmap?>(null, key, window, radius) { value = FrostCache.get(file, picture, window, density, radius) }
    // A picture covers the window as it is; a file slides with the pages.
    val offset = if (picture != null) Centered else backdrop.offset
    return placed.then(FrostElement(image ?: return placed, offset, corner))
}

private val Centered = { 0.5f }

private data class FrostElement(
    val image: ImageBitmap,
    val offset: () -> Float,
    val corner: () -> Float,
) : ModifierNodeElement<FrostNode>() {
    override fun create() = FrostNode(image, offset, corner)

    override fun update(node: FrostNode) {
        node.image = image
        node.offset = offset
        node.corner = corner
        node.invalidateDraw()
    }
}

private class FrostNode(
    var image: ImageBitmap,
    var offset: () -> Float,
    var corner: () -> Float,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private val clip = Path()

    // Scrolling or a page sliding moves the node over the background: draw the part now behind it.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = invalidateDraw()

    override fun ContentDrawScope.draw() {
        val coords = requireLayoutCoordinates()
        val pos = coords.positionInRoot()
        val screen = coords.findRootCoordinates().size
        val img = image
        // As wallpaperCrop places the background on the screen, in floats: the small image would otherwise slide in steps.
        val bw = img.width.toFloat()
        val bh = img.height.toFloat()
        val scale = max(screen.width / bw, screen.height / bh)
        val srcW = screen.width / scale
        val srcH = screen.height / scale
        val travel = min(bw - srcW, srcW / 2)
        val left = (bw - srcW - travel) / 2 + travel * offset().coerceIn(0f, 1f)
        val top = (bh - srcH) / 2
        val r = corner().dp.toPx()
        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        clipPath(clip) {
            drawImage(
                img,
                dstOffset = IntOffset((-left * scale - pos.x).roundToInt(), (-top * scale - pos.y).roundToInt()),
                dstSize = IntSize((bw * scale).roundToInt(), (bh * scale).roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        drawContent()
    }
}

/** ARGB pixels, [w] by [h], as an image. */
internal expect fun imageFromPixels(
    px: IntArray,
    w: Int,
    h: Int,
): ImageBitmap
