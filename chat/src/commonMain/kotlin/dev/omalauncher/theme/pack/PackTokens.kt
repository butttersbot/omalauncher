package dev.omalauncher.theme.pack

/**
 * A pack as its manifest states it, after checking: one value for every token in [PackSchema],
 * with colors still written as palette roles where the pack wrote them so. [resolve] turns
 * that into the [ThemePack] the launcher draws from.
 */
data class PackTokens internal constructor(
    val id: String,
    val name: String,
    /** The format the pack was written for, which may be newer than this build's. */
    val format: Int,
    /** A [PackPaint], Float, Int, String or Boolean for every [PackToken.key]. */
    val values: Map<String, Any>,
    /** The pack's `[palette.extra]` colors by name. */
    val extra: Map<String, PackColor>,
) {
    /** The pack.toml that loads back as these tokens: see [PackManifest.write]. */
    fun manifest(): Map<String, Any> =
        buildMap {
            put("pack.format", format)
            put("pack.name", name)
            for (token in PackSchema.tokens) {
                val value = this@PackTokens.values.getValue(token.key)
                put(token.key, if (value is PackPaint) value.toString() else value)
            }
            for ((key, color) in extra) put("palette.extra.$key", color.toString())
        }

    /**
     * The pack ready to draw from. Given an Omarchy theme's [colors] (its resolved colors.toml),
     * the palette's eight theme roles and its light or dark mode come from the theme the way
     * `ThemePalette` reads them, and every color the pack wrote as a role follows.
     */
    fun resolve(colors: Map<String, String>? = null): ThemePack = PackBuilder(this, colors, null).build()
}

