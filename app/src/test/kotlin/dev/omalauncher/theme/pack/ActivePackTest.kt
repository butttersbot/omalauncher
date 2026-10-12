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

    private val shipped = listOf("riso" to "Riso", "instrument" to "Instrument", "one-bit" to "1-bit")

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
            // No font or wallpaper file is shipped, so nothing names one.
            val type = load.pack.type
            assertEquals("$id fonts", listOf("", "", "", ""), listOf(type.display, type.body, type.hand, type.mono).map { it.file })
            assertEquals("", load.pack.page.wallpaperCover + load.pack.page.wallpaperInner)
            // Each draws its own page, and none is Console under another name.
            assertEquals(PageKind.Paper, load.pack.page.kind)
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
        assertEquals(0f, oneBit.ring.glow, 0f)
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

    /** PRD 6.5: tilt is visual only. Whatever the angle, a scrap is touched on its cell's upright rectangle. */
    @Test fun theHitRectangleIsTheCellWhateverTheTilt() {
        val cell = listOf(40f, 100f, 240f, 220f)
        for (tilt in listOf(-12f, -3.5f, -0.25f, 0f, 0.25f, 1.3f, 7f, 12f, 45f, 90f, 180f)) {
            val box = ScrapBox(cell[0], cell[1], cell[2], cell[3], tilt)
            assertEquals("tilt $tilt", cell, box.hit)
            // Corners of the cell, in and out.
            assertTrue(box.hits(40f, 100f))
            assertTrue(box.hits(239.9f, 219.9f))
            assertFalse(box.hits(39.9f, 100f))
            assertFalse(box.hits(240f, 220f))
            assertFalse(box.hits(140f, 99.9f))
            // What is drawn does turn: the middle stays put and the corners move, unless it is upright.
            val drawn = box.drawn()
            assertEquals(140f, drawn.map { it.first }.average().toFloat(), 0.01f)
            assertEquals(160f, drawn.map { it.second }.average().toFloat(), 0.01f)
            val moved = drawn[0] != (40f to 100f)
            assertEquals("tilt $tilt", tilt != 0f, moved)
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
