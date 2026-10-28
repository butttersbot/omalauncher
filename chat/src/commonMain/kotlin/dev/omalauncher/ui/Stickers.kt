package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as Outline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.annotations.LibrarySticker
import dev.omalauncher.widgets.annotations.Picture
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerBitmaps
import dev.omalauncher.widgets.annotations.StickerChoice
import dev.omalauncher.widgets.annotations.StickerKind
import dev.omalauncher.widgets.annotations.StickerLibrary
import dev.omalauncher.widgets.annotations.StickerShelf
import dev.omalauncher.widgets.annotations.pictureSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The built-in stickers, each drawn here from a few lines and curves: no image is bundled for them and
 * nothing is fetched. They are in the pack's accent, with a hard shadow in its surface color so they stand
 * off any page. After them, the stickers that are pictures of the owner's own: drawn from the launcher's copy
 * of the image, in the image's colors, which no pack changes.
 *
 * Shared by the phone's home and Soma desktop's dashboard (desktop slice 5b, 2026-10-28): each hands over its own
 * shelf of pictures by [LocalStickerShelf].
 */

/**
 * The stickers' shelf where they are drawn: the library to pick from, the copies stuck on pages and their pixels.
 * The phone's home gives its HomeStickers; the dashboard window its own under Soma's folder. Without one, a picture
 * sticker shows as lost and the picker offers the built-in stickers alone.
 */
val LocalStickerShelf = staticCompositionLocalOf<StickerShelf?> { null }

/** A point [radius] from the middle of a box [side] across, at [degrees] clockwise from straight up. */
private fun around(
    side: Float,
    radius: Float,
    degrees: Float,
): Offset {
    val angle = (degrees - 90f) * PI.toFloat() / 180f
    return Offset(side / 2f + side * radius * cos(angle), side / 2f + side * radius * sin(angle))
}

private fun polygon(points: List<Offset>) =
    Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

/** One sticker in a box [side] across at the scope's origin, all of it in the one color it is given. */
private fun DrawScope.shape(
    kind: StickerKind,
    side: Float,
    color: Color,
) {
    val line = Outline(side * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        StickerKind.Star -> drawPath(polygon((0 until 10).map { around(side, if (it % 2 == 0) 0.48f else 0.2f, it * 36f) }), color)
        StickerKind.Heart ->
            drawPath(
                Path().apply {
                    moveTo(side * 0.5f, side * 0.92f)
                    cubicTo(side * -0.08f, side * 0.52f, side * 0.1f, side * 0.02f, side * 0.5f, side * 0.3f)
                    cubicTo(side * 0.9f, side * 0.02f, side * 1.08f, side * 0.52f, side * 0.5f, side * 0.92f)
                    close()
                },
                color,
            )
        StickerKind.Flower -> {
            for (petal in 0 until 5) drawCircle(color, side * 0.19f, around(side, 0.27f, petal * 72f))
            drawCircle(color, side * 0.16f, Offset(side / 2f, side / 2f))
        }
        StickerKind.Check ->
            drawPath(
                Path().apply {
                    moveTo(side * 0.14f, side * 0.55f)
                    lineTo(side * 0.4f, side * 0.8f)
                    lineTo(side * 0.86f, side * 0.2f)
                },
                color,
                style = Outline(side * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        StickerKind.Bolt ->
            drawPath(
                polygon(listOf(0.62f to 0.02f, 0.18f to 0.56f, 0.46f to 0.56f, 0.36f to 0.98f, 0.84f to 0.4f, 0.54f to 0.4f).map { Offset(side * it.first, side * it.second) }),
                color,
            )
        StickerKind.Smile -> {
            drawCircle(color, side * 0.42f, Offset(side / 2f, side / 2f), style = line)
            drawCircle(color, side * 0.065f, Offset(side * 0.36f, side * 0.4f))
            drawCircle(color, side * 0.065f, Offset(side * 0.64f, side * 0.4f))
            drawArc(color, 25f, 130f, false, topLeft = Offset(side * 0.28f, side * 0.28f), size = Size(side * 0.44f, side * 0.44f), style = line)
        }
    }
}

/** [kind] filling the scope: its shadow first, a little down and to the right, then itself. */
private fun DrawScope.drawSticker(kind: StickerKind) {
    val side = minOf(size.width, size.height) * 0.92f
    val shift = side * 0.045f
    translate(shift, shift) { shape(kind, side, Pack.surface) }
    shape(kind, side, Pack.accent)
}

/** A sticker by itself, for where one is chosen. */
@Composable
fun StickerGlyph(
    kind: StickerKind,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) { drawSticker(kind) }
}

/**
 * One sticker on its page at [rect], turned as it was left. Only what is drawn turns: it is touched, carried
 * and resized by its upright box, like a tilted scrap. A tap selects it.
 */
@Composable
fun StickerScrap(
    sticker: Sticker,
    rect: Rect,
    /** How it eases in or out, if it does: its own layer, after its place and size. */
    motion: Modifier = Modifier,
    /** Null for one that is on its way off the page: it is only drawn. */
    onTap: (() -> Unit)?,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .zIndex(1f)
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(motion)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = sticker.turn.toFloat() }) {
            val picture = sticker.picture
            if (picture == null) Canvas(Modifier.fillMaxSize()) { drawSticker(sticker.kind) } else PictureFace(picture, maxOf(rect.width, rect.height).roundToInt())
        }
    }
}

