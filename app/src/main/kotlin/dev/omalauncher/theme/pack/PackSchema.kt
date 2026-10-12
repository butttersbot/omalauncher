package dev.omalauncher.theme.pack

/** What a token's value is, and the range it is kept to. */
sealed interface TokenKind {
    /** A color: `#rrggbb`, `#aarrggbb`, or a palette role like `accent`, each optionally `@alpha`. */
    data object Paint : TokenKind

    /** A number, pulled into [min]..[max]. */
    data class Number(
        val min: Float,
        val max: Float,
    ) : TokenKind

    /** A whole number, pulled into [min]..[max]. */
    data class Whole(
        val min: Int,
        val max: Int,
    ) : TokenKind

    /** One of [options]. */
    data class Choice(
        val options: List<String>,
    ) : TokenKind

    /** A short piece of text of at most [max] characters. */
    data class Text(
        val max: Int,
    ) : TokenKind

    /** true or false. */
    data object Flag : TokenKind

    /** A file inside the pack, or "" for none. */
    data class Asset(
        val type: AssetType,
    ) : TokenKind
}

/** The files a pack may carry, and the most of each the loader accepts. */
enum class AssetType(
    val extensions: Set<String>,
    val maxBytes: Long,
) {
    Font(setOf("ttf", "otf"), 8L * 1024 * 1024),
    Image(setOf("png", "jpg", "jpeg", "webp"), 16L * 1024 * 1024),
}

/** One thing a pack can say, and what Console says for it, written as a manifest would. */
class PackToken(
    /** Its dotted name in pack.toml: `[scrap.tape]` `color` is `scrap.tape.color`. */
    val key: String,
    val kind: TokenKind,
    val console: Any,
)

/**
 * A color as a pack writes it: fixed, or the palette's color for a role, so it follows whichever
 * Omarchy theme is picked.
 */
data class PackPaint(
    val literal: PackColor?,
    /** A palette role (`accent`), or `extra.` and a name from `[palette.extra]`. */
    val role: String?,
    /** Replaces the color's own alpha when set. */
    val alpha: Float?,
) {
    override fun toString() = (literal?.toString() ?: role.orEmpty()) + (alpha?.let { "@$it" } ?: "")

    companion object {
        private val NAME = Regex("^[a-z0-9_]{1,40}$")

        /** True when [text] gives an alpha outside 0 to 1, which [parse] pulls in. */
        fun alphaOutOfRange(text: String) = '@' in text && text.substringAfter('@').trim().toFloatOrNull()?.let { it < 0f || it > 1f } == true

        /** Null unless [text] is a color, or names one of [roles] or `extra.` one of [extras]. */
        fun parse(
            text: String,
            roles: Set<String>,
            extras: Set<String>,
        ): PackPaint? {
            if (text.length > 64) return null
            val base = text.substringBefore('@').trim()
            val alpha =
                if ('@' in text) {
                    text.substringAfter('@').trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return null
                } else {
                    null
                }
            if (base.startsWith("#")) return PackColor.parse(base)?.let { PackPaint(it, null, alpha) }
            val known = if (base.startsWith("extra.")) base.removePrefix("extra.") in extras else base in roles
            return if (known && NAME.matches(base.removePrefix("extra."))) PackPaint(null, base, alpha) else null
        }
    }
}

/**
 * Every token of the pack format, each with Console's value: the one list the loader, the
 * Console pack and the tests all read. Adding a token means adding it here, reading it in
 * [PackBuilder], and adding it to assets/packs/console/pack.toml; the tests fail until all
 * three agree.
 *
 * How the format stays compatible: a key's meaning never changes; a new format number only
 * adds keys; and a build ignores the keys it doesn't know. So an old pack loads on a new build
 * with Console's value for what was added since, and a new pack loads on an old build without
 * what that build can't draw. A token that has to mean something else gets a new key.
 */
object PackSchema {
    /** The format this build writes. A pack says which it was written for in `[pack] format`. */
    const val FORMAT = 1

    /** The roles an Omarchy theme's colors fill in, in [PackPalette]'s order. */
    val themeRoles = listOf("background", "surface", "foreground", "muted", "accent", "border", "red", "green")

