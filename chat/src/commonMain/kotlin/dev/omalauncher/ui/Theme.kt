package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `#rrggbb` or `#aarrggbb` as a color; null if it isn't one. */
fun hexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** A theme's colors, parsed once when it's applied rather than on every read. */
@Immutable
class ThemePalette(
    colors: Map<String, String>,
) {
    /** Every color from the theme's colors.toml by name (accent, red, yellow, ...). */
    val named: Map<String, Color> = colors.mapNotNull { (k, v) -> hexColor(v)?.let { k to it } }.toMap()

    private fun color(
        key: String,
        fallback: Long,
    ) = named[key] ?: Color(fallback)

    val isLight = colors["mode"] == "light"
    val background = color("background", 0xFF1A1B26)
    val surface = color("lighter_background", 0xFF24283B)
    val overlay = background.copy(alpha = 0.9f)
    val foreground = color("foreground", 0xFFC0CAF5)
    val muted = color("dark_foreground", 0xFF565F89)
    val accent = color("accent", 0xFF7AA2F7)
    val border = color("muted", 0xFF414868)
    val red = color("red", 0xFFF7768E)
    val green = color("green", 0xFF9ECE6A)
    /** Console's text: JetBrains Mono from the font store once ThemeStore has read it ([PlatformFonts.consoleMono]). */
    val mono: TextStyle get() = PlatformFonts.consoleMono.let { console ->
        if (console == null) TextStyle(fontFamily = FontFamily.Monospace, fontSize = TextSize.Body, color = foreground)
        // JetBrains Mono's lines are taller (1.32 em) than the system monospace's (Droid Sans Mono, 1.164 em) that Console's
        // fixed-height cards were laid out for; at that line height they hold what they held (lane C, 6becf8a9).
        else TextStyle(
            fontFamily = console, fontSize = TextSize.Body, color = foreground,
            lineHeight = CONSOLE_LINE.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }
}

/**
 * Launcher colors, driven by the active Omarchy theme. Until one is applied
 * they're the defaults above, which match Tokyo Night.
 */
object Palette {
    /** The applied Omarchy theme's colors, whichever theme pack is drawn. */
    var theme by mutableStateOf(ThemePalette(emptyMap()))
        private set

    /** Makes a theme pack's own colors from the theme's, while a pack other than Console is in use: see [cover]. */
    private var covering: ((ThemePalette) -> ThemePalette)? = null

    /** What [covering] made of [theme], made again whenever either changes; null without a cover. */
    private var packed by mutableStateOf<ThemePalette?>(null)

    /** What is drawn in: the theme's colors, or the pack's own. */
    val current: ThemePalette get() = packed ?: theme

    /** True while a theme pack's own colors are laid over the theme's. */
    val covered get() = packed != null

    fun apply(theme: Map<String, String>) {
        val applied = ThemePalette(theme)
        this.theme = applied
        // The pack's colors lie over the theme's named ones, so they are made again from the new theme.
        packed = covering?.invoke(applied)
    }

    /**
     * Draws everything that still reads these colors (widgets, settings) in what [cover] makes of the
     * theme: a theme pack's own colors, so they can be read on its scraps. Null goes back to the theme's.
     * The theme stays applied underneath, and the cover follows it when another is applied.
     */
    fun cover(cover: ((ThemePalette) -> ThemePalette)?) {
        covering = cover
        packed = cover?.invoke(theme)
    }

    /**
     * Runs [block] with no pack's colors over the theme's, so each theme [apply] is given inside it shows
     * in its own colors whichever pack is in use; the launcher draws Console meanwhile. Afterwards the
     * theme and the cover are exactly what they were, also when [block] throws. For rendering previews.
     */
    inline fun <T> uncovered(block: () -> T): T {
        val saved = lift()
        try {
            return block()
        } finally {
            putBack(saved)
        }
    }

    /** What [uncovered] takes away and puts back. */
    @PublishedApi
    internal class Saved(
        val theme: ThemePalette,
        val covering: ((ThemePalette) -> ThemePalette)?,
        val packed: ThemePalette?,
    )

    @PublishedApi
    internal fun lift(): Saved {
        val saved = Saved(theme, covering, packed)
        covering = null
        packed = null
        return saved
    }

    @PublishedApi
    internal fun putBack(saved: Saved) {
        theme = saved.theme
        covering = saved.covering
        packed = saved.packed
    }

    fun named(name: String): Color? = current.named[name]

    val isLight get() = current.isLight
    val background get() = current.background
    val surface get() = current.surface
    val overlay get() = current.overlay
    val foreground get() = current.foreground
    val muted get() = current.muted
    val accent get() = current.accent
    val border get() = current.border
    val red get() = current.red
    val green get() = current.green

    /** The translucent fill of tiles, bars and rails over the wallpaper. */
    val glass get() = background.copy(alpha = 0.88f)
}

/** How the launcher draws itself, independent of the theme's colors. Saved by ThemeStore. */
object LauncherStyle {
    /** App icons as silhouettes in the accent color (Omarchy) rather than their own colors. */
    var monochromeIcons by mutableStateOf(false)

    /** Home screen apps as bare icons on the wallpaper instead of framed tiles. */
    var bareAppIcons by mutableStateOf(false)

    /** The strip of recently used apps above the bar on home. */
    var recentApps by mutableStateOf(true)

    /** The phone's lock and home wallpapers are the look's paper, set again whenever another look is picked. */
    var lockFollowsLook by mutableStateOf(false)

    /** How frames (tiles, panels, menus, fields) round their corners. */
    var corners by mutableStateOf(Corners.Edge)

    /** What frames multiply their corner radius by; 0 is square. Widgets read it as `corners` (owl 6). */
    val cornerScale get() = corners.scale

    /** The shape frames use: square, or [radius] scaled by the corner style. */
    fun shape(radius: Dp = 16.dp): Shape = if (corners == Corners.Edge) RectangleShape else RoundedCornerShape(radius * corners.scale)
}

/** Corner styles, from Omarchy's sharp edges to stock Android's round shapes; [scale] multiplies each frame's radius. */
enum class Corners(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
    val scale: Float,
) {
    Edge("edge", "edge", 0f),
    Soft("soft", "soft", 0.5f),
    Round("round", "round", 1f),
    ;

    companion object {
        /** Also takes the enum names ("Round") launchers before versioning saved. */
        fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Edge
    }
}

/** A framed surface in the user's corner style: clipped, filled, then outlined with one shape. */
fun Modifier.frame(
    width: Dp,
    color: Color,
    fill: Color = Color.Transparent,
    radius: Dp = 16.dp,
): Modifier {
    val shape = LauncherStyle.shape(radius)
    return clip(shape).background(fill, shape).border(width, color, shape)
}

/**
 * Omarchy's gap between framed surfaces: between home's tiles, from them to the screen's
 * safe edges, and from full-screen panels to those edges, so a panel's frame lines up with the tiles'.
 */
val HomeGap = 6.dp

/** The widest a column of text and rows gets on a big screen: the bar beside landscape home, text-heavy panels. */
val ReadingWidth = 640.dp

/** A home-screen surface over the wallpaper: tiles, the prompt bar, the rails; accent while [focused]. */
fun Modifier.tile(focused: Boolean = false): Modifier = frame(2.dp, if (focused) Palette.accent else Palette.border, Palette.glass)

/** The launcher's text style in the theme's foreground color. */
val Mono get() = Palette.current.mono

/**
 * The launcher's text sizes. Nothing is smaller than [Small], which is still
 * readable on a phone at arm's length; Android's font size scales them all.
 */
object TextSize {
    /** Second lines, statuses, hints and labels under icons. */
    val Small = 12.sp

    /** Everything else; [Mono]'s size. */
    val Body = 14.sp

    /** List items and menu entries. */
    val Item = 15.sp

    /** Fields and dialog text. */
    val Large = 16.sp

    /** Screen and dialog titles. */
    val Title = 18.sp
}

/**
 * The few Nerd Font glyphs omarchy uses that Android lacks: weather (U+E300–E3E3, see
 * weatherIcon()) plus the launcher's own [Glyph]s. A subset of Symbols Nerd Font 3.5.1, made
 * with pyftsubset; Weather Icons by Erik Flowers, SIL OFL 1.1; Material Design Icons, Apache 2.0.
 */
val NerdSymbols: FontFamily get() = PlatformFonts.nerdSymbols

/**
 * The fonts each app bundles its own way, given to the shared code before anything draws: the phone app from its
 * resources (App.onCreate), the desktop app from its jar. Until then, and in unit tests, the default family.
 */
/** Droid Sans Mono's line height in em ((1901 + 483) / 2048), which Console's cards were laid out for. */
const val CONSOLE_LINE = 1.164f

object PlatformFonts {
    @Volatile var nerdSymbols: FontFamily = FontFamily.Default

    /** Console's own font with the store's fallbacks (ThemeStore), while Console is the look; null: the system monospace. */
    var consoleMono by mutableStateOf<FontFamily?>(null)
}

/** The Material Design Icons in [NerdSymbols] the launcher draws with [Symbol]. */
object Glyph {
    /** nf-md-check 󰄬 */
    const val Check = "\uDB80\uDD2C"

    /** nf-md-close 󰅖 */
    const val Close = "\uDB80\uDD56"

    /** nf-md-chevron_down 󰅀 */
    const val ChevronDown = "\uDB80\uDD40"

    /** nf-md-chevron_right 󰅂 */
    const val ChevronRight = "\uDB80\uDD42"

    /** nf-md-chevron_up 󰅃 */
    const val ChevronUp = "\uDB80\uDD43"
}