/** What there is to draw of a picture. */
private sealed interface Seen {
    /** Not decoded yet: nothing is drawn for the moment that takes. */
    data object Waiting : Seen

    class Drawn(
        val image: ImageBitmap,
    ) : Seen

    /** Its file is gone or is no picture. */
    data object Lost : Seen
}

/**
 * The picture kept by [key], decoded from [file] off the main thread for being drawn [pixels] across. While a
 * bigger one is decoded for a sticker that has grown, the one there was stays.
 */
@Composable
private fun pictureSeen(
    bitmaps: StickerBitmaps,
    key: String,
    pixels: Int,
    file: () -> File,
): Seen {
    val side = pictureSide(pixels)
    val seen by produceState<Seen>(bitmaps.kept(key, side)?.let { Seen.Drawn(it) } ?: Seen.Waiting, key, side) {
        value = withContext(Dispatchers.IO) { runCatching { bitmaps.load(key, file(), side) }.getOrNull() }?.let { Seen.Drawn(it) } ?: Seen.Lost
    }
    return seen
}

/**
 * A picture sticker filling its box, from the launcher's copy of the image. If the copy is gone or can't be
 * decoded it says so: a frame with the picture's name in it, where showing nothing would look like no sticker.
 */
@Composable
private fun PictureFace(
    picture: Picture,
    pixels: Int,
) {
    val shelf = LocalStickerShelf.current
    val seen = if (shelf == null) Seen.Lost else pictureSeen(shelf.bitmaps, picture.file, pixels) { shelf.snapshots.file(picture.file) }
    when (seen) {
        is Seen.Drawn -> Image(seen.image, picture.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Seen.Lost ->
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val width = Pack.mark.toPx()
                        drawRect(Pack.pageMuted, topLeft = Offset(width / 2f, width / 2f), size = Size(size.width - width, size.height - width), style = Outline(width))
                    }.padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(picture.name, style = Pack.mono(Pack.size.small, Pack.pageMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        Seen.Waiting -> {}
    }
}

/** How many of the library's pictures stand in a row of the picker, how tall a row is, and how tall the picker gets before it scrolls. */
private const val PICKER_COLUMNS = 5
private val PICKER_ROW = 56.dp
private val PICKER_HEIGHT = 320.dp

/**
 * The stickers there are, to pick one from: the built-in ones in a row, then the pictures in the owner's
 * library, those in a folder under the folder's name. With many pictures it scrolls inside the menu it is in,
 * which stays on a narrow screen. [onPick] is told where in the window the one picked was.
 */
@Composable
fun StickerPicker(onPick: (StickerChoice, Offset?) -> Unit) {
    val shelf = LocalStickerShelf.current
    // Null until the library has been looked at, and for good where there is none: without Linux, which it is in.
    val found by produceState<StickerLibrary?>(null, shelf) { value = shelf?.let { withContext(Dispatchers.IO) { it.look() } } }
    val library = found
    val places = remember { HashMap<StickerKind, Offset>() }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = PICKER_HEIGHT).padding(horizontal = 8.dp, vertical = 6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (kind in StickerKind.entries) {
                    Box(
                        Modifier
                            .weight(1f)
                            .onGloballyPositioned { places[kind] = it.boundsInWindow().center }
                            .clickable { onPick(StickerChoice.BuiltIn(kind), places[kind]) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        StickerGlyph(kind, Modifier.size(36.dp))
                    }
                }
            }
        }
        if (library == null || shelf == null) return@LazyColumn
        if (library.groups.isEmpty()) {
            // Where his own go, said once, while there are none.
            item { BasicText("pictures in ${shelf.shownAs} show here", Modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted)) }
        }
        for (group in library.groups) {
            if (group.name.isNotEmpty()) {
                item { BasicText(group.name, Modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            items(group.stickers.chunked(PICKER_COLUMNS)) { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (sticker in row) {
                        PictureKey(library, sticker, shelf.bitmaps, Modifier.weight(1f)) { from -> onPick(StickerChoice.Pictured(library.file(sticker), sticker.name), from) }
                    }
                    // A short last row keeps its pictures the size of the others.
                    repeat(PICKER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One of the library's pictures to pick. One that can't be decoded after all is left out: its place is empty and takes no tap. */
@Composable
private fun PictureKey(
    library: StickerLibrary,
    sticker: LibrarySticker,
    bitmaps: StickerBitmaps,
    modifier: Modifier,
    onPick: (Offset?) -> Unit,
) {
    val place = remember { arrayOfNulls<Offset>(1) }
    val seen = pictureSeen(bitmaps, library.key(sticker), with(LocalDensity.current) { PICKER_ROW.roundToPx() }) { library.file(sticker) }
    Box(
        modifier
            .height(PICKER_ROW)
            .onGloballyPositioned { place[0] = it.boundsInWindow().center }
            .then(if (seen is Seen.Drawn) Modifier.clickable { onPick(place[0]) } else Modifier)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (seen is Seen.Drawn) Image(seen.image, sticker.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}
