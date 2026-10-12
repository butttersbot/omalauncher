package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.omalauncher.theme.pack.CornerStyle
import dev.omalauncher.theme.pack.DockStyle
import dev.omalauncher.theme.pack.FontGeneric
import dev.omalauncher.theme.pack.PackColor
import dev.omalauncher.theme.pack.PackFont
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.PackShadow
import dev.omalauncher.theme.pack.PagePlate
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.ThemePack
import dev.omalauncher.theme.pack.TypeRole

/**
 * The gap between framed surfaces on home and from them to the screen's safe edges, where a pack can't
 * change it: the grid's own gap is fixed (GridGeometry), and the dock band and the side rail are measured
 * with this one, so no pack moves a tile or changes the band's height (PRD AC-18). It is Console's
 * `shape.gap`, and a test holds the two together.
 */
val PageGap = 6.dp

/** What a glyph is filled with so it can be tinted where it's drawn: a mask, not a color of any theme. */
val MaskInk = Color.White

fun PackColor.toColor() = Color(argb)

/** A theme's colors as the pack format takes them: `ConsolePack.withColors`'s argument. */
internal fun ThemePalette.packColors(): Map<String, String> =
    named.mapValues { PackColor(it.value.toArgb()).toString() } + ("mode" to if (isLight) "light" else "dark")

/** True when text straight on [pack]'s page should be dark: its paper is light, or its palette says so. */
internal fun pageIsLight(pack: ThemePack) = if (pack.page.kind == PageKind.Paper) pack.page.color.toColor().luminance() > 0.5f else pack.palette.isLight

/**
 * [pack]'s own palette as the colors everything not yet drawn from the pack reads: its eight theme roles
 * and its extra colors laid over [under]'s named ones.
 */
internal fun packPalette(
    pack: ThemePack,
    under: ThemePalette,
): ThemePalette {
    val p = pack.palette
    return ThemePalette(
        under.named.mapValues { PackColor(it.value.toArgb()).toString() } +
            p.named.mapValues { it.value.toString() } +
            mapOf(
                "background" to p.background.toString(),
                "lighter_background" to p.surface.toString(),
                "foreground" to p.foreground.toString(),
                "dark_foreground" to p.muted.toString(),
                "accent" to p.accent.toString(),
                "muted" to p.border.toString(),
                "red" to p.red.toString(),
                "green" to p.green.toString(),
                "mode" to if (pageIsLight(pack)) "light" else "dark",
            ),
    )
}

/** A pack's wallpapers once decoded; either may be missing. */
class PackWallpapers(
    val cover: ImageBitmap? = null,
    val inner: ImageBitmap? = null,
)

/**
 * The theme pack in use: the one place that knows which, for everything that draws. Console in the
 * applied Omarchy theme's colors until another is chosen, and whenever the chosen one can't be loaded.
 */
object ActivePack {
    /** What was picked and what loading it came to; ThemeStore saves the id. */
    var pick by mutableStateOf(PackPick.Console)
        private set

    /** The pack's font files by their path in the pack, once read; a role whose file isn't here uses its system family. */
    var fonts by mutableStateOf<Map<String, FontFamily>>(emptyMap())

    /** The pack's wallpapers, once decoded. */
    var wallpapers by mutableStateOf(PackWallpapers())

    /** Draws from [pick] from now on. Its fonts and wallpapers are set afterwards by whoever read them. */
    fun choose(pick: PackPick) {
        fonts = emptyMap()
        wallpapers = PackWallpapers()
        this.pick = pick
        // Made again from each theme that is applied, so the named colors under the pack's are never a past theme's.
        Palette.cover(if (pick.isConsole) null else pick.load?.let { load -> { under: ThemePalette -> packPalette(load.pack, under) } })
    }

    @Volatile private var resolved: Triple<PackPick, ThemePalette, ThemePack>? = null

    /** The pack to draw from, every token resolved. Read in a composable, it follows the choice and the theme. */
    val pack: ThemePack
        get() {
            // While the pack's colors are lifted off the theme's (Palette.uncovered, for previews), Console is drawn.
            val pick = pick.let { if (it.isConsole || Palette.covered) it else PackPick.Console }
            val theme = Palette.theme
            resolved?.let { (of, colors, pack) -> if (of === pick && colors === theme) return pack }
            return PackRegistry.resolve(pick, theme.packColors()).also { resolved = Triple(pick, theme, it) }
        }

    @Volatile private var looked: Triple<ThemePack, Map<String, FontFamily>, PackLook>? = null