    /** Every palette role a color elsewhere in a pack may name. */
    val roles = themeRoles + listOf("on_page", "highlight", "alt", "on_alt", "overlay", "glass")

    val typeRoles = TypeRole.entries.map { it.id }
    val ringStates = listOf("listening", "working", "needs_you", "done")

    private const val CLEAR = "#00000000"

    val tokens: List<PackToken> =
        buildList {
            fun paint(
                key: String,
                console: String,
            ) = add(PackToken(key, TokenKind.Paint, console))

            fun number(
                key: String,
                min: Float,
                max: Float,
                console: Number,
            ) = add(PackToken(key, TokenKind.Number(min, max), console))

            fun whole(
                key: String,
                min: Int,
                max: Int,
                console: Int,
            ) = add(PackToken(key, TokenKind.Whole(min, max), console))

            fun flag(
                key: String,
                console: Boolean,
            ) = add(PackToken(key, TokenKind.Flag, console))

            fun choice(
                key: String,
                options: List<PackChoice>,
                console: PackChoice,
            ) = add(PackToken(key, TokenKind.Choice(options.map { it.id }), console.id))

            fun shadow(key: String) {
                number("$key.x", -24f, 24f, 0)
                number("$key.y", -24f, 24f, 0)
                number("$key.blur", 0f, 48f, 0)
                paint("$key.color", CLEAR)
                flag("$key.inset", false)
            }

            fun surface(
                key: String,
                fill: String,
                border: Number,
                borderColor: String,
                ink: String = "foreground",
            ) {
                paint("$key.fill", fill)
                paint("$key.ink", ink)
                number("$key.border", 0f, 8f, border)
                paint("$key.border_color", borderColor)
                paint("$key.focus_color", "accent")
                shadow("$key.shadow")
                shadow("$key.shadow2")
            }

            // Palette. The defaults are ThemePalette's, which are Tokyo Night's.
            choice("palette.mode", PaletteMode.entries, PaletteMode.Dark)
            paint("palette.background", "#1a1b26")
            paint("palette.surface", "#24283b")
            paint("palette.foreground", "#c0caf5")
            paint("palette.muted", "#565f89")
            paint("palette.accent", "#7aa2f7")
            paint("palette.border", "#414868")
            paint("palette.red", "#f7768e")
            paint("palette.green", "#9ece6a")
            paint("palette.on_page", "foreground")
            paint("palette.highlight", "accent")
            paint("palette.alt", "surface")
            paint("palette.on_alt", "foreground")
            paint("palette.overlay", "background@0.9")
            paint("palette.glass", "background@0.88")

            // Type: Console is monospace throughout, at TextSize's sizes.
            for (role in typeRoles) {
                choice("type.$role.family", FontGeneric.entries, FontGeneric.Monospace)
                add(PackToken("type.$role.file", TokenKind.Asset(AssetType.Font), ""))
                whole("type.$role.weight", 100, 900, 400)
                number("type.$role.scale", 0.5f, 2f, 1)
                flag("type.$role.italic", false)
                flag("type.$role.uppercase", false)
                number("type.$role.tracking", -0.1f, 0.5f, 0)
            }
            flag("type.display.highlighter", false)
            number("type.size.small", 8f, 40f, 12)
            number("type.size.body", 8f, 40f, 14)
            number("type.size.item", 8f, 40f, 15)
            number("type.size.large", 8f, 40f, 16)
            number("type.size.title", 8f, 40f, 18)

            // Shape: frame()'s 16dp radius scaled by the user's corner style, and HomeGap.
            number("shape.radius", 0f, 48f, 16)
            choice("shape.corners", CornerStyle.entries, CornerStyle.User)
            number("shape.gap", 0f, 32f, 6)

            // Scraps: Modifier.tile() is a 2dp border over the glass fill, accent while focused.
            surface("scrap", "glass", 2, "border")
            surface("scrap.alt", "glass", 2, "accent")
            paint("scrap.muted", "muted")
            number("scrap.tilt_min", -12f, 12f, 0)
            number("scrap.tilt_max", -12f, 12f, 0)
            choice("scrap.tape.style", Dressing.entries, Dressing.None)
            paint("scrap.tape.color", "accent")
            paint("scrap.tape.color2", "accent")
            number("scrap.tape.opacity", 0f, 1f, 0.9)
            number("scrap.tape.width", 8f, 160f, 66)
            number("scrap.tape.height", 4f, 48f, 21)
            number("scrap.tape.angle", -45f, 45f, -3)
            choice("scrap.title_bar.style", Dressing.entries, Dressing.None)
            number("scrap.title_bar.height", 0f, 40f, 20)
            paint("scrap.title_bar.fill", "foreground")
            paint("scrap.title_bar.ink", "background")
            add(PackToken("scrap.title_bar.glyph", TokenKind.Text(4), ""))
            number("scrap.title_bar.border", 0f, 4f, 0)
            paint("scrap.sticky.fill", "surface")
            paint("scrap.sticky.ink", "foreground")
            number("scrap.sticky.border", 0f, 8f, 2)
            paint("scrap.sticky.border_color", "border")
            flag("scrap.sticky.dashed", false)

            // Page: Console keeps the system wallpaper the Omarchy theme set.
            choice("page.kind", PageKind.entries, PageKind.System)
            paint("page.color", "background")
            choice("page.pattern", PaperPattern.entries, PaperPattern.None)
            paint("page.pattern_color", "border")
            number("page.pattern_size", 2f, 64f, 16)
            number("page.pattern_weight", 0.5f, 8f, 1)
            add(PackToken("page.wallpaper_cover", TokenKind.Asset(AssetType.Image), ""))
            add(PackToken("page.wallpaper_inner", TokenKind.Asset(AssetType.Image), ""))
            flag("page.gutter", false)
            paint("page.overview_scrim", CLEAR)
            paint("page.drawer_fill", "overlay")
            choice("page.plate", PagePlate.entries, PagePlate.None)
            shadow("page.text_shadow")

            // Icons: the monochrome switch in settings decides; a missing icon is a letter in the accent color on the surface color.
            choice("icons.treatment", IconTreatment.entries, IconTreatment.User)
            paint("icons.tint", "accent")
            number("icons.saturation", 0f, 2f, 1)
            number("icons.contrast", 0.5f, 2f, 1)
            flag("icons.shadow", false)
            choice("icons.placeholder.style", PlaceholderStyle.entries, PlaceholderStyle.Letter)
            choice("icons.placeholder.font", TypeRole.entries, TypeRole.Mono)
            surface("icons.placeholder", "surface", 0, "border", ink = "accent")

            // Ring: timings are the prototype's; Console lights it in the theme's accent.
            number("ring.width", 0.5f, 8f, 2)
            number("ring.glow", 0f, 24f, 6)
            number("ring.glow_opacity", 0f, 1f, 0.6)
            for ((state, look) in ringStates.zip(
                listOf(
                    RingLook("accent", "foreground", 1, 4200, 0),
                    RingLook("accent", "foreground", 0.8, 9000, 0),
                    RingLook("red", "accent", 1, 14000, 0),
                    RingLook("green", "green", 1, 4200, 2),
                ),
            )) {
                paint("ring.$state.color1", look.color1)
                paint("ring.$state.color2", look.color2)
                number("ring.$state.opacity", 0f, 1f, look.opacity)
                whole("ring.$state.period", 500, 60_000, look.period)
                whole("ring.$state.pulses", 0, 10, look.pulses)
            }

            // Ink.
            paint("ink.pen", "on_page")
            paint("ink.second", "accent")
            paint("ink.highlighter", "highlight@0.5")
            number("ink.width", 1f, 16f, 3)
            choice("ink.blend", InkBlend.entries, InkBlend.Normal)

            // Dock: today's prompt bar and rails are tiles.
            choice("dock.style", DockStyle.entries, DockStyle.Bar)
            number("dock.tilt", 0f, 12f, 0)
            surface("dock", "glass", 2, "border")
        }

