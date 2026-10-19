package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class PackLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val console = ConsolePack.tokens.values

    private fun kinds(load: PackLoad) = load.issues.associate { it.token to it.kind }

    /** A value for [token] that is allowed and isn't Console's. */
    private fun other(token: PackToken): Any =
        when (val kind = token.kind) {
            TokenKind.Paint -> if (token.key.startsWith("palette.")) "#80112233" else "extra.spot@0.25"
            is TokenKind.Number -> if (console[token.key] == kind.max) kind.min else kind.max
            is TokenKind.Whole -> if (console[token.key] == kind.max) kind.min else kind.max
            is TokenKind.Choice -> kind.options.first { it != token.console }
            is TokenKind.Text -> "×"
            TokenKind.Flag -> !(token.console as Boolean)
            is TokenKind.Asset -> if (kind.type == AssetType.Font) "fonts/${token.key}.otf" else "paper/${token.key}.webp"
        }

    @Test fun roundTripsAFullPack() {
        val values = linkedMapOf<String, Any>("pack.format" to 1, "pack.name" to "Every Token", "palette.extra.spot" to "#ffff3d8b")
        for (token in PackSchema.tokens) values[token.key] = other(token)
        val files = values.values.filterIsInstance<String>().filter { it.startsWith("fonts/") || it.startsWith("paper/") }.toTypedArray()

        val first = loadPack(PackManifest.write(values), *files)
        assertNull(first.error)
        assertEquals(emptyList<PackIssue>(), first.issues)
        for (token in PackSchema.tokens) assertNotEquals(token.key, console[token.key], first.tokens.values[token.key])
        assertEquals("Every Token", first.pack.name)
        assertEquals("test", first.pack.id)
        assertEquals("#40ff3d8b", first.pack.scrap.surface.fill.toString())
        assertEquals(mapOf("spot" to PackColor(0xFFFF3D8B.toInt())), first.pack.palette.named)

        val second = loadPack(PackManifest.write(first.tokens.manifest()), *files)
        assertEquals(emptyList<PackIssue>(), second.issues)
        assertEquals(first.tokens, second.tokens)
        assertEquals(first.pack, second.pack)
    }

    @Test fun loadsAPackFromAFolder() {
        val load = PackLoader.load(DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up")), "dial-up")
        assertNull(load.error)
        assertTrue(load.issues.toString(), load.issues.all { it.kind == PackIssue.Kind.Missing })
        val pack = load.pack
        assertEquals("Dial-Up", pack.name)
        assertTrue(pack.palette.isLight)
        assertEquals("#fff7f3ee", pack.palette.background.toString())
        assertEquals("#b84a5590", pack.palette.muted.toString())
        assertEquals(pack.palette.highlight, pack.palette.alt)
        assertEquals("fonts/pixelify.ttf", pack.type.display.file)
        assertEquals(600, pack.type.display.weight)
        assertEquals("fonts/pixelify.ttf", pack.type.body.file)
        assertEquals(CornerStyle.Edge, pack.shape.corners)
        assertEquals(0f, pack.shape.cornerScale(1f), 0f)
        assertEquals(PackShadow(4f, 4f, 0f, pack.palette.foreground, false), pack.scrap.surface.shadow)
        assertTrue(pack.scrap.surface.shadow.visible)
        assertEquals(PackTitleBar(Dressing.Solid, 20f, pack.palette.foreground, pack.palette.background, "×", 0f), pack.scrap.titleBar)
        assertEquals(-0.5f, pack.scrap.tiltMin, 0f)
        assertEquals(PaperPattern.Grid, pack.page.pattern)
        assertEquals("paper/cover.png", pack.page.wallpaperCover)
        assertEquals("#ff33ff66", pack.ring.listening.color2.toString())
        assertEquals(DockStyle.Buttons, pack.dock.style)
        // What it leaves out is Console's, written against this pack's own palette.
        assertEquals("", pack.page.wallpaperInner)
        assertEquals(ConsolePack.pack.type.hand, pack.type.hand)
        assertEquals(ConsolePack.pack.type.sizes, pack.type.sizes)
        assertEquals(pack.palette.background.withAlpha(0.88f), pack.dock.surface.fill)
        assertEquals(ConsolePack.pack.ring.working.periodMs, pack.ring.working.periodMs)
    }

    /** UP-10: a missing token is Console's for that token only. */
    @Test fun aMissingTokenFallsBackAlone() {
        for (missing in listOf("shape.radius", "palette.accent", "type.hand.family", "scrap.tape.style", "ring.done.period", "type.display.italic")) {
            val full = linkedMapOf<String, Any>("pack.format" to 1)
            for (token in PackSchema.tokens) full[token.key] = other(token)
            full["palette.extra.spot"] = "#ffff3d8b"
            val files = full.values.filterIsInstance<String>().filter { it.startsWith("fonts/") || it.startsWith("paper/") }.toTypedArray()
            val load = loadPack(PackManifest.write(full - missing), *files)
            assertNull(load.error)
            assertEquals(listOf(PackIssue(missing, PackIssue.Kind.Missing, "not set")), load.issues)
            assertEquals(listOf(missing), load.fallbacks.map { it.token })
            for (token in PackSchema.tokens) {
                if (token.key == missing) {
                    assertEquals(console[missing], load.tokens.values[missing])
                } else {
                    assertNotEquals(token.key, console[token.key], load.tokens.values[token.key])
                }
            }
        }
    }

    @Test fun aMissingFontOrWallpaperFallsBackAlone() {
        val manifest =
            consoleManifest(
                mapOf(
                    "type.display.file" to "fonts/gone.ttf",
                    "type.display.family" to "serif",
                    "type.hand.file" to "fonts/here.ttf",
                    "page.wallpaper_cover" to "paper/gone.png",
                    "page.wallpaper_inner" to "paper/here.jpg",
                ),
            )
        val load = loadPack(manifest, "fonts/here.ttf", "paper/here.jpg")
        assertNull(load.error)
        assertEquals(mapOf("type.display.file" to PackIssue.Kind.FileMissing, "page.wallpaper_cover" to PackIssue.Kind.FileMissing), kinds(load))
        assertEquals(2, load.fallbacks.size)
        assertEquals("", load.pack.type.display.file)
        // The role keeps what else the pack said about it.
        assertEquals(FontGeneric.Serif, load.pack.type.display.family)
        assertEquals("fonts/here.ttf", load.pack.type.hand.file)
        assertEquals("", load.pack.page.wallpaperCover)
        assertEquals("paper/here.jpg", load.pack.page.wallpaperInner)
    }

    @Test fun refusesFilesTooBigEmptyOrOfTheWrongKind() {
        val manifest =
            consoleManifest(
                mapOf(
                    "type.display.file" to "big.ttf",
                    "type.body.file" to "ok.otf",
                    "type.hand.file" to "empty.ttf",
                    "type.mono.file" to "script.sh",
                    "page.wallpaper_cover" to "big.png",
                    "page.wallpaper_inner" to "font.ttf",
                ),
            )
        val sizes =
            mapOf(
                "big.ttf" to AssetType.Font.maxBytes + 1,
                "ok.otf" to AssetType.Font.maxBytes,
                "empty.ttf" to 0L,
                "script.sh" to 10L,
                "big.png" to AssetType.Image.maxBytes + 1,
                "font.ttf" to 10L,
            )
        val load = PackLoader.load(MapPackSource(mapOf(PackLoader.MANIFEST to manifest.toByteArray()), sizes), "test")
        assertEquals(
            mapOf(
                "type.display.file" to PackIssue.Kind.FileTooLarge,
                "type.hand.file" to PackIssue.Kind.FileMissing,
                "type.mono.file" to PackIssue.Kind.Invalid,
                "page.wallpaper_cover" to PackIssue.Kind.FileTooLarge,
                "page.wallpaper_inner" to PackIssue.Kind.Invalid,
            ),
            kinds(load),
        )
        assertEquals("ok.otf", load.pack.type.body.file)
        assertEquals(listOf("", "", ""), listOf(load.pack.type.display.file, load.pack.type.hand.file, load.pack.type.mono.file))
    }

    @Test fun refusesPathsThatLeaveThePack() {
        val outside =
            listOf(
                "../secret.ttf",
                "fonts/../../secret.ttf",
                "/etc/secret.ttf",
                "/sdcard/secret.ttf",
                "~/secret.ttf",
                "C:\\secret.ttf",
                "fonts\\..\\secret.ttf",
                "file:///etc/secret.ttf",
                "fonts//secret.ttf",
                "./secret.ttf",
                "fonts/./secret.ttf",
                "fonts/..",
                "..",
                "fonts/secret.ttf/",
            )
        for (path in outside) {
            val source = packOf(consoleManifest(mapOf("type.display.file" to path, "page.wallpaper_cover" to path.replace(".ttf", ".png"))))
            val load = PackLoader.load(source, "test")
            assertNull(load.error)
            assertEquals(path, mapOf("type.display.file" to PackIssue.Kind.FileOutside, "page.wallpaper_cover" to PackIssue.Kind.FileOutside), kinds(load))
            assertEquals("", load.pack.type.display.file)
            assertEquals("", load.pack.page.wallpaperCover)
            // The loader doesn't so much as ask the source about such a path.
            assertEquals(path, listOf(PackLoader.MANIFEST), source.asked.distinct())
        }
        // A control character smuggled in by an escape is no better.
        val load = loadPack("[pack]\nformat = 1\n[type.display]\nfile = \"a\\u0000.ttf\"\n", "a")
        assertEquals(PackIssue.Kind.FileOutside, kinds(load)["type.display.file"])

        // A path that is only too long says so, and isn't looked for either.
        val long = packOf(consoleManifest(mapOf("type.display.file" to "a/".repeat(120) + "font.ttf", "type.body.file" to "b".repeat(196) + ".ttf")), "b".repeat(196) + ".ttf")
        val tooLong = PackLoader.load(long, "test")
        assertEquals(listOf(PackIssue("type.display.file", PackIssue.Kind.Invalid, "a path of more than 200 characters")), tooLong.issues)
        assertEquals("", tooLong.pack.type.display.file)
        assertEquals(200, tooLong.pack.type.body.file.length)
        assertTrue(long.asked.none { it.startsWith("a/") })
    }

    /** `radius = NaN` is a slip to show the author; it mustn't read as a pack that left radius alone. */
    @Test fun aValueTheParserCantReadIsInvalidNotMissing() {
        val load =
            loadPack(
                "[pack]\nformat = 1\nname = [\"a\"]\n[shape]\nradius = NaN\ngap = 0x10\ncorners = round\nsquircle = [1]\n" +
                    "[scrap]\nborder = 1_000\nfill = \"unclosed\n[type.display]\nweight = 4 00\nweight = 700\n[palette.extra]\nspot = {}\n",
            )
        assertNull(load.error)
        val slips = load.issues.filter { it.kind != PackIssue.Kind.Missing }
        assertEquals(
            mapOf(
                "pack.name" to PackIssue.Kind.Invalid,
                "shape.radius" to PackIssue.Kind.Invalid,
                "shape.gap" to PackIssue.Kind.Invalid,
                "shape.corners" to PackIssue.Kind.Invalid,
                "scrap.border" to PackIssue.Kind.Invalid,
                "scrap.fill" to PackIssue.Kind.Invalid,
                "shape.squircle" to PackIssue.Kind.Ignored,
                "palette.extra.spot" to PackIssue.Kind.Ignored,
            ),
            slips.associate { it.token to it.kind },
        )
        assertTrue(slips.toString(), slips.none { it.token.isEmpty() })
        assertEquals("line 5: not a value this build can read", slips.first { it.token == "shape.radius" }.detail)
        assertEquals(ConsolePack.pack.shape, load.pack.shape)
        // Set again readably, the key has the value that could be read.
        assertEquals(700, load.pack.type.display.weight)
        assertTrue(load.broken)
    }

    @Test fun tellsABrokenPackFromASparseOne() {
        assertTrue(!PackLoader.load(DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up")), "dial-up").broken)
        assertTrue(!loadPack("[pack]\nformat = 3\n[hologram]\nshimmer = 1\n").broken)
        assertTrue(!loadPack(consoleManifest()).broken)
        assertTrue(loadPack("").broken)
        assertTrue(loadPack(consoleManifest(mapOf("shape.radius" to 400))).broken)
        assertTrue(loadPack(consoleManifest(mapOf("shape.radius" to "big"))).broken)
        assertTrue(loadPack(consoleManifest(mapOf("type.hand.file" to "gone.ttf"))).broken)
        assertTrue(loadPack(consoleManifest(mapOf("type.hand.file" to "../gone.ttf"))).broken)
    }

    @Test fun saysWhenAnAlphaIsOutOfRange() {
        val load = loadPack(consoleManifest(mapOf("scrap.fill" to "accent@-1", "scrap.ink" to "#ffffff@7", "dock.fill" to "accent@1", "dock.ink" to "accent@0")))
        assertNull(load.error)
        assertEquals(mapOf("scrap.fill" to PackIssue.Kind.Clamped, "scrap.ink" to PackIssue.Kind.Clamped), kinds(load))
        assertEquals(emptyList<PackIssue>(), load.fallbacks)
        assertEquals("#007aa2f7", load.pack.scrap.surface.fill.toString())
        assertEquals("#ffffffff", load.pack.scrap.surface.ink.toString())
        assertTrue(load.issues.first().detail, load.issues.first().detail.contains("accent@0.0"))
    }

    @Test fun refusesTextThatWouldTurnTheScreenAround() {
        for (control in listOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')) {
            val load = loadPack(consoleManifest(mapOf("pack.name" to "${control}emosewa", "scrap.title_bar.glyph" to "$control×")))
            assertEquals(mapOf("pack.name" to PackIssue.Kind.Invalid, "scrap.title_bar.glyph" to PackIssue.Kind.Invalid), kinds(load))
            assertEquals("test", load.pack.name)
            assertEquals("", load.pack.scrap.titleBar.glyph)
        }
        // Text that merely is right-to-left is fine.
        assertEquals("שלום", loadPack(consoleManifest(mapOf("pack.name" to "שלום"))).pack.name)
    }

    @Test fun saysWhatIsWrongWithTheFormat() {
        for (bad in listOf("1.0", "\"1\"", "2147483648", "0", "-1", "1e0", "true", "0x1", "[1]", "one")) {
            val error = loadPack("[pack]\nformat = $bad\n[shape]\nradius = 4\n").error
            assertNotNull(bad, error)
            assertTrue("$bad: $error", error!!.contains("whole number from 1 up"))
        }
        for (absent in listOf("[shape]\nradius = 4\n", "[pack]\nname = \"x\"\n", "format = 1\n")) {
            assertTrue(absent, loadPack(absent).error!!.contains("doesn't say which pack format"))
        }
        assertNull(loadPack("[pack]\nformat = 2147483647\n").error)
    }

    @Test fun aFolderNeverReadsThroughALinkOutOfIt() {
        val outside = tmp.newFolder("outside")
        File(outside, "secret.ttf").writeText("secret")
        File(outside, "pack.toml").writeText(consoleManifest(mapOf("pack.name" to "Elsewhere")))
        val pack = tmp.newFolder("pack")
        File(pack, "fonts").mkdir()
        File(pack, "fonts/real.ttf").writeText("font")
        Files.createSymbolicLink(File(pack, "fonts/link.ttf").toPath(), File(outside, "secret.ttf").toPath())
        Files.createSymbolicLink(File(pack, "fonts/inside.ttf").toPath(), File(pack, "fonts/real.ttf").toPath())
        Files.createSymbolicLink(File(pack, "away").toPath(), outside.toPath())
        File(pack, "pack.toml").writeText(
            consoleManifest(
                mapOf(
                    "type.display.file" to "fonts/link.ttf",
                    "type.body.file" to "away/secret.ttf",
                    "type.hand.file" to "fonts/inside.ttf",
                    "type.mono.file" to "fonts/real.ttf",
                ),
            ),
        )
        val source = DirectoryPackSource(pack)
        val load = PackLoader.load(source, "pack")
        assertNull(load.error)
        assertEquals(mapOf("type.display.file" to PackIssue.Kind.FileOutside, "type.body.file" to PackIssue.Kind.FileOutside), kinds(load))
        assertEquals("fonts/inside.ttf", load.pack.type.hand.file)
        assertEquals("fonts/real.ttf", load.pack.type.mono.file)
        for (path in listOf("fonts/link.ttf", "away/secret.ttf", "../outside/secret.ttf", "away", "fonts", "")) {
            assertNotEquals(path, PackEntry.File::class, source.stat(path)::class)
            assertNull(path, source.open(path))
        }
        assertEquals("font", source.open("fonts/inside.ttf")!!.use { it.readBytes().decodeToString() })

        // A manifest that is itself a link out of the pack isn't read either.
        File(pack, "pack.toml").delete()
        Files.createSymbolicLink(File(pack, "pack.toml").toPath(), File(outside, "pack.toml").toPath())
        val linked = PackLoader.load(DirectoryPackSource(pack), "pack")
        assertNotNull(linked.error)
        assertEquals(ConsolePack.pack, linked.pack)

        // Nor is a pack folder that isn't there.
        assertNotNull(PackLoader.load(DirectoryPackSource(File(pack, "nowhere")), "nowhere").error)
    }

    @Test fun clampsWhatCouldHurt() {
        val manifest =
            consoleManifest(
                mapOf(
                    "shape.radius" to 1e9,
                    "shape.gap" to -5,
                    "scrap.border" to 400,
                    "scrap.tilt_min" to -90,
                    "scrap.tilt_max" to 3600,
                    "type.display.scale" to 1000,
                    "type.body.scale" to 0,
                    "type.size.body" to 9999,
                    "type.size.small" to 0.001,
                    "type.hand.weight" to 99999,
                    "type.mono.weight" to -1,
                    "ring.width" to 500,
                    "ring.working.period" to 0,
                    "ring.done.pulses" to Long.MAX_VALUE,
                    "scrap.shadow.blur" to 1e300,
                    "scrap.tape.opacity" to 7,
                    "ink.width" to 1e12,
                ),
            )
        val load = loadPack(manifest)
        assertNull(load.error)
        assertTrue(load.issues.toString(), load.issues.all { it.kind == PackIssue.Kind.Clamped })
        assertEquals(17, load.issues.size)
        assertEquals(emptyList<PackIssue>(), load.fallbacks)
        val pack = load.pack
        assertEquals(48f, pack.shape.radius, 0f)
        assertEquals(0f, pack.shape.gap, 0f)
        assertEquals(8f, pack.scrap.surface.border, 0f)
        assertEquals(-12f, pack.scrap.tiltMin, 0f)
        assertEquals(12f, pack.scrap.tiltMax, 0f)
        assertEquals(2f, pack.type.display.scale, 0f)
        assertEquals(0.5f, pack.type.body.scale, 0f)
        assertEquals(40f, pack.type.sizes.body, 0f)
        assertEquals(8f, pack.type.sizes.small, 0f)
        assertEquals(900, pack.type.hand.weight)
        assertEquals(100, pack.type.mono.weight)
        assertEquals(8f, pack.ring.width, 0f)
        assertEquals(500, pack.ring.working.periodMs)
        assertEquals(10, pack.ring.done.pulses)
        assertEquals(48f, pack.scrap.surface.shadow.blur, 0f)
        assertEquals(1f, pack.scrap.tape.opacity, 0f)
        assertEquals(16f, pack.ink.width, 0f)
    }

    /** Whatever numbers a pack gives, every number in the pack that comes out is inside its token's range. */
    @Test fun everyNumberStaysInRange() {
        for (huge in listOf<Any>(1e300, -1e300, Long.MAX_VALUE, Long.MIN_VALUE, 1e-300, 0)) {
            val values = PackSchema.tokens.filter { it.kind is TokenKind.Number || it.kind is TokenKind.Whole }.associate { it.key to huge }
            val load = loadPack(consoleManifest(values))
            assertNull(load.error)
            assertEquals(emptyList<PackIssue>(), load.fallbacks)
            for (token in PackSchema.tokens) {
                val value = load.tokens.values.getValue(token.key)
                when (val kind = token.kind) {
                    is TokenKind.Number -> assertTrue(token.key, (value as Float) in kind.min..kind.max)
                    is TokenKind.Whole -> assertTrue(token.key, (value as Int) in kind.min..kind.max)
                    else -> {}
                }
            }
            assertTrue(load.pack.scrap.tiltMin <= load.pack.scrap.tiltMax)
        }
    }

    @Test fun putsAReversedTiltRangeRight() {
        val pack = loadPack(consoleManifest(mapOf("scrap.tilt_min" to 3, "scrap.tilt_max" to -2))).pack
        assertEquals(-2f, pack.scrap.tiltMin, 0f)
        assertEquals(3f, pack.scrap.tiltMax, 0f)
    }

    @Test fun aValueOfTheWrongTypeFallsBackAlone() {
        val wrong =
            mapOf<String, Any>(
                "shape.radius" to "16",
                "shape.gap" to true,
                "scrap.border" to "wide",
                "type.display.weight" to "bold",
                "type.display.italic" to "true",
                "type.display.uppercase" to 1,
                "type.display.family" to "Comic Sans",
                "type.body.family" to 7,
                "type.display.file" to 12,
                "palette.accent" to 0xff0000,
                "palette.red" to "crimson",
                "palette.green" to "#12345",
                "scrap.fill" to "glass@lots",
                "scrap.ink" to "extra.nothing",
                "scrap.title_bar.glyph" to "far too long",
                "scrap.tape.style" to false,
                "ring.listening.period" to "fast",
                "page.kind" to "",
            )
        val load = loadPack(consoleManifest(wrong + mapOf("shape.corners" to "ROUND")))
        assertNull(load.error)
        assertEquals(wrong.keys.associateWith { PackIssue.Kind.Invalid }, kinds(load))
        // Console but for the one value that was fine, which is read whatever its case.
        assertEquals(ConsolePack.pack.copy(id = "test", shape = ConsolePack.pack.shape.copy(corners = CornerStyle.Round)), load.pack)

        // Numbers that aren't numbers.
        val odd = loadPack("[pack]\nformat = 1\n[shape]\nradius = nan\ngap = inf\n[scrap]\nborder = 1e999\n")
        assertNull(odd.error)
        assertEquals(ConsolePack.pack.shape, odd.pack.shape)
        assertEquals(2f, odd.pack.scrap.surface.border, 0f)
        assertEquals(PackIssue.Kind.Invalid, kinds(odd)["scrap.border"])
        assertEquals(PackIssue.Kind.Invalid, kinds(odd)["shape.radius"])
        assertEquals(PackIssue.Kind.Invalid, kinds(odd)["shape.gap"])
    }

    @Test fun ignoresKeysItDoesntKnow() {
        val manifest =
            consoleManifest(mapOf("pack.format" to 7, "shape.radius" to 4)) +
                "\n[hologram]\nshimmer = 0.4\nlayers = [1, 2, 3]\n[shape]\nsquircle = true\n[pack]\nauthor = \"someone\"\n[ring.listening.aurora]\nbands = 3\n"
        val load = loadPack(manifest)
        assertNull(load.error)
        assertEquals(emptyList<PackIssue>(), load.fallbacks)
        assertEquals(
            mapOf(
                "pack.format" to PackIssue.Kind.Newer,
                "hologram.layers" to PackIssue.Kind.Ignored,
                "hologram.shimmer" to PackIssue.Kind.Ignored,
                "shape.squircle" to PackIssue.Kind.Ignored,
                "pack.author" to PackIssue.Kind.Ignored,
                "ring.listening.aurora.bands" to PackIssue.Kind.Ignored,
            ),
            kinds(load),
        )
        assertEquals(7, load.tokens.format)
        assertEquals(ConsolePack.pack.copy(id = "test", shape = ConsolePack.pack.shape.copy(radius = 4f)), load.pack)
    }

    @Test fun aManifestItCantUseIsConsoleWithAnError() {
        val big = "# " + "x".repeat(PackLoader.MAX_MANIFEST)
        val unusable =
            mapOf(
                "empty" to "",
                "blank" to "  \n\n\t\n",
                "comments" to "# nothing\n# here\n",
                "no format" to "[shape]\nradius = 4\n",
                "format as text" to "[pack]\nformat = \"1\"\n",
                "format zero" to "[pack]\nformat = 0\n",
                "format negative" to "[pack]\nformat = -1\n",
                "format fraction" to "[pack]\nformat = 1.5\n",
                "format huge" to "[pack]\nformat = 99999999999\n",
                "not toml" to "{\"pack\": {\"format\": 1}}",
                "html" to "<html><body>404</body></html>",
                "nul" to "[pack]\nformat = 1\n\u0000",
                "too big" to big,
            )
        for ((what, text) in unusable) {
            val load = loadPack(text)
            assertNotNull(what, load.error)
            assertTrue(what, load.error!!.isNotBlank())
            assertEquals(what, ConsolePack.pack, load.pack)
            assertEquals(what, ConsolePack.tokens, load.tokens)
        }
        // Bytes that aren't UTF-8, a pack with no manifest, and one whose manifest can't be opened.
        val binary = MapPackSource(mapOf(PackLoader.MANIFEST to byteArrayOf(0x5b, 0xff.toByte(), 0xfe.toByte(), 0x00, 0x80.toByte())))
        for (source in listOf(binary, MapPackSource(), MapPackSource(sizes = mapOf(PackLoader.MANIFEST to 10L)))) {
            val load = PackLoader.load(source, "test")
            assertNotNull(load.error)
            assertEquals(ConsolePack.pack, load.pack)
        }
        // One right at the limit is read.
        val atLimit = "[pack]\nformat = 1\n#" + "x".repeat(PackLoader.MAX_MANIFEST - 19)
        assertEquals(PackLoader.MAX_MANIFEST, atLimit.length)
        assertNull(loadPack(atLimit).error)
    }

    @Test fun neverThrowsWhateverTheSourceDoes() {
        val manifest = consoleManifest(mapOf("type.display.file" to "a.ttf")).toByteArray()
        val liar =
            object : PackSource {
                // Claims a small manifest, then never stops handing out bytes.
                override fun stat(path: String) = PackEntry.File(1)

                override fun open(path: String): InputStream =
                    object : InputStream() {
                        override fun read() = '#'.code
                    }
            }
        assertNotNull(PackLoader.load(liar, "test").error)

        val throwsOnStat =
            object : PackSource {
                override fun stat(path: String): PackEntry = throw IllegalStateException("boom")

                override fun open(path: String): InputStream? = null
            }
        assertEquals(ConsolePack.pack, PackLoader.load(throwsOnStat, "test").pack)
        assertTrue(PackLoader.load(throwsOnStat, "test").error!!.contains("boom"))

        val throwsOnFiles =
            object : PackSource {
                override fun stat(path: String): PackEntry = if (path == PackLoader.MANIFEST) PackEntry.File(manifest.size.toLong()) else throw java.io.IOException("gone")

                override fun open(path: String): InputStream = manifest.inputStream()
            }
        val lost = PackLoader.load(throwsOnFiles, "test")
        assertNull(lost.error)
        assertEquals(mapOf("type.display.file" to PackIssue.Kind.FileMissing), kinds(lost))

        for (id in listOf("", "..", "../console", "Riso", "a b", "a/b", "-a", "a".repeat(41))) {
            assertNotNull(id, PackLoader.load(packOf(consoleManifest()), id).error)
        }
    }

    @Test fun neverThrowsOnNoise() {
        val random = java.util.Random(11)
        val keys = PackSchema.tokens.map { it.key }
        val scraps = listOf("\"#ff0000\"", "\"accent@2\"", "1e99", "-0", "true", "\"../x.ttf\"", "\"\"", "[1]", "\"\\u0000\"", "nan", "99999999999999999999", "'x'", "\"glass\"", "\"extra.a\"")
        repeat(300) {
            val text = StringBuilder("[pack]\nformat = ${random.nextInt(3)}\n")
            repeat(random.nextInt(60)) {
                val key = keys[random.nextInt(keys.size)]
                text.append('[').append(key.substringBeforeLast('.')).append("]\n")
                text.append(key.substringAfterLast('.')).append(" = ").append(scraps[random.nextInt(scraps.size)]).append('\n')
            }
            val load = loadPack(text.toString())
            // Whatever came of it, there is a whole pack, and each fallback names a real token.
            assertEquals(PackSchema.tokens.size, load.tokens.values.size)
            assertTrue(load.fallbacks.all { it.token in PackSchema.byKey })
        }
    }

    @Test fun aPaletteThatRefersToItselfFallsBack() {
        val load =
            loadPack(
                consoleManifest(
                    mapOf(
                        "palette.foreground" to "on_page",
                        "palette.on_page" to "foreground@0.5",
                        "palette.accent" to "accent",
                        "palette.alt" to "highlight",
                        "palette.highlight" to "#ffffe14d",
                    ),
                ),
            )
        assertNull(load.error)
        assertTrue(load.fallbacks.isNotEmpty())
        assertTrue(load.fallbacks.all { it.kind == PackIssue.Kind.Invalid && it.token in setOf("palette.foreground", "palette.on_page", "palette.accent") })
        assertEquals(load.fallbacks, load.fallbacks.distinct())
        assertEquals(ConsolePack.pack.palette.accent, load.pack.palette.accent)
        // A chain that ends is fine.
        assertEquals("#ffffe14d", load.pack.palette.alt.toString())
    }

    @Test fun readsExtraColors() {
        val load =
            loadPack(
                consoleManifest(mapOf("scrap.tape.color" to "extra.washi", "scrap.tape.color2" to "extra.washi@0.5", "ink.second" to "extra.Bad")) +
                    "\n[palette.extra]\nwashi = \"#efe0ae\"\nBad = \"#000000\"\nwrong = \"blue\"\nnumber = 3\n",
            )
        assertEquals("#ffefe0ae", load.pack.scrap.tape.color.toString())
        assertEquals("#80efe0ae", load.pack.scrap.tape.color2.toString())
        assertEquals(mapOf("washi" to PackColor(0xFFEFE0AE.toInt())), load.pack.palette.named)
        assertEquals(
            mapOf(
                "palette.extra.Bad" to PackIssue.Kind.Ignored,
                "palette.extra.wrong" to PackIssue.Kind.Ignored,
                "palette.extra.number" to PackIssue.Kind.Ignored,
                "ink.second" to PackIssue.Kind.Invalid,
            ),
            kinds(load),
        )
        // Drawn in an Omarchy theme's colors, the pack keeps its own extras, and the theme's win a shared name.
        val themed = load.tokens.resolve(mapOf("accent" to "#ff0000", "yellow" to "#ffff00")).palette
        assertEquals(mapOf("washi" to "#ffefe0ae", "accent" to "#ffff0000", "yellow" to "#ffffff00"), themed.named.mapValues { it.value.toString() })
        assertEquals("#ffff0000", themed.accent.toString())
        val shared = loadPack(consoleManifest() + "\n[palette.extra]\nyellow = \"#111111\"\nwashi = \"#efe0ae\"\n").tokens.resolve(mapOf("yellow" to "#ffff00")).palette
        assertEquals(mapOf("yellow" to "#ffffff00", "washi" to "#ffefe0ae"), shared.named.mapValues { it.value.toString() })

        val many = (0 until 100).joinToString("\n") { "c$it = \"#000000\"" }
        assertEquals(64, loadPack(consoleManifest() + "\n[palette.extra]\n$many\n").pack.palette.named.size)
    }

    @Test fun namesAPackByItsFolderUnlessItSaysOtherwise() {
        assertEquals("test", loadPack(consoleManifest(without = setOf("pack.name"))).pack.name)
        assertEquals("City Pop", loadPack(consoleManifest(mapOf("pack.name" to "  City Pop "))).pack.name)
        for (bad in listOf<Any>("", "   ", "x".repeat(41), "two\nlines", 5)) {
            val load = loadPack(consoleManifest(mapOf("pack.name" to bad)))
            assertEquals("test", load.pack.name)
            assertEquals(mapOf("pack.name" to PackIssue.Kind.Invalid), kinds(load))
        }
    }
    private fun panelKinds(load: PackLoad) = kinds(load).filterKeys { it.startsWith("panels.") }

    /** The Manga look's manifest sets every key of its [panels], each to the mockup's value. */
    @Test fun readsTheMangaPacksPanels() {
        val load = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/manga")), "manga")
        assertNull(load.error)
        assertFalse(load.broken)
        assertEquals(emptyMap<String, PackIssue.Kind>(), panelKinds(load))
        val panels = load.pack.panels!!
        assertEquals("opening", panels.defaultLayout)
        assertEquals(1.15f, panels.inkWidth, 0f)
        assertEquals(2.3f, panels.gutterRows, 0f)
        assertEquals(1.1f, panels.gutterCols, 0f)
        assertEquals(PanelMargins(left = 2.4f, top = 3f, right = 2.4f, bottom = 4.4f), panels.margins)
        assertEquals(1.1f, panels.pad, 0f)
        assertEquals(PackScreentone(dot = 0.85f, spacing = 4.5f, opacity = 0.42f, fade = 0.04f), panels.screentone)
        assertEquals(ArtPrint.Ink, panels.artPrint)
        assertEquals(PanelEmpty.Screentone, panels.empty)
        assertEquals(panels, load.tokens.panels)
        // Its default layout is one the notebook model has (CONTRACT.md).
        assertTrue(panels.defaultLayout in PackPanels.LAYOUTS)
    }

    /** A pack with no [panels] has none, and says nothing about them. */
    @Test fun noSectionIsNoPanels() {
        val load = loadPack(consoleManifest())
        assertNull(load.pack.panels)
        assertNull(load.tokens.panels)
        assertTrue(load.issues.none { it.token.startsWith("panels") })
        assertNull(PackLoader.load(DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up")), "dial-up").pack.panels)
        // A header the parser can't read hides what is under it, as any section's does.
        val lost = loadPack(consoleManifest() + "\n[panels\nink_width = 2\n")
        assertNull(lost.pack.panels)
        assertTrue(lost.issues.none { it.token.startsWith("panels") })
    }

    /** A bare [panels] header turns panels on, every key at the mockup's value, each said to be unset as a token would be. */
    @Test fun anEmptySectionIsTheMockupsPanels() {
        val load = loadPack(consoleManifest() + "\n[panels]\n")
        assertNull(load.error)
        assertEquals(PackPanels.defaults, load.pack.panels)
        assertEquals(PackPanels.tokens.associate { it.key to PackIssue.Kind.Missing }, panelKinds(load))
        assertFalse(load.broken)
        // A subsection alone is the section too, and the keys it doesn't name are the defaults.
        val margins = loadPack(consoleManifest() + "\n[panels.margins]\nleft = 1\n").pack.panels
        assertEquals(PackPanels.defaults.copy(margins = PackPanels.defaults.margins.copy(left = 1f)), margins)
        // The defaults are the mockup's.
        assertEquals(
            PackPanels(
                defaultLayout = "opening",
                inkWidth = 1.15f,
                gutterRows = 2.3f,
                gutterCols = 1.1f,
                margins = PanelMargins(left = 2.4f, top = 3f, right = 2.4f, bottom = 4.4f),
                pad = 1.1f,
                screentone = PackScreentone(dot = 0.85f, spacing = 4.5f, opacity = 0.42f, fade = 0.04f),
                artPrint = ArtPrint.Ink,
                empty = PanelEmpty.Screentone,
            ),
            PackPanels.defaults,
        )
    }

    /** Every key read, and written back by the tokens' manifest so it loads the same again. */
    @Test fun roundTripsAFullPanelsSection() {
        val section =
            "\n[panels]\ndefault_layout = \"Grid6\"\nink_width = 2\ngutter_rows = 3.5\ngutter_cols = 0\npad = 0.5\nart_print = \"color\"\nempty = \"paper\"\n" +
                "[panels.margins]\nleft = 1\ntop = 2\nright = 3\nbottom = 12\n[panels.screentone]\ndot = 1\nspacing = 6\nopacity = 0.3\nfade = 0.3\n"
        val first = loadPack(consoleManifest() + section)
        assertEquals(emptyList<PackIssue>(), first.issues)
        val expected = PackPanels("grid6", 2f, 3.5f, 0f, PanelMargins(1f, 2f, 3f, 12f), 0.5f, PackScreentone(1f, 6f, 0.3f, 0.3f), ArtPrint.Color, PanelEmpty.Paper)
        assertEquals(expected, first.pack.panels)
        val second = loadPack(PackManifest.write(first.tokens.manifest()))
        assertEquals(emptyList<PackIssue>(), second.issues)
        assertEquals(first.tokens, second.tokens)
        assertEquals(first.pack, second.pack)
        // And a look with no panels writes none.
        assertTrue(ConsolePack.tokens.manifest().keys.none { it.startsWith("panels") })
    }

    /** A bad value is the default for that key alone, and said the way a bad token is; unknown keys are skipped. */
    @Test fun aBadPanelValueFallsBackAlone() {
        val section =
            "\n[panels]\ndefault_layout = \"zigzag\"\nink_width = \"thin\"\ngutter_rows = 99\ngutter_cols = 1.5\nart_print = \"sepia\"\nempty = 3\nwobble = 1\n" +
                "[panels.margins]\nleft = -3\ntop = true\n[panels.screentone]\nspacing = nan\nopacity = 2\n[panels.speedlines]\ncount = 3\nangle = [1]\n"
        val load = loadPack(consoleManifest() + section)
        assertNull(load.error)
        assertEquals(
            mapOf(
                "panels.default_layout" to PackIssue.Kind.Invalid,
                "panels.ink_width" to PackIssue.Kind.Invalid,
                "panels.gutter_rows" to PackIssue.Kind.Clamped,
                "panels.art_print" to PackIssue.Kind.Invalid,
                "panels.empty" to PackIssue.Kind.Invalid,
                "panels.margins.left" to PackIssue.Kind.Clamped,
                "panels.margins.top" to PackIssue.Kind.Invalid,
                "panels.margins.right" to PackIssue.Kind.Missing,
                "panels.margins.bottom" to PackIssue.Kind.Missing,
                "panels.pad" to PackIssue.Kind.Missing,
                "panels.screentone.dot" to PackIssue.Kind.Missing,
                "panels.screentone.spacing" to PackIssue.Kind.Invalid,
                "panels.screentone.opacity" to PackIssue.Kind.Clamped,
                "panels.screentone.fade" to PackIssue.Kind.Missing,
                "panels.wobble" to PackIssue.Kind.Ignored,
                "panels.speedlines.count" to PackIssue.Kind.Ignored,
                "panels.speedlines.angle" to PackIssue.Kind.Ignored,
            ),
            panelKinds(load),
        )
        val slip = load.issues.first { it.token == "panels.screentone.spacing" }.detail
        assertTrue(slip, slip.startsWith("line ") && slip.endsWith(": not a value this build can read"))
        assertTrue(load.broken)
        val d = PackPanels.defaults
        assertEquals(
            d.copy(gutterRows = 8f, gutterCols = 1.5f, margins = d.margins.copy(left = 0f), screentone = d.screentone.copy(opacity = 1f)),
            load.pack.panels,
        )
        // The rest of the pack is untouched by it.
        assertEquals(ConsolePack.pack.copy(id = "test", panels = load.pack.panels), load.pack)
        // A choice is read whatever its case, as a token's is.
        assertEquals(ArtPrint.Color, loadPack(consoleManifest() + "\n[panels]\nart_print = \"COLOR\"\n").pack.panels!!.artPrint)
    }

    /** Dots wider than half their spacing would run into flat gray, so a dot is never more than that. */
    @Test fun screentoneDotsNeverRunTogether() {
        for ((dot, spacing, expected) in listOf(Triple(3.0, 4.0, 2f), Triple(9.0, 4.5, 2.25f), Triple(2.0, 4.0, 2f), Triple(0.85, 4.5, 0.85f))) {
            val load = loadPack(consoleManifest() + "\n[panels.screentone]\ndot = $dot\nspacing = $spacing\nopacity = 0.4\nfade = 0.1\n")
            val tone = load.pack.panels!!.screentone
            assertEquals("$dot every $spacing", expected, tone.dot, 0f)
            assertEquals(spacing.toFloat(), tone.spacing, 0f)
            val said = load.issues.filter { it.token == "panels.screentone.dot" }
            if (dot > spacing / 2) {
                // One issue, naming the value used, even where the dot was out of range as well.
                assertEquals("$dot every $spacing", listOf(PackIssue.Kind.Clamped), said.map { it.kind })
                assertTrue(said.single().detail, said.single().detail.contains("$expected"))
            } else {
                assertEquals("$dot every $spacing", emptyList<PackIssue>(), said)
            }
        }
    }

    /** A value at a bound is in range however the bound is spelled: 0.2 is not under the Float 0.2f it is kept as. */
    @Test fun aValueAtItsBoundIsNotClamped() {
        val load = loadPack(consoleManifest(mapOf("type.display.tracking" to -0.1, "type.body.tracking" to 0.5)) + "\n[panels]\n[panels.screentone]\ndot = 0.2\nspacing = 24\n")
        assertEquals(emptyList<PackIssue>(), load.issues.filter { it.kind != PackIssue.Kind.Missing })
        assertFalse(load.broken)
        assertEquals(0.2f, load.pack.panels!!.screentone.dot, 0f)
        assertEquals(-0.1f, load.pack.type.display.tracking, 0f)
    }

    /** A dot the spacing pulls in has one issue, in its own place, whatever else was said of it. */
    @Test fun aDotPulledInBySpacingSaysSoOnce() {
        for (dot in listOf("", "dot = \"x\"\n", "dot = 9\n")) {
            val load = loadPack(consoleManifest() + "\n[panels.screentone]\n${dot}spacing = 1\n")
            val panels = load.issues.filter { it.token.startsWith("panels.") }.map { it.token }
            // Every key but the spacing, which is fine, in the section's order.
            assertEquals(dot, PackPanels.tokens.map { it.key } - "panels.screentone.spacing", panels)
            assertEquals(dot, listOf(PackIssue.Kind.Clamped), load.issues.filter { it.token == "panels.screentone.dot" }.map { it.kind })
            assertEquals(0.5f, load.pack.panels!!.screentone.dot, 0f)
        }
    }

    /** Whatever a [panels] section holds, there are panels, and every number is inside its key's range. */
    @Test fun panelsNeverThrowOnNoise() {
        val random = java.util.Random(19)
        val keys = PackPanels.tokens.map { it.key } + listOf("panels.extra", "panels.margins.middle")
        val scraps = listOf("1e99", "-0", "true", "\"\"", "[1]", "nan", "99999999999999999999", "'opening'", "\"splash\"", "\"ink\"", "0.5", "-7", "\"paper\"")
        repeat(300) {
            val text = StringBuilder(consoleManifest()).append("\n[panels]\n")
            repeat(random.nextInt(30)) {
                val key = keys[random.nextInt(keys.size)]
                text.append('[').append(key.substringBeforeLast('.')).append("]\n")
                text.append(key.substringAfterLast('.')).append(" = ").append(scraps[random.nextInt(scraps.size)]).append('\n')
            }
            val load = loadPack(text.toString())
            assertNull(load.error)
            val panels = load.pack.panels!!
            assertTrue(panels.defaultLayout in PackPanels.LAYOUTS)
            for ((key, value) in panels.manifest()) {
                val kind = PackPanels.byKey.getValue(key).kind
                if (kind is TokenKind.Number) assertTrue("$key = $value", (value as Float) in kind.min..kind.max)
            }
            assertTrue(panels.screentone.dot <= panels.screentone.spacing / 2f)
            assertTrue(load.fallbacks.all { it.token in PackSchema.byKey || it.token in PackPanels.byKey })
            // Written back, it loads the same and has nothing more to say about its panels.
            val again = loadPack(PackManifest.write(load.tokens.manifest()))
            assertEquals(load.tokens, again.tokens)
            assertEquals(emptyList<PackIssue>(), again.issues.filter { it.token.startsWith("panels") })
        }
    }
}