    /** [pack] in Compose's values. */
    val look: PackLook
        get() {
            val pack = pack
            val fonts = fonts
            looked?.let { (of, with, look) -> if (of === pack && with === fonts) return look }
            return PackLook(pack, fonts).also { looked = Triple(pack, fonts, it) }
        }
}

/** The launcher's five text sizes, as the pack sets them. Android's font size scales them all. */
@Immutable
class PackSizes(
    val small: TextUnit,
    val body: TextUnit,
    val item: TextUnit,
    val large: TextUnit,
    val title: TextUnit,
)

/**
 * A resolved pack in Compose's values: the adapter between the plain numbers of [ThemePack] and what
 * composables draw with. Built once per pack, not per read.
 */
@Immutable
class PackLook(
    val pack: ThemePack,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    private val p = pack.palette
    val background = p.background.toColor()
    val surface = p.surface.toColor()
    val overlay = p.overlay.toColor()
    val foreground = p.foreground.toColor()
    val muted = p.muted.toColor()
    val accent = p.accent.toColor()
    val border = p.border.toColor()
    val red = p.red.toColor()
    val green = p.green.toColor()
    val glass = p.glass.toColor()
    val onPage = p.onPage.toColor()
    val highlight = p.highlight.toColor()

    /** Text and captions on a scrap. */
    val scrapInk = pack.scrap.surface.ink.toColor()
    val scrapMuted = pack.scrap.muted.toColor()

    /** Monochrome icons and the launcher's own glyphs. */
    val tint = pack.icons.tint.toColor()

    /** The letter standing in for an icon, and what it and a bare silhouette sit on. */
    val letter = pack.icons.placeholder.surface.ink.toColor()
    val badge = pack.icons.placeholder.surface.fill.toColor()

    val sizes = pack.type.sizes.run { PackSizes(small.sp, body.sp, item.sp, large.sp, title.sp) }

    /** A scrap's outline width, and the widths drawn from it: [mark] for focus and selection, [hair] for light outlines. */
    val line = pack.scrap.surface.border.dp
    val mark = maxOf(pack.scrap.surface.border, MIN_MARK).dp
    val hair = maxOf(pack.scrap.surface.border / 2f, MIN_HAIR).dp

    /** A frame's radius where the caller gives none. */
    val radius = pack.shape.radius.dp

    /** What a caller's own radius is multiplied by: callers give theirs for Console's 16dp frames. */
    private val radiusScale = pack.shape.radius / CONSOLE_RADIUS

    private val families: Map<TypeRole, FontFamily> =
        TypeRole.entries.associateWith { role -> font(role).let { fonts[it.file] ?: it.family.family() } }

    fun font(role: TypeRole): PackFont =
        when (role) {
            TypeRole.Display -> pack.type.display
            TypeRole.Body -> pack.type.body
            TypeRole.Hand -> pack.type.hand
            TypeRole.Mono -> pack.type.mono
        }

    /** Text in [role]'s voice at [size], which the role's scale multiplies. Console's is the launcher's monospace style. */
    fun style(
        role: TypeRole,
        size: TextUnit = sizes.body,
        color: Color = foreground,
    ): TextStyle {
        val font = font(role)
        return TextStyle(
            fontFamily = families.getValue(role),
            fontSize = if (font.scale == 1f) size else size * font.scale,
            color = color,
            fontWeight = if (font.weight == 400) null else FontWeight(font.weight),
            fontStyle = if (font.italic) FontStyle.Italic else null,
            letterSpacing = if (font.tracking == 0f) TextUnit.Unspecified else font.tracking.em,
        )
    }

    /** [text] as [role] spells it: in capitals when the pack says so. */
    fun cased(
        role: TypeRole,
        text: String,
    ) = if (font(role).uppercase) text.uppercase() else text

    /** The shape of a frame of [radius] under the corner style [userScale] the user picked, unless the pack sets its own. */
    fun shape(
        radius: Dp,
        userScale: Float,
    ): Shape {
        val scale = pack.shape.cornerScale(userScale)
        return if (scale == 0f) RectangleShape else RoundedCornerShape(radius * scale * radiusScale)
    }

    /** A shape that is round whatever corner style the user picked: square only in a pack with square corners. */
    fun round(radius: Dp): Shape = if (pack.shape.corners == CornerStyle.Edge) RectangleShape else RoundedCornerShape(radius * radiusScale)

    /** True when the pack draws its own page, rather than leaving the system's or the theme's wallpaper. */
    val ownsPage = pack.page.kind != PageKind.System

    /** The stock the page is made of, for what is laid over it: the pack's paper, or the theme's background where the pack has none. */
    val paper = if (ownsPage) pack.page.color.toColor() else background

    /**
     * A name straight on the page, under a bare icon. Over the system's wallpaper, which can be anything:
     * light text with a shadow, like stock launchers. On a pack's own page: its page ink and text shadow.
     */
    fun pageLabel(size: TextUnit): TextStyle =
        if (ownsPage) {
            style(TypeRole.Body, size, onPage).copy(shadow = pack.page.textShadow.takeIf { it.visible }?.let(::textShadow))
        } else {
            style(TypeRole.Body, size, Color.White).copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 6f))
        }

    /** Quiet text and guide lines straight on the page: the theme's over the system's wallpaper, the page's ink on a pack's own page. */
    val pageMuted = if (ownsPage) onPage.copy(alpha = onPage.alpha * 0.7f) else muted
    val pageLine = if (ownsPage) onPage else border

    /** What sits behind text straight on the page, for pages too busy to read on; clear when the pack wants none. */
    val plate = if (ownsPage && pack.page.plate == PagePlate.Paper) pack.page.color.toColor() else Color.Transparent

    /** The page's name over it, and what it sits on. */
    val folio: TextStyle = if (ownsPage) style(TypeRole.Hand, sizes.body, onPage) else style(TypeRole.Body, sizes.body, muted)
    val folioPlate = if (ownsPage) plate else background.copy(alpha = 0.6f)

    /** How the pack treats app icons, as plain values (see [IconLook]). */
    val icons = iconLook(pack.icons)

    /** An icon in its own colors as the pack treats it; null leaves it alone. */
    val iconFilter: ColorFilter? = icons.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it.toFloatArray())) }

    /** Whether icons are silhouettes in [tint]: the pack's say, or the switch in settings. */
    fun monoIcons(user: Boolean) = icons.mono(user)

    /** The outline of the placeholder's badge; no width in Console, whose badge is a fill alone. */
    val badgeLine = pack.icons.placeholder.surface.border.dp
    val badgeEdge = pack.icons.placeholder.surface.borderColor.toColor()

    /** True when panels are light, for the system bars' icons over a full-screen one like the chat. */
    val panelsLight = if (ownsPage) background.luminance() > 0.5f else p.isLight

    private fun textShadow(shadow: PackShadow) = Shadow(shadow.color.toColor(), Offset(shadow.x, shadow.y), shadow.blur)

    internal companion object {
        /** Console's frame radius, which every caller's own radius is written against. */
        private const val CONSOLE_RADIUS = 16f

        /** Focus and selection are never drawn thinner than this, even in a pack whose scraps have no outline. */
        const val MIN_MARK = 2f
        private const val MIN_HAIR = 1f

        private fun FontGeneric.family(): FontFamily =
            when (this) {
                FontGeneric.Monospace -> FontFamily.Monospace
                FontGeneric.SansSerif -> FontFamily.SansSerif
                FontGeneric.Serif -> FontFamily.Serif
                FontGeneric.Cursive -> FontFamily.Cursive
            }
    }
}

