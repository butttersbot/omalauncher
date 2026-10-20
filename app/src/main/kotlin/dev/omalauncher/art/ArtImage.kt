package dev.omalauncher.art

import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Art [id] from the [ArtStore], cropped to fill this composable's bounds (centered, like CSS `object-fit: cover`).
 * A GIF or animated WebP plays, looping, only while it is on screen and the app is started (the screen off stops the
 * activity, and so the animation). It is decoded at the size it is drawn, never larger than the file. With [ink] it is
 * printed like the mockup's "art in ink": grayscale, more contrast, and screentone dots over it. Missing art draws nothing.
 */
@Composable
fun ArtImage(
    id: String,
    modifier: Modifier = Modifier,
    ink: Boolean = false,
) {
    val context = LocalContext.current
    val store = remember(context) { ArtStore(context.applicationContext) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf(false) }
    var art by remember(id) { mutableStateOf<Decoded?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)

    var decodedFor by remember(id) { mutableStateOf(IntSize.Zero) }
    // Only art on the screen is held decoded (Boss, 2026-10-20: GIFs only where they're seen): off it, the frames are let
    // go after a moment, so a quick swipe back doesn't decode again, and decoded anew when it shows.
    LaunchedEffect(id, size, shown) {
        if (!shown) {
            if (art != null) {
                delay(RELEASE_MS)
                (art?.drawable as? AnimatedImageDrawable)?.stop()
                art = null
                decodedFor = IntSize.Zero
            }
            return@LaunchedEffect
        }
        if (size.width <= 0 || size.height <= 0 || (art != null && decodedFor == size)) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) { store.file(id) } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { decode(file, size) }?.let { art = it; decodedFor = size }
    }

    // A frame of an animation asks to be drawn again through the callback; the drawable only holds it weakly.
    val callback =
        remember {
            val handler = Handler(Looper.getMainLooper())
            object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    frame++
                }

                override fun scheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                    `when`: Long,
                ) {
                    handler.postAtTime(what, who, `when`)
                }

                override fun unscheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                ) {
                    handler.removeCallbacks(what, who)
                }
            }
        }
    DisposableEffect(art) {
        val drawable = art?.drawable
        drawable?.callback = callback
        onDispose {
            (drawable as? AnimatedImageDrawable)?.stop()
            drawable?.callback = null
        }
    }
    val playing = shown && started
    LaunchedEffect(art, playing) {
        val animated = art?.drawable as? AnimatedImageDrawable ?: return@LaunchedEffect
        if (playing) animated.start() else animated.stop()
    }
    val inkFilter = remember { ColorMatrixColorFilter(ArtInk.MATRIX) }

    val density = LocalDensity.current
    val tone = remember(density.density) { ShaderBrush(ImageShader(screentone(with(density) { 3.dp.toPx() }, with(density) { 0.8.dp.toPx() }), TileMode.Repeated, TileMode.Repeated)) }

    Spacer(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .onGloballyPositioned { shown = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } }
            .drawBehind {
                val decoded = art ?: return@drawBehind
                frame // Read so that each new frame of an animation draws again.
                val box = this.size
                val scale = max(box.width / decoded.width, box.height / decoded.height)
                val w = decoded.width * scale
                val h = decoded.height * scale
                val left = ((box.width - w) / 2).roundToInt()
                val top = ((box.height - h) / 2).roundToInt()
                decoded.drawable.setBounds(left, top, left + w.roundToInt(), top + h.roundToInt())
                // Set only on a change: setting it asks for another frame.
                if (decoded.ink != ink) {
                    decoded.ink = ink
                    decoded.drawable.colorFilter = if (ink) inkFilter else null
                }
                clipRect {
                    drawIntoCanvas { decoded.drawable.draw(it.nativeCanvas) }
                    if (ink) drawRect(tone, alpha = ArtInk.TONE_ALPHA, blendMode = BlendMode.Multiply)
                }
            },
    )
}

/** A decoded picture and the size it was decoded at, in pixels. */
private class Decoded(
    val drawable: Drawable,
    val width: Float,
    val height: Float,
) {
    /** Whether the ink filter is set on [drawable]; null before the first draw. */
    var ink: Boolean? = null
}

/** [file] decoded just large enough to cover [box]; an animation loops forever. Null if it can't be read. */
private fun decode(
    file: File,
    box: IntSize,
): Decoded? =
    try {
        var w = 0
        var h = 0
        val drawable =
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (tw, th) = ArtInk.coverSize(info.size.width, info.size.height, box.width, box.height)
                w = tw
                h = th
                decoder.setTargetSize(tw, th)
            }
        (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        Decoded(drawable, w.toFloat(), h.toFloat())
    } catch (e: Throwable) {
        // An out-of-memory error included: a picture that can't be drawn must not take home down with it.
        null
    }

/** One screentone dot centered in a [step]-pixel tile, repeated over the art in ink. */
private fun screentone(
    step: Float,
    radius: Float,
): ImageBitmap {
    val side = max(2, step.roundToInt())
    val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(ArtInk.DOT_ALPHA, 0, 0, 0) }
    android.graphics.Canvas(bitmap).drawCircle(side / 2f, side / 2f, max(0.6f, radius), paint)
    return bitmap.asImageBitmap()
}

/** The numbers of the ink print, from the mockup's CSS: `grayscale(1) contrast(1.45) brightness(1.06)`, dots at .55. */
object ArtInk {
    const val CONTRAST = 1.45f
    const val BRIGHTNESS = 1.06f
    const val TONE_ALPHA = 0.55f
    const val DOT_ALPHA = 140 // 0.55 of 255

    /** Rec. 709 luma, as CSS grayscale() uses. */
    private const val R = 0.2126f
    private const val G = 0.7152f
    private const val B = 0.0722f

    /** The 4×5 color matrix of grayscale, then contrast around the middle, then brightness (channels 0..255). */
    val MATRIX: FloatArray =
        run {
            val k = BRIGHTNESS * CONTRAST
            val offset = BRIGHTNESS * 127.5f * (1 - CONTRAST)
            val gray = floatArrayOf(k * R, k * G, k * B, 0f, offset)
            gray + gray + gray + floatArrayOf(0f, 0f, 0f, 1f, 0f)
        }

    /**
     * The size to decode a [width]×[height] picture at so it covers a [boxW]×[boxH] box: scaled down until one side
     * just fills it, never scaled up, at least one pixel each way.
     */
    fun coverSize(
        width: Int,
        height: Int,
        boxW: Int,
        boxH: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val scale = min(1f, max(boxW.toFloat() / width, boxH.toFloat() / height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}

/** How long art off the screen stays decoded before its frames are let go. */
private const val RELEASE_MS = 4_000L
