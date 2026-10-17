package dev.omalauncher.theme.pack

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The pack the user picked and what loading it came to. [load] is null for Console, which is
 * built in and never read from a folder.
 */
class PackPick(
    val id: String,
    val load: PackLoad?,
) {
    /** True when the launcher draws from Console: it was picked, or the picked pack's manifest couldn't be used (PRD UP-10). */
    val isConsole get() = id == ConsolePack.ID || load == null || load.error != null

    /** A sentence for the picker when the picked pack isn't the one being drawn; never shown on home. */
    val error get() = load?.error

    companion object {
        val Console = PackPick(ConsolePack.ID, null)
    }
}

/** One pack the picker offers. */
class PackOption(
    val id: String,
    val name: String,
    /** Null for Console. */
    val load: PackLoad?,
) {
    /** What the picker says under the name when the pack isn't whole; null when it is. */
    val problem: String?
        get() =
            when {
                load == null -> null
                load.error != null -> load.error
                load.broken -> "some of it is wrong; Console's values stand in"
                else -> null
            }
}

/** The packs to pick from, and the one in use. Plain Kotlin: the app hands it the folders. */
object PackRegistry {
    /**
     * The packs in [ids], each opened through [open], after Console, which is always first and always the
     * built-in one: a folder named `console` is never read, so nothing can pass for it.
     */
    fun options(
        ids: List<String>,
        open: (String) -> PackSource?,
    ): List<PackOption> =
        listOf(PackOption(ConsolePack.ID, ConsolePack.pack.name, null)) +
            ids.filter { it != ConsolePack.ID }.distinct().sorted().mapNotNull { id ->
                val load = load(id, open) ?: return@mapNotNull null
                PackOption(id, if (load.error == null) load.pack.name else id, load)
            }

    /**
     * The looks offered in the pickers. Console is left out: it is the built-in base every look falls back
     * to, not one Boss picks. It is still offered while it is the one in use (so the picker shows what is
     * drawn), and when there is nothing else.
     */
    fun offered(
        options: List<PackOption>,
        picked: String,
    ): List<PackOption> = options.filter { it.id != ConsolePack.ID || picked == ConsolePack.ID }.ifEmpty { options }

    /** Loads the pack [id]; null for Console or a folder that isn't there. Never throws. */
    fun load(
        id: String,
        open: (String) -> PackSource?,
    ): PackLoad? {
        if (id == ConsolePack.ID) return null
        val source =
            try {
                open(id)
            } catch (e: Exception) {
                null
            } ?: return null
        return PackLoader.load(source, id)
    }

    /** The saved choice [id] as a [PackPick]: Console when nothing is saved, or the pack is gone. */
    fun choose(
        id: String?,
        open: (String) -> PackSource?,
    ): PackPick {
        if (id == null || id == ConsolePack.ID) return PackPick.Console
        val load = load(id, open) ?: return PackPick.Console
        return PackPick(id, load)
    }

    /**
     * The pack to draw from. Console takes the Omarchy theme's [themeColors] (PRD AC-21) and is what a pack
     * that couldn't be loaded falls back to. Any other pack is drawn in its own colors, whichever theme is
     * picked: the theme stays picked and shows again with Console.
     */
    fun resolve(
        choice: PackPick,
        themeColors: Map<String, String>?,
    ): ThemePack {
        val load = choice.load
        if (choice.isConsole || load == null) return if (themeColors == null) ConsolePack.pack else ConsolePack.withColors(themeColors)
        return load.pack
    }
}

/**
 * At most [max] bytes of [path] in [source], or null when it is missing, empty or longer. The loader only
 * checked the size the source reported; this is the read that holds a font or image to its limit.
 */
fun readCapped(
    source: PackSource,
    path: String,
    max: Long,
): ByteArray? {
    if (path.isEmpty() || PackSchema.pathProblem(path) != null) return null
    val input: InputStream =
        try {
            source.open(path)
        } catch (e: Exception) {
            null
        } ?: return null
    return try {
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                size += n
                if (size > max) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray().takeIf { bytes -> bytes.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * The angle the scrap known by [key] is drawn at: fixed for that key, somewhere from [min] to [max]
 * degrees, so a scrap leans the same way every time it is drawn and after every restart. 0 when the pack
 * doesn't tilt.
 */
fun scrapTilt(
    key: String,
    min: Float,
    max: Float,
): Float {
    val low = minOf(min, max)
    val high = maxOf(min, max)
    if (low == high) return low
    // String.hashCode is specified, so the angle is the same on every device and run.
    val step = (key.hashCode().toLong() and 0xFFFFFFFFL) % 1001L
    return low + (high - low) * (step / 1000f)
}

/** A window whose short side is at least this many dp is the unfolded display; as the notebook's fold rule has it. */
private const val INNER_MIN_DP = 600f

/**
 * Which of a pack's two wallpapers a window of [widthDp] by [heightDp] shows: "cover" folded, "inner"
 * unfolded (PRD AC-20). A pack with only one shows it on both; null when it has neither.
 */
fun wallpaperSide(
    widthDp: Float,
    heightDp: Float,
    hasCover: Boolean,
    hasInner: Boolean,
): String? {
    val inner = minOf(widthDp, heightDp) >= INNER_MIN_DP
    return when {
        inner && hasInner -> "inner"
        !inner && hasCover -> "cover"
        hasCover -> "cover"
        hasInner -> "inner"
        else -> null
    }
}

/**
 * The part of an image of [srcW] by [srcH] that fills a display of [dstW] by [dstH] as x, y, width and
 * height: the whole image scaled until it covers, cut evenly at the two sides that stick out. One image
 * over the whole display, never tiled and never one half per page. Null when either has no area.
 */
fun coverCrop(
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
): List<Int>? {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
    // Compared as products, so no rounding decides which sides are cut.
    return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
        val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
        listOf((srcW - w) / 2, 0, w, srcH)
    } else {
        val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
        listOf(0, (srcH - h) / 2, srcW, h)
    }
}