/**
 * What home, the drawer, the dock and the Oma chat draw with: the active pack's colors, type and shapes
 * (PRD AC-19). Nothing in those screens reads a color, font, shape or border from anywhere else.
 */
object Pack {
    val look get() = ActivePack.look

    val background get() = look.background
    val surface get() = look.surface
    val overlay get() = look.overlay
    val foreground get() = look.foreground
    val muted get() = look.muted
    val accent get() = look.accent
    val border get() = look.border
    val red get() = look.red
    val green get() = look.green
    val glass get() = look.glass
    val onPage get() = look.onPage
    val paper get() = look.paper
    val highlight get() = look.highlight
    /** Glyphs on the dock's own surface. */
    val dockInk get() = look.pack.dock.surface.ink.toColor()
    val scrapInk get() = look.scrapInk
    val scrapMuted get() = look.scrapMuted
    val tint get() = look.tint
    val badge get() = look.badge
    val plate get() = look.plate
    val folioPlate get() = look.folioPlate
    val pageMuted get() = look.pageMuted
    val pageLine get() = look.pageLine

    /** No color at all. */
    val clear = Color.Transparent

    val size get() = look.sizes
    val mark get() = look.mark
    val hair get() = look.hair

    /** Everything read at length; what the launcher's text was before packs. */
    fun body(
        size: TextUnit = look.sizes.body,
        color: Color = look.foreground,
    ) = look.style(TypeRole.Body, size, color)

