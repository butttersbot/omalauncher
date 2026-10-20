package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Which pack is drawn: the choice, its fallback to Console, and what is read from a pack's files. */
class ActivePackTest {
    private val dialUp = DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up"))
    private val nord = mapOf("background" to "#2e3440", "foreground" to "#d8dee9", "accent" to "#88c0d0", "mode" to "dark")

    @Test fun nothingSavedIsConsole() {
        val pick = PackRegistry.choose(null) { error("Console is never opened") }
        assertSame(PackPick.Console, pick)
        assertTrue(pick.isConsole)
        assertEquals(ConsolePack.pack, PackRegistry.resolve(pick, null))
        assertEquals(ConsolePack.withColors(nord), PackRegistry.resolve(pick, nord))
    }

    /** PRD AC-21: Console is drawn in the Omarchy theme's colors; another pack keeps its own. */
    @Test fun onlyConsoleTakesTheThemesColors() {
        val pick = PackRegistry.choose("dial-up") { dialUp }
        assertFalse(pick.isConsole)
        assertNull(pick.error)
        val pack = PackRegistry.resolve(pick, nord)
        assertEquals("dial-up", pack.id)
        assertEquals(PackColor.parse("#f7f3ee"), pack.palette.background)
        assertEquals(pick.load!!.pack, pack)
    }

    /** PRD UP-10: a pack whose manifest can't be used leaves Console drawing, and says why for the picker. */
    @Test fun aBrokenPackFallsBackToConsole() {
        for (manifest in listOf("", "not toml at all [", "[pack]\nname = \"No format\"\n", "[pack]\nformat = \"one\"\n")) {
            val pick = PackRegistry.choose("broken") { packOf(manifest) }
            assertEquals("broken", pick.id)
            assertTrue(manifest, pick.isConsole)
            assertNotNull(manifest, pick.error)
            assertEquals(ConsolePack.withColors(nord), PackRegistry.resolve(pick, nord))
        }
    }

    @Test fun aPackThatIsGoneIsConsole() {
        assertSame(PackPick.Console, PackRegistry.choose("gone") { null })
        assertSame(PackPick.Console, PackRegistry.choose("throws") { error("no such folder") })
    }

    /** A pack with a bad value still draws, with Console's value for that token only. */
    @Test fun aBadTokenFallsBackAlone() {
        val pick = PackRegistry.choose("odd") { packOf(consoleManifest(mapOf("shape.radius" to "wide", "palette.accent" to "#ff0000"))) }
        assertFalse(pick.isConsole)
        val pack = PackRegistry.resolve(pick, nord)
        assertEquals(ConsolePack.pack.shape.radius, pack.shape.radius, 0f)
        assertEquals(PackColor.parse("#ff0000"), pack.palette.accent)
        assertTrue(pick.load!!.broken)
    }

    /** The id `console` is the built-in pack's: a folder of that name is never read. */
    @Test fun consoleIsReserved() {
        val opened = ArrayList<String>()
        val open = { id: String ->
            opened += id
            if (id == "dial-up") dialUp else packOf(consoleManifest(mapOf("pack.name" to "Impostor")))
        }
        val options = PackRegistry.options(listOf("dial-up", "console", "dial-up"), open)
        assertEquals(listOf("console", "dial-up"), options.map { it.id })
        assertEquals(listOf("Console", "Dial-Up"), options.map { it.name })
        assertNull(options[0].load)
        assertEquals(listOf("dial-up"), opened)
        assertSame(PackPick.Console, PackRegistry.choose("console", open))
        assertNull(PackRegistry.load("console", open))
        assertEquals(listOf("dial-up"), opened)
    }

