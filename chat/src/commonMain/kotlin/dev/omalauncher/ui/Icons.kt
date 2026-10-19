package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.pack.IconTreatment
import dev.omalauncher.theme.pack.PackIcons
import dev.omalauncher.theme.pack.PlaceholderStyle
import kotlin.math.roundToInt

/** A 24dp Material glyph (Apache 2.0), filled white so it can be tinted where it's drawn. */
private fun icon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(MaskInk))
        .build()

/** The glyphs the launcher and Files use; tint with ColorFilter.tint. */
object Icons {
    val Gear by lazy {
        icon(
            "gear",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 " +
                "0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81" +
                "c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29" +
                "L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 " +
                "4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22" +
                "l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41" +
                "l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 " +
                "-0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    }
    val Folder by lazy {
        icon("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val File by lazy {
        icon("file", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z")
    }
    val TextFile by lazy {
        icon(
            "text",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2z" +
                "M16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
        )
    }
    val Image by lazy {
        icon(
            "image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }
    val Back by lazy { icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Refresh by lazy {
        icon(
            "refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08" +
                "c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        )
    }
    val More by lazy {
        icon(
            "more",
            "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 " +
                "-0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }
    val Close by lazy {
        icon(
            "close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    }
    val Check by lazy { icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    val Search by lazy {
        icon(
            "search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
                "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 " +
                "9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }
    val NewFolder by lazy {
        icon(
            "new-folder",
            "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2z" +
                "M19,14h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z",
        )
    }
    val Delete by lazy {
        icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }
    val Copy by lazy {
        icon(
            "copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
                "c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }
    val Cut by lazy {
        icon(
            "cut",
            "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 " +
                "1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 " +
                "-0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 " +
                "-2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5" +
                "s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7L22,3z",
        )
    }

    val Undo by lazy {
        icon(
            "undo",
            "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 " +
                "7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
        )
    }
    val Redo by lazy {
        icon(
            "redo",
            "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 " +
                "1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
        )
    }

    /** Points up; rotate it for the other arrow keys. */
    val Chevron by lazy { icon("chevron", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z") }
}

/**
 * How app icons are drawn under a pack, worked out once from its `[icons]`: plain values, so it is
 * tested without a screen. Home, the dock, the drawer and the recent strip all draw from it, through
 * [AppIcon] and [Pack].
 */
data class IconLook(
    /** Silhouettes in the pack's tint (true), each icon's own colors (false), or null: the monochrome switch in settings decides. */
    val mono: Boolean?,
    /** What an icon's own colors go through, as the 20 numbers of a 4 by 5 color matrix, row by row; null leaves them alone. */
    val matrix: List<Float>?,
    /** A small shadow under an icon that is drawn in its own colors with no frame around it. */
    val shadow: Boolean,
    /** What an app without an icon gets. */
    val placeholder: PlaceholderStyle,
) {
    /** Whether icons are silhouettes, given the switch in settings. */
    fun mono(user: Boolean) = mono ?: user

    /** True when an icon gets the shadow: only one in its own colors, and never one [framed] by a tile or a button of its own. */
    fun shadowed(
        mono: Boolean,
        framed: Boolean,
    ) = shadow && !mono && !framed

    /** What stands in for an icon [label]'s app doesn't have. */
    fun placeholderText(label: String) =
        when (placeholder) {
            PlaceholderStyle.Letter -> initials(label)
            PlaceholderStyle.Glyph -> PLACEHOLDER_GLYPH
            PlaceholderStyle.Blank -> ""
        }

    companion object {
        /** The one mark every app gets in a pack whose placeholder is a glyph: a bullet, which every system font has. */
        const val PLACEHOLDER_GLYPH = "•"

        /** The shadow, as the design prototype has it under every real icon: 2dp down, a quarter black. */
        const val SHADOW_DROP = 2f
        const val SHADOW_ALPHA = 0.25f
    }
}

/** [icons] as what is drawn. Console comes out as no treatment at all: the user's switch, no matrix, no shadow, a letter. */
fun iconLook(icons: PackIcons) =
    IconLook(
        mono =
            when (icons.treatment) {
                IconTreatment.User -> null
                IconTreatment.Monochrome -> true
                IconTreatment.Original, IconTreatment.Grayscale -> false
            },
        // Gray is no saturation, whatever the pack's own says.
        matrix = iconMatrix(if (icons.treatment == IconTreatment.Grayscale) 0f else icons.saturation, icons.contrast),
        shadow = icons.shadow,
        placeholder = icons.placeholder.style,
    )

/**
 * The color matrix for icons at [saturation] (1 as they are, 0 gray) and [contrast] (1 as they are,
 * about mid gray): each row gives red, green, blue or alpha from the four of them and a constant on
 * the 0 to 255 scale. Null when both are 1, so an untreated icon is drawn with no filter at all.
 */
fun iconMatrix(
    saturation: Float,
    contrast: Float,
): List<Float>? {
    if (saturation == 1f && contrast == 1f) return null
    // How bright each of red, green and blue looks: the gray an icon fades to.
    val gray = floatArrayOf(0.213f, 0.715f, 0.072f)
    val shift = 127.5f * (1f - contrast)
    val rows =
        (0 until 3).flatMap { row ->
            (0 until 3).map { column -> contrast * (gray[column] * (1f - saturation) + if (row == column) saturation else 0f) } + listOf(0f, shift)
        }
    return rows + listOf(0f, 0f, 0f, 1f, 0f)
}

/**
 * An app's icon as the pack treats it: a silhouette in the pack's tint when [mono], else in its own
 * colors through the pack's matrix, with the pack's shadow under it unless it is [framed] by a tile or
 * a button of its own. In Console this is the plain image the launcher always drew.
 */
@Composable
fun AppIcon(
    bitmap: ImageBitmap,
    description: String,
    modifier: Modifier,
    mono: Boolean,
    framed: Boolean = false,
) {
    Image(bitmap, description, if (Pack.iconShadowed(mono, framed)) modifier.iconShadow(bitmap) else modifier, colorFilter = Pack.icon(mono))
}

/** [bitmap]'s own outline again under it, a little lower: a shadow that follows the icon's shape, whatever it is. */
private fun Modifier.iconShadow(bitmap: ImageBitmap): Modifier {
    val filter = Pack.iconShadow
    return drawBehind {
        translate(top = IconLook.SHADOW_DROP.dp.toPx()) {
            drawImage(bitmap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = filter)
        }
    }
}

/**
 * What an app without an icon, a bare silhouette, a folder's four icons or one of the drawer's own
 * entries sits on: the pack's placeholder surface, [size] across, its corners of [radius], in [fill]
 * where that isn't the placeholder's own. Console's is a fill alone, as it always was; a pack that
 * outlines its placeholder gets the line.
 */
fun Modifier.iconBadge(
    size: Dp,
    radius: Dp = size / 2,
    fill: Color = Pack.badge,
): Modifier {
    val look = Pack.look
    val sized = size(size)
    return if (look.badgeLine.value > 0f) sized.framed(look.badgeLine, look.badgeEdge, fill, radius) else sized.clip(Pack.shape(radius)).background(fill)
}