    /** Headings and big marks. */
    fun display(
        size: TextUnit = look.sizes.title,
        color: Color = look.foreground,
    ) = look.style(TypeRole.Display, size, color)

    /** Handwriting: what Oma heard. */
    fun hand(
        size: TextUnit = look.sizes.body,
        color: Color = look.foreground,
    ) = look.style(TypeRole.Hand, size, color)

    /** Captions and second lines. */
    fun mono(
        size: TextUnit = look.sizes.body,
        color: Color = look.foreground,
    ) = look.style(TypeRole.Mono, size, color)

    /** The letter standing in for an app's icon, in the placeholder's ink unless [color] says otherwise. */
    fun placeholder(
        size: TextUnit,
        color: Color = look.letter,
    ) = look.style(look.pack.icons.placeholder.font, size, color)

    /** A glyph of the launcher's symbol font, which no pack replaces. */
    fun symbol(
        size: TextUnit,
        color: Color,
    ) = TextStyle(fontFamily = NerdSymbols, fontSize = size, color = color)

    /**
     * The text the dock band is measured with: Console's, in every pack, so the band is as tall in one
     * pack as in another and the grid above it keeps its room. It still grows with Android's font size.
     */
    fun strut(size: TextUnit) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size)

    fun pageLabel(size: TextUnit = look.sizes.small) = look.pageLabel(size)

    val folio get() = look.folio

    /** [text] as the pack spells a heading: in capitals where its display voice says so. */
    fun heading(text: String) = look.cased(TypeRole.Display, text)

    /** [text] as the pack spells a caption. */
    fun caption(text: String) = look.cased(TypeRole.Mono, text)

    /** The shape frames use: square, or [radius] scaled by the corner style. */
    fun shape(radius: Dp = look.radius): Shape = look.shape(radius, LauncherStyle.corners.scale)

    fun round(radius: Dp): Shape = look.round(radius)

    /** App icons as silhouettes in [tint]. */
    val monoIcons get() = look.monoIcons(LauncherStyle.monochromeIcons)

    /** Home's apps as bare icons on the page instead of framed tiles: the user's choice in every pack. */
    val bareIcons get() = LauncherStyle.bareAppIcons

    /** The strip of recently used apps: the user's choice, and room on home that no pack changes. */
    /** The strip of recent apps over the dock: Console's. A look that draws its own page has the prototype's dock, which the recent apps fill instead. */
    val recentApps get() = LauncherStyle.recentApps && !look.ownsPage

    /** How an icon is drawn: a silhouette in [tint] when [mono], else in its own colors as the pack treats them. */
    fun icon(mono: Boolean): ColorFilter? = if (mono) ColorFilter.tint(look.tint) else look.iconFilter

    /** True when an icon in its own colors gets the pack's small shadow: not a silhouette, and not [framed] by a tile or a button. */
    fun iconShadowed(
        mono: Boolean,
        framed: Boolean,
    ) = look.icons.shadowed(mono, framed)

    /** What that shadow is drawn with: the icon's own outline in a thin black. */
    val iconShadow: ColorFilter by lazy { ColorFilter.tint(Color.Black.copy(alpha = IconLook.SHADOW_ALPHA)) }

    /** What stands in for an icon [label]'s app doesn't have: its initials, the pack's one mark, or nothing. */
    fun placeholderText(label: String) = look.icons.placeholderText(label)

    /** A glyph drawn in [color]. */
    fun tinted(color: Color = look.tint): ColorFilter = ColorFilter.tint(color)

    val isLight get() = look.panelsLight
    val ownsPage get() = look.ownsPage
    val dock get() = look.pack.dock.style

    /** True when each dock icon is a framed button of its own. */
    val dockButtons get() = look.pack.dock.style == DockStyle.Buttons
}

/** A framed surface in the pack's corner style: clipped, filled, then outlined with one shape. */
fun Modifier.framed(
    width: Dp,
    color: Color,
    fill: Color = Color.Transparent,
    radius: Dp = Pack.look.radius,
): Modifier {
    val shape = Pack.shape(radius)
    return clip(shape).background(fill, shape).border(width, color, shape)
}

/** A panel over home: a menu, a dialog, an open folder, the edit bar. The emphasised scrap's outline on the pack's opaque background. */
fun Modifier.panel(radius: Dp = Pack.look.radius): Modifier {
    val alt = Pack.look.pack.scrap.alt
    return framed(maxOf(alt.border, 1f).dp, alt.borderColor.toColor(), Pack.background, radius)
}