    /** Console is the base, not a look to pick: the pickers leave it out unless it is the one in use. */
    @Test fun consoleIsNotOffered() {
        val options = PackRegistry.options(listOf("dial-up")) { dialUp }
        assertEquals(listOf("console", "dial-up"), options.map { it.id })
        assertEquals(listOf("dial-up"), PackRegistry.offered(options, "dial-up").map { it.id })
        assertEquals(listOf("console", "dial-up"), PackRegistry.offered(options, "console").map { it.id })
        val alone = PackRegistry.options(emptyList()) { null }
        assertEquals(listOf("console"), PackRegistry.offered(alone, "dial-up").map { it.id })
    }

    private val shipped =
        listOf(
            "riso" to "Riso",
            "instrument" to "Instrument",
            "one-bit" to "1-bit",
            "city-pop" to "City Pop",
            "custard" to "Custard",
            "tiny-magazine" to "Tiny Magazine",
            "bujo" to "Bujo",
            "dial-up" to "Dial-Up",
            "manga" to "Manga",
        )

    private fun shippedPack(id: String) = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id)

    /** Each pack shipped beside Console loads from its asset folder with nothing wrong: only tokens it leaves to Console. */
    @Test fun theShippedPacksLoadClean() {
        val folders = moduleFile("src/main/assets/packs").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
        assertEquals((shipped.map { it.first } + ConsolePack.ID).sorted(), folders)
        for ((id, name) in shipped) {
            val load = shippedPack(id)
            assertNull("$id: ${load.error}", load.error)
            assertFalse("$id: ${load.issues.filter { it.kind != PackIssue.Kind.Missing }}", load.broken)
            assertEquals("$id: ${load.issues.filter { it.kind != PackIssue.Kind.Missing }}", emptyList<PackIssue>(), load.issues.filter { it.kind != PackIssue.Kind.Missing })
            assertEquals(id, load.pack.id)
            assertEquals(name, load.pack.name)
            assertEquals(PackSchema.FORMAT, load.tokens.format)
            // Every voice names one of the prototype's fonts, and the file is in the pack. Only City Pop names wallpapers (its two images, checked in PackWallpaperTest).
            val type = load.pack.type
            for (font in listOf(type.display, type.body, type.hand, type.mono)) {
                assertTrue("$id names no font file for a voice", font.file.startsWith("fonts/"))
                assertTrue("$id: ${font.file} isn't in the pack", moduleFile("src/main/assets/packs/$id/${font.file}").isFile)
            }
            if (id == "city-pop") {
                assertEquals(id, "paper/cover.jpg", load.pack.page.wallpaperCover)
                assertEquals(id, "paper/inner.jpg", load.pack.page.wallpaperInner)
                assertTrue(id, moduleFile("src/main/assets/packs/$id/${load.pack.page.wallpaperCover}").isFile && moduleFile("src/main/assets/packs/$id/${load.pack.page.wallpaperInner}").isFile)
            } else {
                assertEquals(id, "", load.pack.page.wallpaperCover + load.pack.page.wallpaperInner)
            }
            // Each draws its own page (City Pop lays a wallpaper on its color), and none is Console under another name.
            assertEquals(id, if (id == "city-pop") PageKind.Wallpaper else PageKind.Paper, load.pack.page.kind)
            assertFalse(load.pack == ConsolePack.pack.copy(id = id, name = name))
            // Text on a scrap, on a panel and on the page can be told from what it is on.
            val pack = load.pack
            assertFalse("$id scrap", pack.scrap.surface.ink == pack.scrap.surface.fill)
            assertFalse("$id panel", pack.palette.foreground == pack.palette.background)
            assertFalse("$id page", pack.palette.onPage == pack.page.color)
            assertFalse("$id dock", pack.dock.surface.ink == pack.dock.surface.fill)
            assertFalse("$id letter", pack.icons.placeholder.surface.ink == pack.icons.placeholder.surface.fill)
        }
    }

    /** The looks, as the prototype has them: the tokens that make each pack what it is. */
    @Test fun theShippedPacksLookLikeThePrototype() {
        fun color(hex: String) = PackColor.parse(hex)!!
        val riso = shippedPack("riso").pack
        assertEquals(color("#e7e5ee"), riso.page.color)
        assertEquals(color("#2337ff"), riso.scrap.surface.ink)
        assertEquals(PackShadow(5f, 5f, 0f, color("#ff3d8b"), inset = false), riso.scrap.surface.shadow)
        assertEquals(Dressing.Solid, riso.scrap.tape.style)
        assertTrue(riso.scrap.tiltMin < 0f && riso.scrap.tiltMax > 0f)
        assertEquals(InkBlend.Multiply, riso.ink.blend)
        assertEquals(CornerStyle.Edge, riso.shape.corners)
        assertEquals(FontGeneric.Cursive, riso.type.hand.family)
        assertEquals(DockStyle.Buttons, riso.dock.style)

        val instrument = shippedPack("instrument").pack
        assertEquals(color("#d7d7d1"), instrument.page.color)
        assertEquals(color("#d7d7d1"), instrument.page.drawerFill)
        assertTrue(instrument.page.gutter && instrument.icons.shadow)
        assertEquals(0f, instrument.scrap.alt.border, 0f)
        assertEquals(color("#26241f"), instrument.scrap.sticky.ink)
        assertEquals(400, instrument.type.body.weight)
        assertEquals(color("#191b18"), instrument.scrap.surface.fill)
        assertEquals(color("#cfe0a8"), instrument.scrap.surface.ink)
        assertEquals(0f, instrument.scrap.surface.border, 0f)
        assertTrue(instrument.scrap.surface.shadow.inset)
        assertEquals(0f, instrument.scrap.tiltMax - instrument.scrap.tiltMin, 0f)
        assertEquals(Dressing.None, instrument.scrap.tape.style)
        assertEquals(color("#ecece6"), instrument.dock.surface.fill)
        assertEquals(0.75f, instrument.icons.saturation, 0f)
        assertTrue(instrument.type.display.uppercase)
        assertEquals(listOf(instrument.palette.accent), listOf(instrument.ring.listening, instrument.ring.working, instrument.ring.needsYou, instrument.ring.done).flatMap { listOf(it.color1, it.color2) }.distinct())

        val oneBit = shippedPack("one-bit").pack
        val colors = oneBit.palette.run { listOf(background, surface, foreground, muted, accent, border, red, green, onPage, highlight, alt, onAlt) } +
            oneBit.scrap.surface.run { listOf(fill, ink, borderColor, focusColor, shadow.color) } + listOf(oneBit.page.color, oneBit.page.patternColor)
        assertEquals(setOf(color("#000000"), color("#ffffff")), colors.toSet())
        assertEquals(PaperPattern.Checker, oneBit.page.pattern)
        assertEquals(PagePlate.Paper, oneBit.page.plate)
        assertEquals(Dressing.Stripes, oneBit.scrap.titleBar.style)
        assertEquals(IconTreatment.Grayscale, oneBit.icons.treatment)
        assertTrue(oneBit.scrap.sticky.dashed)
        // The ring keeps the default glow, as the prototype's does.
        assertEquals(ConsolePack.pack.ring.glow, oneBit.ring.glow, 0f)
        assertTrue(oneBit.ring.glow > 0f)
        assertTrue(oneBit.icons.shadow && oneBit.page.gutter)
        assertEquals(PackShadow(3f, 3f, 0f, color("#000000"), inset = false), oneBit.scrap.alt.shadow)

        /** Both colors of every state of the ring. */
        fun ring(pack: ThemePack) = pack.ring.run { listOf(listening, working, needsYou, done) }.flatMap { listOf(it.color1, it.color2) }.distinct()

        val cityPop = shippedPack("city-pop").pack
        assertEquals(color("#0f4fc9"), cityPop.page.color)
        assertEquals(color("#ffffff"), cityPop.palette.onPage)
        assertEquals(color("#ffffff"), cityPop.scrap.surface.fill)
        assertEquals(color("#12296b"), cityPop.scrap.surface.ink)
        assertEquals(0f, cityPop.scrap.surface.border, 0f)
        // rgba(8,28,96,.55), alpha first.
        assertEquals(PackShadow(7f, 7f, 0f, color("#8c081c60"), inset = false), cityPop.scrap.surface.shadow)
        assertEquals(PackShadow(7f, 7f, 0f, color("#8c081c60"), inset = false), cityPop.scrap.alt.shadow)
        assertEquals(PackShadow(7f, 7f, 0f, color("#8c081c60"), inset = false), cityPop.dock.surface.shadow)
        assertEquals(PackShadow(7f, 7f, 0f, color("#8c081c60"), inset = false), cityPop.icons.placeholder.surface.shadow)
        // The app list is the prototype's translucent blue, rgba(10,48,150,.9).
        assertEquals(color("#e60a3096"), cityPop.page.drawerFill)
        assertEquals(color("#19c3c9"), cityPop.scrap.alt.fill)
        assertEquals(color("#0b2260"), cityPop.scrap.alt.ink)
        assertEquals(color("#ff4f9a"), cityPop.scrap.tape.color)
        assertEquals(color("#ffe14d"), cityPop.palette.highlight)
        assertEquals(0f, cityPop.scrap.tiltMax - cityPop.scrap.tiltMin, 0f)
        assertEquals(PackShadow(0f, 2f, 0f, color("#bf081c60"), inset = false), cityPop.page.textShadow)
        assertEquals(color("#33ffffff"), cityPop.page.overviewScrim)
        assertFalse(cityPop.page.gutter)
        assertTrue(cityPop.type.display.run { italic && uppercase && weight == 800 && family == FontGeneric.SansSerif })
        assertEquals(listOf(color("#ff4f9a"), color("#ffe14d")), ring(cityPop))
        assertEquals(color("#ffffff"), cityPop.ink.pen)

        val custard = shippedPack("custard").pack
        assertEquals(color("#f7e9b5"), custard.page.color)
        assertEquals(PaperPattern.Lines, custard.page.pattern)
        assertEquals(26f, custard.page.patternSize, 0f)
        assertEquals(color("#26241f").withAlpha(0.07f), custard.page.patternColor)
        assertEquals(color("#ffffff"), custard.scrap.surface.fill)
        assertEquals(color("#26241f"), custard.scrap.surface.ink)
        assertEquals(0f, custard.scrap.surface.border, 0f)
        assertEquals(PackShadow(0f, 2f, 0f, color("#2e26241f"), inset = false), custard.scrap.surface.shadow)
        assertTrue(custard.scrap.surface.shadow2.run { visible && blur > 0f && y > 2f })
        assertEquals(color("#26241f"), custard.scrap.alt.fill)
        assertEquals(color("#fbf6e7"), custard.scrap.alt.ink)
        assertEquals(Dressing.Solid, custard.scrap.tape.style)
        assertEquals(color("#c9a36b"), custard.scrap.tape.color)
        assertEquals(color("#f3d96b"), custard.palette.highlight)
        assertTrue(custard.type.displayHighlighter)
        assertEquals(FontGeneric.Cursive, custard.type.display.family)
        assertTrue(custard.scrap.tiltMin < 0f && custard.scrap.tiltMax > 0f)
        assertEquals(listOf(color("#26241f"), color("#f3d96b")), ring(custard))

        val tinyMagazine = shippedPack("tiny-magazine").pack
        assertEquals(color("#1257c4"), tinyMagazine.page.color)
        assertEquals(PaperPattern.Lines, tinyMagazine.page.pattern)
        assertEquals(24f, tinyMagazine.page.patternSize, 0f)
        assertEquals(color("#ffffff"), tinyMagazine.palette.onPage)
        assertEquals(color("#ffffff"), tinyMagazine.scrap.surface.fill)
        assertEquals(color("#1a2434"), tinyMagazine.scrap.surface.ink)
        assertEquals(PackShadow(0f, 5f, 0f, color("#66081c50"), inset = false), tinyMagazine.scrap.surface.shadow)
        assertEquals(color("#c9e1f6"), tinyMagazine.scrap.alt.fill)
        assertEquals(color("#1257c4"), tinyMagazine.palette.accent)
        assertEquals(color("#bfffffff"), tinyMagazine.scrap.tape.color)
        assertEquals(CornerStyle.Edge, tinyMagazine.shape.corners)
        assertTrue(tinyMagazine.type.display.run { italic && family == FontGeneric.Serif && weight == 700 })
        assertEquals(color("#ffffff"), tinyMagazine.ink.pen)
        assertEquals(listOf(color("#ffffff"), color("#c9e1f6")), ring(tinyMagazine))

        val bujo = shippedPack("bujo").pack
        assertEquals(color("#ffffff"), bujo.page.color)
        assertEquals(PaperPattern.Dots, bujo.page.pattern)
        assertEquals(color("#c5c0b4"), bujo.page.patternColor)
        assertEquals(16f, bujo.page.patternSize, 0f)
        assertEquals(1.5f, bujo.scrap.surface.border, 0f)
        assertEquals(color("#33312c"), bujo.scrap.surface.borderColor)
        assertEquals(color("#33312c"), bujo.scrap.surface.ink)
        assertFalse(bujo.scrap.surface.shadow.visible || bujo.scrap.surface.shadow2.visible)
        assertEquals(color("#f2cfd4"), bujo.scrap.alt.fill)
        assertEquals(Dressing.Stripes, bujo.scrap.tape.style)
        assertEquals(listOf(color("#efe0ae"), color("#f7efd0")), bujo.scrap.tape.run { listOf(this.color, color2) })
        assertEquals(12f, bujo.shape.radius, 0f)
        assertEquals(CornerStyle.Round, bujo.shape.corners)
        assertEquals(color("#b86578"), bujo.ink.pen)
        assertEquals(color("#b86578"), bujo.scrap.surface.focusColor)
        assertEquals(listOf(color("#b86578"), color("#efe0ae")), ring(bujo))

        val dialUp = shippedPack("dial-up").pack
        assertEquals(color("#f7f3ee"), dialUp.page.color)
        assertEquals(PaperPattern.Grid, dialUp.page.pattern)
        assertEquals(14f, dialUp.page.patternSize, 0f)
        assertEquals(color("#4a5590").withAlpha(0.16f), dialUp.page.patternColor)
        assertEquals(color("#f7f3ee"), dialUp.scrap.surface.fill)
        assertEquals(color("#4a5590"), dialUp.scrap.surface.ink)
        assertEquals(2f, dialUp.scrap.surface.border, 0f)
        assertEquals(PackShadow(4f, 4f, 0f, color("#4a5590"), inset = false), dialUp.scrap.surface.shadow)
        assertEquals(PackTitleBar(Dressing.Solid, 20f, color("#4a5590"), color("#f7f3ee"), "×", 0f), dialUp.scrap.titleBar)
        assertEquals(Dressing.None, dialUp.scrap.tape.style)
        assertEquals(color("#efd9ec"), dialUp.scrap.alt.fill)
        assertEquals(CornerStyle.Edge, dialUp.shape.corners)
        assertEquals(listOf(FontGeneric.Monospace, FontGeneric.Monospace), dialUp.type.run { listOf(display.family, body.family) })
        assertEquals(color("#a94fa4"), dialUp.ink.pen)
        // The prototype's magenta for focus, told from the indigo outline by its weight.
        assertEquals(color("#a94fa4"), dialUp.scrap.surface.focusColor)
        assertEquals(4f, dialUp.scrap.surface.focusWidth, 0f)
        assertEquals(listOf(color("#a94fa4"), color("#4a5590")), ring(dialUp))

        // The five are scrapbooks: a row of buttons for a dock, icons in their own colors with the prototype's small shadow, a letter for a missing one.
        for (pack in listOf(cityPop, custard, tinyMagazine, bujo, dialUp)) {
            assertEquals(pack.id, DockStyle.Buttons, pack.dock.style)
            assertEquals(pack.id, IconTreatment.Original, pack.icons.treatment)
            assertEquals(pack.id, 1f, pack.icons.saturation * pack.icons.contrast, 0f)
            assertTrue(pack.id, pack.icons.shadow)
            assertEquals(pack.id, PlaceholderStyle.Letter, pack.icons.placeholder.style)
            assertEquals(pack.id, FontGeneric.Cursive, pack.type.hand.family)
        }

        // Manga is the mockup's paper, ink and spot red (features/manga-look/manga-mockup.html), ruled in hairlines with no shadow anywhere.
        val manga = shippedPack("manga").pack
        assertEquals(color("#fdfdfb"), manga.page.color)
        assertEquals(color("#fdfdfb"), manga.palette.background)
        assertEquals(color("#111113"), manga.palette.foreground)
        assertEquals(color("#d7262d"), manga.palette.accent)
        assertEquals(color("#d7262d"), manga.palette.named["spot"])
        assertEquals(PaperPattern.None, manga.page.pattern)
        for (surface in listOf(manga.scrap.surface, manga.dock.surface, manga.icons.placeholder.surface)) {
            assertEquals(1.15f, surface.border, 0f)
            assertEquals(color("#111113"), surface.borderColor)
            assertEquals(color("#fdfdfb"), surface.fill)
        }
        // The emphasised scrap is a black panel lettered in the paper.
        assertEquals(color("#111113"), manga.scrap.alt.fill)
        assertEquals(color("#fdfdfb"), manga.scrap.alt.ink)
        for (surface in listOf(manga.scrap.surface, manga.scrap.alt, manga.dock.surface, manga.icons.placeholder.surface)) {
            assertFalse(surface.shadow.visible || surface.shadow2.visible)
            assertEquals(color("#d7262d"), surface.focusColor)
        }
        assertFalse(manga.page.textShadow.visible || manga.icons.shadow)
        assertEquals(0f, manga.ring.glow, 0f)
        assertEquals(0f, manga.scrap.tiltMax - manga.scrap.tiltMin, 0f)
        assertEquals(Dressing.None, manga.scrap.tape.style)
        assertEquals(Dressing.None, manga.scrap.titleBar.style)
        assertEquals(CornerStyle.Edge, manga.shape.corners)
        assertEquals(DockStyle.Bar, manga.dock.style)
        assertEquals(IconTreatment.Original, manga.icons.treatment)
        assertEquals("fonts/DelaGothicOne-Regular.ttf", manga.type.display.file)
        assertEquals("fonts/SchibstedGrotesk.ttf", manga.type.body.file)
        assertEquals("fonts/PatrickHand-Regular.ttf", manga.type.hand.file)
        assertEquals("fonts/JetBrainsMono.ttf", manga.type.mono.file)
        // Its panels are the mockup's.
        assertEquals(PackPanels.defaults, manga.panels)
    }

    /** Only Manga is drawn in panels; every other look, Console included, has none. */
    @Test fun onlyMangaHasPanels() {
        for ((id, _) in shipped) assertEquals(id, id == "manga", shippedPack(id).pack.panels != null)
        assertNull(ConsolePack.pack.panels)
        assertNull(ConsolePack.tokens.panels)
    }

    /** The fonts Manga carries are the other looks' own files, byte for byte: nothing new was downloaded for it. */
    @Test fun mangasFontsAreTheOtherLooks() {
        val from = mapOf("DelaGothicOne-Regular.ttf" to "riso", "PatrickHand-Regular.ttf" to "bujo", "SchibstedGrotesk.ttf" to "riso", "JetBrainsMono.ttf" to "riso")
        val carried = moduleFile("src/main/assets/packs/manga/fonts").listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(from.keys.sorted(), carried)
        for ((file, look) in from) {
            val bytes = moduleFile("src/main/assets/packs/manga/fonts/$file").readBytes()
            assertTrue(file, bytes.contentEquals(moduleFile("src/main/assets/packs/$look/fonts/$file").readBytes()))
        }
    }

    @Test fun thePickerSaysWhatIsWrong() {
        val options = PackRegistry.options(listOf("a", "b", "c")) { id ->
            when (id) {
                "a" -> packOf("[pack]\nformat = 1\nname = \"Sparse\"\n")
                "b" -> packOf(consoleManifest(mapOf("shape.radius" to 999)))
                else -> packOf("")
            }
        }
        assertEquals(listOf("Console", "Sparse", "Console", "c"), options.map { it.name })
        assertNull(options[0].problem)
        // Leaving tokens out isn't a fault.
        assertNull(options[1].problem)
        assertNotNull(options[2].problem)
        assertEquals(options[3].load!!.error, options[3].problem)
    }

    @Test fun readsAFileNoFurtherThanItsLimit() {
        val source = MapPackSource(mapOf("a.ttf" to ByteArray(100) { 1 }, "empty.ttf" to ByteArray(0)))
        assertEquals(100, readCapped(source, "a.ttf", 100)!!.size)
        assertNull(readCapped(source, "a.ttf", 99))
        assertNull(readCapped(source, "empty.ttf", 100))
        assertNull(readCapped(source, "missing.ttf", 100))
        assertNull(readCapped(source, "", 100))
        assertNull(readCapped(source, "../a.ttf", 100))
    }

    /** The source said the file was small when the loader asked; the read still stops at the limit. */
    @Test fun aSourceThatLiesAboutSizeIsStillCapped() {
        var read = 0L
        val endless =
            object : PackSource {
                override fun stat(path: String) = PackEntry.File(10)

                override fun open(path: String): InputStream =
                    object : InputStream() {
                        override fun read(): Int {
                            read++
                            return 0
                        }

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int {
                            read += len
                            return len
                        }
                    }
            }
        assertNull(readCapped(endless, "big.ttf", 1024 * 1024))
        assertTrue("read $read bytes", read <= 1024 * 1024 + 64 * 1024)
        val throwing =
            object : PackSource {
                override fun stat(path: String) = PackEntry.File(10)

                override fun open(path: String): InputStream = error("gone")
            }
        assertNull(readCapped(throwing, "x.ttf", 10))
        val failing =
            object : PackSource {
                override fun stat(path: String) = PackEntry.File(10)

                override fun open(path: String): InputStream =
                    object : ByteArrayInputStream(ByteArray(4)) {
                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = throw java.io.IOException("torn")
                    }
            }
        assertNull(readCapped(failing, "x.ttf", 10))
    }

    /**
     * A shadow a shipped pack gives an offset or a blur has to show. Colors are `#aarrggbb`: written the
     * way CSS has it, `#00000040`, a shadow is fully clear and never drawn.
     */
    @Test fun everyShadowAShippedPackDeclaresIsVisible() {
        var declared = 0
        for ((id, _) in shipped) {
            val pack = shippedPack(id).pack
            val surfaces = mapOf("scrap" to pack.scrap.surface, "scrap.alt" to pack.scrap.alt, "dock" to pack.dock.surface, "icons.placeholder" to pack.icons.placeholder.surface)
            val shadows = surfaces.flatMap { (name, s) -> listOf("$name.shadow" to s.shadow, "$name.shadow2" to s.shadow2) } + ("page.text_shadow" to pack.page.textShadow)
            for ((name, shadow) in shadows) {
                if (shadow.x == 0f && shadow.y == 0f && shadow.blur == 0f) continue
                declared++
                assertTrue("$id $name is ${shadow.color}, which has no alpha", shadow.color.argb ushr 24 != 0)
                assertTrue("$id $name", shadow.visible)
            }
        }
        // Riso's three (its emphasised scrap casts the pink one too), Instrument's six and 1-bit's three (its alt scrap casts the black one now); City Pop's five (scrap, alt, dock, placeholder, text),
        // Custard's eight (its placeholder carries the scrap's two), Tiny Magazine's four (its placeholder casts the clipping's), Bujo's none and Dial-Up's three.
        assertEquals(32, declared)
        // No color anywhere in a shipped manifest is written with eight digits and a clear alpha.
        for ((id, _) in shipped) {
            val clear = Regex("\"#00[0-9a-fA-F]{6}\"").findAll(moduleFile("src/main/assets/packs/$id/pack.toml").readText()).map { it.value }.toList()
            assertEquals("$id", emptyList<String>(), clear)
        }
    }

    @Test fun aScrapsTiltIsFixedAndInRange() {
        val keys = (0 until 200).map { "widget-$it.owl" } + listOf("", "app:com.example/.Main", "folder:1")
        for (key in keys) {
            val tilt = scrapTilt(key, -1.3f, 1.3f)
            assertTrue("$key: $tilt", tilt in -1.3f..1.3f)
            assertEquals(tilt, scrapTilt(key, -1.3f, 1.3f), 0f)
            // Written the wrong way round, the range is the same.
            assertEquals(tilt, scrapTilt(key, 1.3f, -1.3f), 0f)
            // Console doesn't tilt, and a pack with one angle uses it.
            assertEquals(0f, scrapTilt(key, 0f, 0f), 0f)
            assertEquals(2f, scrapTilt(key, 2f, 2f), 0f)
        }
        // Not all the same way.
        val tilts = keys.map { scrapTilt(it, -1.3f, 1.3f) }
        assertTrue(tilts.any { it < -0.3f } && tilts.any { it > 0.3f })
    }

    /** PRD AC-20: one image per display, the cover's folded and the inner's unfolded. */
    @Test fun picksTheWallpaperForTheDisplay() {
        assertEquals("cover", wallpaperSide(360f, 800f, hasCover = true, hasInner = true))
        assertEquals("cover", wallpaperSide(800f, 360f, hasCover = true, hasInner = true))
        assertEquals("inner", wallpaperSide(700f, 800f, hasCover = true, hasInner = true))
        assertEquals("inner", wallpaperSide(800f, 700f, hasCover = true, hasInner = true))
        // A pack with only one shows that one on both, whole, rather than nothing.
        assertEquals("inner", wallpaperSide(360f, 800f, hasCover = false, hasInner = true))
        assertEquals("cover", wallpaperSide(700f, 800f, hasCover = true, hasInner = false))
        assertNull(wallpaperSide(700f, 800f, hasCover = false, hasInner = false))
    }

    /** The image fills the display once: scaled as a whole and cut evenly at two sides, never repeated. */
    @Test fun aWallpaperCoversTheDisplayOnce() {
        // Same shape: all of it.
        assertEquals(listOf(0, 0, 1000, 2000), coverCrop(1000, 2000, 500, 1000))
        // Wider than the display: the sides go.
        assertEquals(listOf(500, 0, 1000, 2000), coverCrop(2000, 2000, 500, 1000))
        // Taller: top and bottom go.
        assertEquals(listOf(0, 500, 1000, 1000), coverCrop(1000, 2000, 1000, 1000))
        for ((sw, sh, dw, dh) in listOf(listOf(1920, 1080, 1812, 2176), listOf(904, 2316, 904, 2316), listOf(3, 7, 1000, 10), listOf(7, 3, 10, 1000))) {
            val (x, y, w, h) = coverCrop(sw, sh, dw, dh)!!
            assertTrue(x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= sw && y + h <= sh)
            // Evenly: what is cut on one side is cut on the other.
            assertTrue(kotlin.math.abs(x - (sw - w - x)) <= 1 && kotlin.math.abs(y - (sh - h - y)) <= 1)
        }
        assertNull(coverCrop(0, 10, 10, 10))
        assertNull(coverCrop(10, 10, 0, 10))
    }
}
