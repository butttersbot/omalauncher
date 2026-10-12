package dev.omalauncher.theme.pack

/**
 * A theme pack with every token resolved: how the launcher looks beyond its colors. Lengths are
 * in dp, text sizes in sp, angles in degrees and colors ARGB, all as plain numbers, so a pack is
 * built and tested without Android. Nothing here is ever missing: what a pack leaves out is
 * Console's (see [ConsolePack]).
 */
data class ThemePack(
    /** The pack's folder name: lowercase letters, digits and dashes. */
    val id: String,
    /** What the pack is called where the user picks one. */
    val name: String,
    val palette: PackPalette,
    val type: PackType,
    val shape: PackShape,
    val scrap: PackScrap,
    val page: PackPage,
    val icons: PackIcons,
    val ring: PackRing,
    val ink: PackInk,
    val dock: PackDock,
)

/** A color as ARGB, like `android.graphics.Color`'s ints and `Color.toArgb()`. */
@JvmInline
value class PackColor(
    val argb: Int,
) {
    /** This color at [alpha] from 0 to 1, replacing its own, rounded as Compose's `Color.copy(alpha)` rounds. */
    fun withAlpha(alpha: Float) = PackColor((argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24))

    /** `#aarrggbb`, the form the launcher's hexColor() reads. */
    override fun toString() = "#%08x".format(argb)

    companion object {
        val Clear = PackColor(0)

        /** `#rrggbb` or `#aarrggbb`; null for anything else. */
        fun parse(hex: String): PackColor? {
            val h = hex.trim().removePrefix("#")
            if (h.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            return when (h.length) {
                6 -> PackColor(h.toLong(16).toInt() or 0xFF000000.toInt())
                8 -> PackColor(h.toLong(16).toInt())
                else -> null
            }
        }
    }
}

/** A token with a fixed set of values; [id] is how a manifest writes it. */
interface PackChoice {
    val id: String
}

/**
 * The pack's colors by role. The first eight are the ones an Omarchy theme supplies; the rest
 * are usually written in terms of those ("background@0.88"), so they follow the theme.
 */
data class PackPalette(
    val isLight: Boolean,
    /** The page itself, and what panels are filled with. */
    val background: PackColor,
    val surface: PackColor,
    /** Text on scraps and panels. */
    val foreground: PackColor,
    val muted: PackColor,
    val accent: PackColor,
    val border: PackColor,
    val red: PackColor,
    val green: PackColor,
    /** Text and marks drawn straight on the page or wallpaper, outside any scrap. */
    val onPage: PackColor,
    /** Highlighter, sticky notes and selected things. */
    val highlight: PackColor,
    /** The fill of an emphasised scrap, and [onAlt] the text on it. */
    val alt: PackColor,
    val onAlt: PackColor,
    /** The scrim behind dialogs and full-screen panels. */
    val overlay: PackColor,
    /** The translucent fill of surfaces over the wallpaper. */
    val glass: PackColor,
    /** Further colors by name: an Omarchy theme's whole colors.toml, or a pack's `[palette.extra]`. */
    val named: Map<String, PackColor>,
)

/** The four voices text is set in, and the sizes they scale. */
data class PackType(
    /** Headings and big numbers. */
    val display: PackFont,
    /** Everything read at length. */
    val body: PackFont,
    /** Handwriting: notes, margins, what Oma heard. */
    val hand: PackFont,
    /** Captions, code and terminal text. */
    val mono: PackFont,
    val sizes: PackTextSizes,
    /** Headings sit on a stroke of the palette's highlight, like marker over highlighter. */
    val displayHighlighter: Boolean,
)

data class PackFont(
    /** The system family used when the pack ships no [file], or it can't be read. */
    val family: FontGeneric,
    /** A font file inside the pack, or "" for none. */
    val file: String,
    /** 100 to 900. */
    val weight: Int,
    /** Multiplies every size this role is drawn at. */
    val scale: Float,
    val italic: Boolean,
    val uppercase: Boolean,
    /** Letter spacing in em. */
    val tracking: Float,
)

enum class FontGeneric(
    override val id: String,
) : PackChoice {
    Monospace("monospace"),
    SansSerif("sans-serif"),
    Serif("serif"),
    Cursive("cursive"),
}

/** The launcher's text sizes in sp, before a role's scale; the names are `TextSize`'s. */
data class PackTextSizes(
    val small: Float,
    val body: Float,
    val item: Float,
    val large: Float,
    val title: Float,
)

data class PackShape(
    /** A frame's corner radius where the caller doesn't give its own. */
    val radius: Float,
    val corners: CornerStyle,
    /** The gap between scraps, and from them to the screen's safe edges. */
    val gap: Float,
) {
    /** What a frame multiplies its radius by, given the scale of the corner style the user picked. */
    fun cornerScale(user: Float) = corners.scale ?: user
}

enum class CornerStyle(
    override val id: String,
    /** Null leaves it to the corner style in the user's settings. */
    val scale: Float?,
) : PackChoice {
    User("user", null),
    Edge("edge", 0f),
    Soft("soft", 0.5f),
    Round("round", 1f),
}

/** A filled, outlined, shadowed box: a scrap, a dock button, an icon's placeholder. */
data class PackSurface(
    val fill: PackColor,
    /** Text and glyphs on it. */
    val ink: PackColor,
    /** Outline width; 0 for none. */
    val border: Float,
    val borderColor: PackColor,
    /** The outline while focused or listening. */
    val focusColor: PackColor,
    /**
     * How wide the focus line is in all, where the surface's own outline isn't wider. What is over 2
     * (or over the outline) is drawn outside the surface's edge in the page's focus color, so a look
     * can mark focus by weight, and so the line shows on the page as well as on the fill.
     */
    val focusWidth: Float,
    val shadow: PackShadow,
    /** A second shadow under the first, for a key cap's edge or a soft cast below a hard one. */
    val shadow2: PackShadow,
)

data class PackShadow(
    val x: Float,
    val y: Float,
    val blur: Float,
    val color: PackColor,
    /** Drawn inside the box, like a recessed display. */
    val inset: Boolean,
) {
    /** False when there is nothing to draw. */
    val visible get() = color.argb ushr 24 != 0 && (x != 0f || y != 0f || blur != 0f)
}

/** How tiles are dressed as scraps on the page. */
data class PackScrap(
    val surface: PackSurface,
    /** An emphasised scrap. */
    val alt: PackSurface,
    /** Captions and second lines on a scrap. */
    val muted: PackColor,
    /** Each scrap is tilted by a fixed angle picked between these two. Only what's drawn tilts, never where it's touched. */
    val tiltMin: Float,
    val tiltMax: Float,
    val tape: PackTape,
    val titleBar: PackTitleBar,
    val sticky: PackSticky,
)

/** Plain, or striped in two colors, or absent: shared by tape and title bars. */
enum class Dressing(
    override val id: String,
) : PackChoice {
    None("none"),
    Solid("solid"),
    Stripes("stripes"),
}

/** The strip of tape holding a scrap to the page. */
data class PackTape(
    val style: Dressing,
    val color: PackColor,
    /** The second stripe. */
    val color2: PackColor,
    val opacity: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
)

/** A window title bar across the top of each scrap. */
data class PackTitleBar(
    val style: Dressing,
    val height: Float,
    val fill: PackColor,
    val ink: PackColor,
    /** A mark at the bar's end, like a close box's ×; "" for none. */
    val glyph: String,
    /** A line under the bar, in the scrap's border color. */
    val border: Float,
)

data class PackSticky(
    val fill: PackColor,
    val ink: PackColor,
    val border: Float,
    val borderColor: PackColor,
    val dashed: Boolean,
)

/** What is behind the scraps. */
data class PackPage(
    val kind: PageKind,
    /** The paper, and what shows while a wallpaper loads. */
    val color: PackColor,
    val pattern: PaperPattern,
    val patternColor: PackColor,
    /** The distance between dots or lines. */
    val patternSize: Float,
    /** How thick a dot or line is. */
    val patternWeight: Float,
    /** Image files inside the pack for the folded and the unfolded display, or "" for none. */
    val wallpaperCover: String,
    val wallpaperInner: String,
    /** A soft shadow down the page's inner edge, like a notebook's gutter. */
    val gutter: Boolean,
    /** Laid over each page in the overview of all pages. */
    val overviewScrim: PackColor,
    /** What the app drawer is filled with. */
    val drawerFill: PackColor,
    /** Focus where it is drawn straight on the page and so has to show there: a bare icon's outline, and the part of a surface's focus line outside its edge. */
    val focusColor: PackColor,
    val plate: PagePlate,
    /** Under text drawn straight on the page; [PackShadow.inset] means nothing here. */
    val textShadow: PackShadow,
)

enum class PageKind(
    override val id: String,
) : PackChoice {
    /** The system wallpaper, which is the Omarchy theme's background today. */
    System("system"),

    /** The page color and pattern. */
    Paper("paper"),

    /** The pack's own images, one per display. */
    Wallpaper("wallpaper"),
}

enum class PaperPattern(
    override val id: String,
) : PackChoice {
    None("none"),
    Dots("dots"),
    Lines("lines"),
    Grid("grid"),
    Checker("checker"),
}

/** Whether text on the page gets a paper-colored plate behind it, for pages too busy to read on. */
enum class PagePlate(
    override val id: String,
) : PackChoice {
    None("none"),
    Paper("paper"),
}

data class PackIcons(
    val treatment: IconTreatment,
    /** The color of monochrome icons. */
    val tint: PackColor,
    /** 1 leaves an icon's colors alone; 0 is gray. */
    val saturation: Float,
    val contrast: Float,
    /** A small drop shadow under icons that aren't in a frame. */
    val shadow: Boolean,
    val placeholder: PackPlaceholder,
)

enum class IconTreatment(
    override val id: String,
) : PackChoice {
    /** Whatever the monochrome icons switch in settings says. */
    User("user"),
    Original("original"),
    Monochrome("monochrome"),
    Grayscale("grayscale"),
}

/** What stands in for an app that has no icon. */
data class PackPlaceholder(
    val style: PlaceholderStyle,
    /** The voice its letter is set in. */
    val font: TypeRole,
    val surface: PackSurface,
)

enum class PlaceholderStyle(
    override val id: String,
) : PackChoice {
    /** The app's first letter. */
    Letter("letter"),

    /** One mark for every app. */
    Glyph("glyph"),
    Blank("blank"),
}

enum class TypeRole(
    override val id: String,
) : PackChoice {
    Display("display"),
    Body("body"),
    Hand("hand"),
    Mono("mono"),
}

/** The light around the screen's edge while Oma is doing something. */
data class PackRing(
    val width: Float,
    /** The blur of the glow behind the line; 0 for a bare line. */
    val glow: Float,
    val glowOpacity: Float,
    val listening: PackRingState,
    val working: PackRingState,
    val needsYou: PackRingState,
    val done: PackRingState,
)

data class PackRingState(
    /** The two colors the light runs between. */
    val color1: PackColor,
    val color2: PackColor,
    val opacity: Float,
    /** Milliseconds for the light to go once around. */
    val periodMs: Int,
    /** How many times it breathes before it fades; 0 keeps it steady. */
    val pulses: Int,
)

/** What the pen draws with on a page. */
data class PackInk(
    val pen: PackColor,
    val second: PackColor,
    val highlighter: PackColor,
    val width: Float,
    val blend: InkBlend,
)

enum class InkBlend(
    override val id: String,
) : PackChoice {
    Normal("normal"),

    /** Ink darkens what it crosses, like one print run over another. */
    Multiply("multiply"),
}

data class PackDock(
    val style: DockStyle,
    /** Buttons lean this far, alternating left and right. */
    val tilt: Float,
    val surface: PackSurface,
)

enum class DockStyle(
    override val id: String,
) : PackChoice {
    /** One framed bar, like today's prompt bar and rails. */
    Bar("bar"),

    /** A row of separate framed buttons. */
    Buttons("buttons"),

    /** Icons straight on the page. */
    Bare("bare"),
}
