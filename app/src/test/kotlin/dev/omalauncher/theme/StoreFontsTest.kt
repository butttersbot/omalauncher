package dev.omalauncher.theme

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** The fonts each device fetches once from Soma Canvas's store (Boss, 2026-10-29: emoji and CJK aren't bundled). */
class StoreFontsTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val emoji = "emoji-bytes".toByteArray()
    private val pin = StoreFonts.Pin("emoji", "Noto Color Emoji", sha(emoji) + ".ttf", sha(emoji), emoji.size.toLong(), null)

    @Test fun thePinsAreTheBundledSymbolsThenTheFetchedFacesInStackOrder() {
        val (bundled, fetched) = StoreFonts.pins(File("src/main/assets/fonts/store.json").let { if (it.exists()) it else File("app/src/main/assets/fonts/store.json") }.readText())
        assertEquals(listOf("symbols"), bundled.map { it.role })
        assertEquals("fonts/NotoSansSymbols2.ttf", bundled.single().asset)
        assertEquals(listOf("emoji", "cjk"), fetched.map { it.role })
        assertTrue(fetched.all { it.asset == null && it.sha256.length == 64 && it.size > 1_000_000 })
        // The bundled file is the one the store pins.
        val asset = File("src/main/assets/fonts/NotoSansSymbols2.ttf").let { if (it.exists()) it else File("app/src/main/assets/fonts/NotoSansSymbols2.ttf") }
        assertEquals(bundled.single().sha256, sha(asset.readBytes()))
    }

    @Test fun aFaceIsKeptOnlyWholeAndAsPinnedAndFetchedOnce() {
        val dir = Files.createTempDirectory("store-fonts").toFile()
        var asked = 0
        val urls = mutableListOf<String>()
        fun serve(bytes: ByteArray): (String) -> java.io.InputStream = { url -> asked++; urls += url; ByteArrayInputStream(bytes) }
        // A file that isn't the pinned one (same size, other bytes) is thrown away, and nothing is kept.
        assertEquals(emptyList<StoreFonts.Pin>(), StoreFonts.fetchMissing(dir, "http://canvas:5860/", listOf(pin), serve("emoji-BYTES".toByteArray())))
        assertNull(StoreFonts.cached(dir, pin))
        assertTrue(dir.listFiles()!!.isEmpty())
        // A longer one too.
        assertEquals(emptyList<StoreFonts.Pin>(), StoreFonts.fetchMissing(dir, "http://canvas:5860", listOf(pin), serve(emoji + emoji)))
        // The right one is kept under its pinned name, from the store's file route.
        assertEquals(listOf(pin), StoreFonts.fetchMissing(dir, "http://canvas:5860/", listOf(pin), serve(emoji)))
        assertEquals("http://canvas:5860/api/fonts/files/${pin.file}", urls.last())
        assertArrayEquals(emoji, StoreFonts.cached(dir, pin)!!.readBytes())
        // Kept: not asked again.
        val before = asked
        assertEquals(emptyList<StoreFonts.Pin>(), StoreFonts.fetchMissing(dir, "http://canvas:5860", listOf(pin), serve(emoji)))
        assertEquals(before, asked)
        // Canvas out of reach: nothing kept, nothing thrown.
        val other = pin.copy(file = "0".repeat(64) + ".otf", sha256 = "0".repeat(64))
        assertEquals(emptyList<StoreFonts.Pin>(), StoreFonts.fetchMissing(dir, "http://canvas:5860", listOf(other)) { throw java.io.IOException("offline") })
        assertNull(StoreFonts.cached(dir, other))
    }

    @Test fun consoleCarriesTheStoreFontsItsVoicesName() {
        fun asset(p: String) = File("src/main/assets/packs/console/$p").let { if (it.exists()) it else File("app/src/main/assets/packs/console/$p") }
        // The store's console set (manifest 92235ea631ee): JetBrains Mono 48715a42…, Schibsted Grotesk 6ceeadf6….
        assertEquals("48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda", sha(asset(dev.omalauncher.theme.pack.ConsolePack.FONT).readBytes()))
        assertEquals("6ceeadf6be8e1fd7687011c7fa38ed0edd1abe967a0b73d97caec183552e823d", sha(asset(dev.omalauncher.theme.pack.ConsolePack.BODY_FALLBACK).readBytes()))
        assertEquals(4, asset("pack.toml").readText().split("file = \"fonts/JetBrainsMono.ttf\"").size - 1)
    }

    @Test fun aPinnedNameNeverBecomesAPath() {
        val dir = Files.createTempDirectory("store-fonts").toFile()
        val evil = pin.copy(file = "../../evil.ttf")
        StoreFonts.fetchMissing(dir, "http://canvas", listOf(evil)) { ByteArrayInputStream(emoji) }
        assertFalse(File(dir.parentFile.parentFile, "evil.ttf").exists())
    }

    @Test fun everyVoiceGetsTheStoreFallbacksBehindItsOwnFace() {
        val text = File("src/main/kotlin/dev/omalauncher/theme/ThemeStore.kt").let { if (it.exists()) it else File("app/src/main/kotlin/dev/omalauncher/theme/ThemeStore.kt") }.readText()
        assertTrue(text.contains("Typeface.CustomFallbackBuilder(own).apply { (after + storeFallbacks()).forEach { addCustomFallback(it) } }"))
        // Console draws in its store fonts too, its body with Schibsted Grotesk behind JetBrains Mono.
        assertTrue(text.contains("packFont(source, id, ConsolePack.FONT, listOf(ConsolePack.BODY_FALLBACK))"))
        assertFalse("no Latin cut, no generic family in front of the store's", text.contains("FontFamily.SansSerif"))
    }
}
