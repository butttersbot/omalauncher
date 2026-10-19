package dev.omalauncher.theme.pack

/**
 * A look whose home pages are manga pages: fixed panels in hairline ink on paper, the lines never
 * moving when what is in them does. It is a pack's `[panels]` section, and only a pack that has the
 * section is drawn in panels; every other look has none ([ThemePack.panels] is null).
 *
 * Lengths on the page are in the panel layouts' page units (`PanelLayouts`): the page is 60 wide
 * and 96 tall, so a unit across is a sixtieth of its width and a unit down a ninety-sixth of its
 * height. Lengths drawn the same at any size, the ink and the dots, are in dp.
 *
 * The lines are drawn in the palette's foreground (the ink) and an empty panel is the palette's
 * background (the paper); the section holds what the palette can't say.
 */
data class PackPanels(
    /** The panel layout a page is fitted into when the look is switched on: one of [LAYOUTS]. */
    val defaultLayout: String,
    /** How wide every panel's outline is, in dp. */
    val inkWidth: Float,
    /** The gap between rows of panels, in page units down. A manga's gaps between rows are wider than its gaps across. */
    val gutterRows: Float,
    /** The gap between panels side by side, in page units across. */
    val gutterCols: Float,
    /** From the page's edges to its panels, in page units. */
    val margins: PanelMargins,
    /** From a panel's outline to what is drawn in it, in page units: the room a widget keeps from the ink. */
    val pad: Float,
    /** The dots an empty panel is toned with. */
    val screentone: PackScreentone,
    /** How a picture in a panel is printed until the user says otherwise. */
    val artPrint: ArtPrint,
    /** What an empty panel shows. */
    val empty: PanelEmpty,
) {
    /** The section as a manifest writes it, by its dotted keys: see [PackTokens.manifest]. */
    fun manifest(): Map<String, Any> =
        mapOf(
            "panels.default_layout" to defaultLayout,
            "panels.ink_width" to inkWidth,
            "panels.gutter_rows" to gutterRows,
            "panels.gutter_cols" to gutterCols,
            "panels.margins.left" to margins.left,
            "panels.margins.top" to margins.top,
            "panels.margins.right" to margins.right,
            "panels.margins.bottom" to margins.bottom,
            "panels.pad" to pad,
            "panels.screentone.dot" to screentone.dot,
            "panels.screentone.spacing" to screentone.spacing,
            "panels.screentone.opacity" to screentone.opacity,
            "panels.screentone.fade" to screentone.fade,
            "panels.art_print" to artPrint.id,
            "panels.empty" to empty.id,
        )

    companion object {
        const val SECTION = "panels"

        /** The panel layouts a page can have, as the notebook model names them (CONTRACT.md, `PanelLayouts`). */
        val LAYOUTS = listOf("opening", "beats", "tallstack", "action5", "splash3", "yonkoma", "grid6", "splash")

        /**
         * Every key of the section, each with the value it has where a pack with the section leaves
         * it out: the mockup's (features/manga-look/manga-mockup.html). [PackToken.console] holds that
         * default; Console itself has no panels.
         */
        val tokens: List<PackToken> =
            listOf(
                PackToken("panels.default_layout", TokenKind.Choice(LAYOUTS), "opening"),
                PackToken("panels.ink_width", TokenKind.Number(0.5f, 4f), 1.15),
                PackToken("panels.gutter_rows", TokenKind.Number(0f, 8f), 2.3),
                PackToken("panels.gutter_cols", TokenKind.Number(0f, 8f), 1.1),
                PackToken("panels.margins.left", TokenKind.Number(0f, 12f), 2.4),
                PackToken("panels.margins.top", TokenKind.Number(0f, 12f), 3),
                PackToken("panels.margins.right", TokenKind.Number(0f, 12f), 2.4),
                PackToken("panels.margins.bottom", TokenKind.Number(0f, 12f), 4.4),
                PackToken("panels.pad", TokenKind.Number(0f, 6f), 1.1),
                PackToken("panels.screentone.dot", TokenKind.Number(0.2f, 4f), 0.85),
                PackToken("panels.screentone.spacing", TokenKind.Number(1f, 24f), 4.5),
                PackToken("panels.screentone.opacity", TokenKind.Number(0f, 1f), 0.42),
                PackToken("panels.screentone.fade", TokenKind.Number(0f, 1f), 0.04),
                PackToken("panels.art_print", TokenKind.Choice(ArtPrint.entries.map { it.id }), ArtPrint.Ink.id),
                PackToken("panels.empty", TokenKind.Choice(PanelEmpty.entries.map { it.id }), PanelEmpty.Screentone.id),
            )

        val byKey: Map<String, PackToken> = tokens.associateBy { it.key }

        /** The section's values as the loader checked them, one for every key of [tokens]. */
        internal fun from(values: Map<String, Any>): PackPanels {
            fun number(key: String) = values.getValue(key) as Float

            fun text(key: String) = values.getValue(key) as String
            return PackPanels(
                defaultLayout = text("panels.default_layout"),
                inkWidth = number("panels.ink_width"),
                gutterRows = number("panels.gutter_rows"),
                gutterCols = number("panels.gutter_cols"),
                margins =
                    PanelMargins(
                        left = number("panels.margins.left"),
                        top = number("panels.margins.top"),
                        right = number("panels.margins.right"),
                        bottom = number("panels.margins.bottom"),
                    ),
                pad = number("panels.pad"),
                screentone =
                    PackScreentone(
                        dot = number("panels.screentone.dot"),
                        spacing = number("panels.screentone.spacing"),
                        opacity = number("panels.screentone.opacity"),
                        fade = number("panels.screentone.fade"),
                    ),
                artPrint = text("panels.art_print").let { id -> ArtPrint.entries.first { it.id == id } },
                empty = text("panels.empty").let { id -> PanelEmpty.entries.first { it.id == id } },
            )
        }

        /** The section with nothing in it set: the mockup's panels. */
        val defaults: PackPanels =
            from(
                tokens.associate { token ->
                    when (val read = token.read(token.console)) {
                        is TokenRead.Ok -> token.key to read.value.also { check(!read.clamped) { "the default ${token.key} is out of range" } }
                        is TokenRead.Bad -> error("the default ${token.key} is ${read.why}")
                    }
                },
            )
    }
}

/** In page units ([PackPanels]). */
data class PanelMargins(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A grid of round dots in the ink, laid square. */
data class PackScreentone(
    /** A dot's radius, in dp. Never more than half of [spacing], so the dots never run together. */
    val dot: Float,
    /** From one dot's center to the next, in dp. */
    val spacing: Float,
    /** How dark the tone is where it is darkest, at the panel's top left; 1 is the ink itself. */
    val opacity: Float,
    /**
     * How dark it has faded to 70% of the way across the panel, from its top left toward its bottom right
     * (the mockup's 165° sweep), and stays beyond. Equal to [opacity] for an even tone.
     */
    val fade: Float,
)

/** How a picture in a panel is printed. */
enum class ArtPrint(
    override val id: String,
) : PackChoice {
    /** In the ink: gray, harder, and toned with dots, as a manga prints a photo. */
    Ink("ink"),

    /** In its own colors. */
    Color("color"),
}

/** What a panel with nothing in it shows. */
enum class PanelEmpty(
    override val id: String,
) : PackChoice {
    /** The screentone, so an empty panel reads as part of the page and not a hole in it. */
    Screentone("screentone"),

    /** Bare paper inside its outline. */
    Paper("paper"),
}