    val byKey: Map<String, PackToken> = tokens.associateBy { it.key }

    private class RingLook(
        val color1: String,
        val color2: String,
        val opacity: Number,
        val period: Int,
        val pulses: Int,
    )

    /** The longest path a manifest may give for a file. */
    const val MAX_PATH = 200

    /**
     * True for a character text shown to the user may not hold: a control character, or one of
     * Unicode's direction controls (U+202A to U+202E, U+2066 to U+2069), which would turn the
     * text around them back to front.
     */
    fun unshowable(c: Char) = c < ' ' || c == '\u007f' || c in '‪'..'‮' || c in '⁦'..'⁩'

    /** A path a manifest may give for a file: relative, inside the pack, and plainly spelled. Null if it's fine. */
    fun pathProblem(path: String): String? =
        when {
            path.length > MAX_PATH -> "the path is too long"
            path.any { it < ' ' || it == '\\' || it == ':' || it == '\u007f' } -> "the path has a character a pack's files can't"
            path.startsWith("/") || path.startsWith("~") -> "the path isn't inside the pack"
            path.split('/').any { it.isEmpty() || it == "." || it == ".." } -> "the path isn't inside the pack"
            else -> null
        }
}

/** Whether the pack is dark or light, which sets the system bars' icons. */
enum class PaletteMode(
    override val id: String,
) : PackChoice {
    Dark("dark"),
    Light("light"),
}

