package dev.omalauncher.theme.pack

/**
 * The default pack: the launcher as it looked before packs, monospace in framed glass tiles
 * over the Omarchy theme's wallpaper. It is built into the code from [PackSchema], so the
 * launcher is complete with no file read; assets/packs/console/pack.toml states the same values
 * as the pack other packs are written from, and a test holds the two together.
 */
object ConsolePack {
    const val ID = "console"

    /**
     * Console's face, as Soma Canvas's font store has it (manifest 92235ea631ee; Boss, 2026-10-29 20:08): JetBrains Mono
     * in every voice, the body with Schibsted Grotesk after it, then the store's symbols, emoji and CJK. In the app's
     * assets/fonts (not a pack file, so packs that name none still draw their family); what Console's monospace is.
     */
    const val FONT = "console/JetBrainsMono.ttf"

    /** After [FONT] in the body's stack only. */
    const val BODY_FALLBACK = "console/SchibstedGrotesk.ttf"

    /** Console's own tokens. A value in [PackSchema] that isn't one for its token fails here, and so in the tests. */
    val tokens: PackTokens =
        PackTokens(
            id = ID,
            name = "Console",
            format = PackSchema.FORMAT,
            values =
                PackSchema.tokens.associate { token ->
                    when (val read = token.read(token.console)) {
                        is TokenRead.Ok -> token.key to read.value.also { check(!read.clamped) { "Console's ${token.key} is out of range" } }
                        is TokenRead.Bad -> error("Console's ${token.key} is ${read.why}")
                    }
                },
            extra = emptyMap(),
        )

    /** Console in its own colors, which are Tokyo Night's: what shows before any theme is applied. */
    val pack: ThemePack = tokens.resolve()

    private val colors: Map<String, PackColor> by lazy {
        PackSchema.tokens.filter { it.kind == TokenKind.Paint }.associate { it.key to PackBuilder(tokens, null, null).color(it.key) }
    }

    /** Console's color for a color token, for when a pack's own can't be worked out. */
    internal fun color(key: String): PackColor = colors[key] ?: PackColor.Clear

    /**
     * Console drawn in an Omarchy theme's colors (PRD AC-21): [colors] is the theme's resolved
     * colors.toml, as `Palette.apply` takes it. A color the theme lacks is Console's own.
     */
    fun withColors(colors: Map<String, String>): ThemePack = tokens.resolve(colors)
}
