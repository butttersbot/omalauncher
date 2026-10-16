package dev.omalauncher.keyboard

import android.content.res.AssetManager
import android.graphics.Typeface
import dev.omalauncher.theme.pack.PackShadow
import dev.omalauncher.theme.pack.PackSurface
import dev.omalauncher.theme.pack.ThemePack

/** A shadow under a key, dp; scaled down from the look's scrap shadow to a key's size. */
class KeyShade(
    val x: Float,
    val y: Float,
    val blur: Float,
    val color: Int,
)

/** How one kind of key is drawn in the look's own style: its fill, ink, outline and shadows. */
class KeyFace(
    val fill: Int,
    val ink: Int,
    val border: Float,
    val borderColor: Int,
    val shadows: List<KeyShade>,
)

private const val BLACK = 0xff000000.toInt()

private fun opaque(c: Int) = c or BLACK

/** Whether two colours are told apart at a glance: their channels differ by more than about a tenth in all. */
private fun apart(
    a: Int,
    b: Int,
): Boolean {
    fun ch(c: Int, sh: Int) = (c shr sh) and 0xff
    return listOf(16, 8, 0).sumOf { kotlin.math.abs(ch(a, it) - ch(b, it)) } > 80
}

/**
 * The look's shadow at a key's size: a scrap's offset of 7 dp would lie on the next key, so offsets are
 * held to 3 dp and blur to 6, keeping their direction and hardness. Inset shadows (a display set into its
 * chassis) and empty ones are left out.
 */
private fun keyShade(s: PackShadow): KeyShade? {
    if (s.inset || (s.color.argb ushr 24) == 0 || (s.x == 0f && s.y == 0f && s.blur == 0f)) return null
    fun hold(v: Float) = v.coerceIn(-3f, 3f)
    return KeyShade(hold(s.x), hold(s.y), s.blur.coerceAtMost(6f), s.color.argb)
}

private fun face(
    s: PackSurface,
    fill: Int? = null,
    ink: Int? = null,
) = KeyFace(
    fill = fill ?: opaque(s.fill.argb),
    ink = ink ?: opaque(s.ink.argb),
    border = s.border.coerceAtMost(2f),
    borderColor = s.borderColor.argb,
    shadows = listOfNotNull(keyShade(s.shadow2), keyShade(s.shadow)),
)

/**
 * The keyboard in the active look (Boss, 2026-10-15: "theme the keyboard based on the phone's active Oma theme",
 * the deeper way): nothing per look is written here; each key is one of the look's scraps, on its page.
 * Letters are its scrap surface, shift, ⌫ and the rest the same a shade toward its ink, the mic its accent and
 * return its emphasised scrap, with the look's corners and its body font. Mechanical caps use its colours too.
 */
fun keyboardLook(
    pack: ThemePack,
    assets: AssetManager,
): KeyboardLook {
    val pal = pack.palette
    val bg = opaque(pal.background.argb)
    val surface = opaque(pal.surface.argb)
    val ink = opaque(pal.foreground.argb)
    val key = if (pal.isLight) bg else surface
    val board = if (pal.isLight) KeyboardView.blend(surface, ink, 0.08f) else KeyboardView.blend(bg, BLACK, 0.3f)
    val mod = if (pal.isLight) KeyboardView.blend(surface, ink, 0.18f) else KeyboardView.blend(bg, surface, 0.5f)
    fun font(file: String) =
        runCatching { if (file.isEmpty()) null else Typeface.createFromAsset(assets, "packs/${pack.id}/$file") }.getOrNull()
    val typeface = font(pack.type.body.file) ?: Typeface.DEFAULT
    val scrap = pack.scrap.surface
    val letters = face(scrap)
    val page = opaque(pack.page.color.argb).takeIf { (pack.page.color.argb ushr 24) != 0 } ?: board
    return KeyboardLook(
        board = board,
        key = key,
        mod = mod,
        ink = ink,
        muted = pal.muted.argb,
        mic = opaque(pal.accent.argb),
        onMic = bg,
        ret = opaque(pal.alt.argb),
        onRet = opaque(pal.onAlt.argb),
        led = opaque(pal.accent.argb),
        typeface = typeface,
        page = page,
        radius = (pack.shape.radius * (pack.shape.corners.scale ?: 1f)).coerceAtMost(10f),
        letter = letters,
        modKey = face(scrap, fill = KeyboardView.blend(letters.fill, letters.ink, 0.12f)),
        // The accent, unless it is the page's own colour (Tiny Magazine's blue), where the mic would vanish: then the ink.
        micKey = if (apart(opaque(pal.accent.argb), page)) face(scrap, fill = opaque(pal.accent.argb), ink = bg) else face(scrap, fill = ink, ink = bg),
        returnKey = face(pack.scrap.alt),
        pageInk = opaque(pal.onPage.argb),
    )
}