/** What became of one manifest value. */
internal sealed interface TokenRead {
    class Ok(
        val value: Any,
        val clamped: Boolean = false,
    ) : TokenRead

    class Bad(
        val why: String,
        val kind: PackIssue.Kind = PackIssue.Kind.Invalid,
    ) : TokenRead
}

/**
 * [raw] from a manifest as this token's value: a [PackPaint], Float, Int, String or Boolean.
 * A value of the wrong type is bad; one out of range is kept and pulled in.
 */
internal fun PackToken.read(
    raw: Any,
    extras: Set<String> = emptySet(),
): TokenRead =
    when (val kind = kind) {
        TokenKind.Paint ->
            (raw as? String)?.let { text -> PackPaint.parse(text, PackSchema.roles.toSet(), extras)?.let { TokenRead.Ok(it, PackPaint.alphaOutOfRange(text)) } }
                ?: TokenRead.Bad("not a color or a palette role")
        is TokenKind.Number -> {
            val n = (raw as? Number)?.toDouble()
            if (n == null || !n.isFinite()) {
                TokenRead.Bad("not a number")
            } else {
                TokenRead.Ok(n.toFloat().coerceIn(kind.min, kind.max), clamped = n < kind.min || n > kind.max)
            }
        }
        is TokenKind.Whole -> {
            val n = (raw as? Number)?.toDouble()
            if (n == null || !n.isFinite()) {
                TokenRead.Bad("not a number")
            } else {
                val whole = Math.rint(n)
                TokenRead.Ok(whole.coerceIn(kind.min.toDouble(), kind.max.toDouble()).toInt(), clamped = whole < kind.min || whole > kind.max)
            }
        }
        is TokenKind.Choice ->
            (raw as? String)?.lowercase()?.takeIf { it in kind.options }?.let { TokenRead.Ok(it) }
                ?: TokenRead.Bad("not one of ${kind.options.joinToString(", ")}")
        is TokenKind.Text ->
            when {
                raw !is String -> TokenRead.Bad("not text")
                raw.length > kind.max -> TokenRead.Bad("longer than ${kind.max} characters")
                raw.any(PackSchema::unshowable) -> TokenRead.Bad("has a character that can't be shown")
                else -> TokenRead.Ok(raw)
            }
        TokenKind.Flag -> (raw as? Boolean)?.let { TokenRead.Ok(it) } ?: TokenRead.Bad("not true or false")
        is TokenKind.Asset ->
            when {
                raw !is String -> TokenRead.Bad("not a file name")
                raw.isEmpty() -> TokenRead.Ok(raw)
                raw.length > PackSchema.MAX_PATH -> TokenRead.Bad("a path of more than ${PackSchema.MAX_PATH} characters")
                PackSchema.pathProblem(raw) != null -> TokenRead.Bad(PackSchema.pathProblem(raw)!!, PackIssue.Kind.FileOutside)
                raw.substringAfterLast('.', "").lowercase() !in kind.type.extensions ->
                    TokenRead.Bad("not a ${kind.type.extensions.joinToString(", ")} file")
                else -> TokenRead.Ok(raw)
            }
    }