/** Builds a [ThemePack] from tokens, reading each token once and resolving its colors. */
class PackBuilder(
    private val tokens: PackTokens,
    colors: Map<String, String>?,
    /** Takes what goes wrong while resolving; null when nothing can (Console's own tokens). */
    private val issues: MutableList<PackIssue>?,
) {
    /** Every token [build] asked for, so a test can tell a token nothing reads. */
    val read = HashSet<String>()

    private val themed: Map<String, PackColor> =
        colors?.let { theme ->
            PackSchema.themeRoles.zip(THEME_KEYS).mapNotNull { (role, key) -> theme[key]?.let(PackColor::parse)?.let { "palette.$role" to it } }.toMap()
        } ?: emptyMap()
    /** The pack's own extra colors, with the theme's laid over them. */
    private val named: Map<String, PackColor> =
        tokens.extra + colors.orEmpty().mapNotNull { (key, value) -> PackColor.parse(value)?.let { key to it } }
    private val light: Boolean? = colors?.let { it["mode"] == "light" }

    private fun value(key: String): Any {
        read += key
        return tokens.values.getValue(key)
    }

    private fun number(key: String) = value(key) as Float

    private fun whole(key: String) = value(key) as Int

    private fun flag(key: String) = value(key) as Boolean

    private fun text(key: String) = value(key) as String

    private inline fun <reified T> choice(key: String): T where T : Enum<T>, T : PackChoice = text(key).let { id -> enumValues<T>().first { it.id == id } }

    /** The color of the color token [key], with its role and alpha worked out. */
    fun color(key: String): PackColor {
        read += key
        return color(key, HashSet())
    }

    private fun color(
        key: String,
        visiting: MutableSet<String>,
    ): PackColor {
        themed[key]?.let { return it }
        val paint = tokens.values.getValue(key) as PackPaint
        val base =
            paint.literal ?: run {
                val role = paint.role.orEmpty()
                if (role.startsWith("extra.")) return@run tokens.extra[role.removePrefix("extra.")] ?: ConsolePack.color(key)
                val target = "palette.$role"
                visiting += key
                if (target in visiting) {
                    issues?.add(PackIssue(key, PackIssue.Kind.Invalid, "its color leads back to itself"))
                    return ConsolePack.color(key)
                }
                color(target, visiting).also { visiting -= key }
            }
        return paint.alpha?.let { base.withAlpha(it) } ?: base
    }

    private fun shadow(key: String) = PackShadow(number("$key.x"), number("$key.y"), number("$key.blur"), color("$key.color"), flag("$key.inset"))

    private fun surface(key: String) =
        PackSurface(
            fill = color("$key.fill"),
            ink = color("$key.ink"),
            border = number("$key.border"),
            borderColor = color("$key.border_color"),
            focusColor = color("$key.focus_color"),
            focusWidth = number("$key.focus_width"),
            shadow = shadow("$key.shadow"),
            shadow2 = shadow("$key.shadow2"),
        )

    private fun font(role: String) =
        PackFont(
            family = choice("type.$role.family"),
            file = text("type.$role.file"),
            weight = whole("type.$role.weight"),
            scale = number("type.$role.scale"),
            italic = flag("type.$role.italic"),
            uppercase = flag("type.$role.uppercase"),
            tracking = number("type.$role.tracking"),
        )

    private fun ring(state: String) =
        PackRingState(
            color1 = color("ring.$state.color1"),
            color2 = color("ring.$state.color2"),
            opacity = number("ring.$state.opacity"),
            periodMs = whole("ring.$state.period"),
            pulses = whole("ring.$state.pulses"),
        )

    fun build(): ThemePack {
        val mode = choice<PaletteMode>("palette.mode")
        val tilts = listOf(number("scrap.tilt_min"), number("scrap.tilt_max"))
        return ThemePack(
            id = tokens.id,
            name = tokens.name,
            palette =
                PackPalette(
                    isLight = light ?: (mode == PaletteMode.Light),
                    background = color("palette.background"),
                    surface = color("palette.surface"),
                    foreground = color("palette.foreground"),
                    muted = color("palette.muted"),
                    accent = color("palette.accent"),
                    border = color("palette.border"),
                    red = color("palette.red"),
                    green = color("palette.green"),
                    onPage = color("palette.on_page"),
                    highlight = color("palette.highlight"),
                    alt = color("palette.alt"),
                    onAlt = color("palette.on_alt"),
                    overlay = color("palette.overlay"),
                    glass = color("palette.glass"),
                    named = named,
                ),
            type =
                PackType(
                    display = font("display"),
                    body = font("body"),
                    hand = font("hand"),
                    mono = font("mono"),
                    sizes =
                        PackTextSizes(
                            small = number("type.size.small"),
                            body = number("type.size.body"),
                            item = number("type.size.item"),
                            large = number("type.size.large"),
                            title = number("type.size.title"),
                        ),
                    displayHighlighter = flag("type.display.highlighter"),
                ),
            shape = PackShape(radius = number("shape.radius"), corners = choice("shape.corners"), gap = number("shape.gap")),
            scrap =
                PackScrap(
                    surface = surface("scrap"),
                    alt = surface("scrap.alt"),
                    muted = color("scrap.muted"),
                    // Written the wrong way round, the range is still the one the pack meant.
                    tiltMin = tilts.min(),
                    tiltMax = tilts.max(),
                    tape =
                        PackTape(
                            style = choice("scrap.tape.style"),
                            color = color("scrap.tape.color"),
                            color2 = color("scrap.tape.color2"),
                            opacity = number("scrap.tape.opacity"),
                            width = number("scrap.tape.width"),
                            height = number("scrap.tape.height"),
                            angle = number("scrap.tape.angle"),
                        ),
                    titleBar =
                        PackTitleBar(
                            style = choice("scrap.title_bar.style"),
                            height = number("scrap.title_bar.height"),
                            fill = color("scrap.title_bar.fill"),
                            ink = color("scrap.title_bar.ink"),
                            glyph = text("scrap.title_bar.glyph"),
                            border = number("scrap.title_bar.border"),
                        ),
                    sticky =
                        PackSticky(
                            fill = color("scrap.sticky.fill"),
                            ink = color("scrap.sticky.ink"),
                            border = number("scrap.sticky.border"),
                            borderColor = color("scrap.sticky.border_color"),
                            dashed = flag("scrap.sticky.dashed"),
                        ),
                ),
            page =
                PackPage(
                    kind = choice("page.kind"),
                    color = color("page.color"),
                    pattern = choice("page.pattern"),
                    patternColor = color("page.pattern_color"),
                    patternSize = number("page.pattern_size"),
                    patternWeight = number("page.pattern_weight"),
                    wallpaperCover = text("page.wallpaper_cover"),
                    wallpaperInner = text("page.wallpaper_inner"),
                    gutter = flag("page.gutter"),
                    overviewScrim = color("page.overview_scrim"),
                    drawerFill = color("page.drawer_fill"),
                    focusColor = color("page.focus_color"),
                    plate = choice("page.plate"),
                    textShadow = shadow("page.text_shadow"),
                ),
            icons =
                PackIcons(
                    treatment = choice("icons.treatment"),
                    tint = color("icons.tint"),
                    saturation = number("icons.saturation"),
                    contrast = number("icons.contrast"),
                    shadow = flag("icons.shadow"),
                    placeholder =
                        PackPlaceholder(
                            style = choice("icons.placeholder.style"),
                            font = choice("icons.placeholder.font"),
                            surface = surface("icons.placeholder"),
                        ),
                ),
            ring =
                PackRing(
                    width = number("ring.width"),
                    glow = number("ring.glow"),
                    glowOpacity = number("ring.glow_opacity"),
                    listening = ring("listening"),
                    working = ring("working"),
                    needsYou = ring("needs_you"),
                    done = ring("done"),
                ),
            ink =
                PackInk(
                    pen = color("ink.pen"),
                    second = color("ink.second"),
                    highlighter = color("ink.highlighter"),
                    width = number("ink.width"),
                    blend = choice("ink.blend"),
                ),
            dock = PackDock(style = choice("dock.style"), tilt = number("dock.tilt"), surface = surface("dock")),
        )
    }

    private companion object {
        /** The colors.toml name `ThemePalette` reads for each of [PackSchema.themeRoles], in the same order. */
        val THEME_KEYS = listOf("background", "lighter_background", "foreground", "dark_foreground", "accent", "muted", "red", "green")
    }
}
