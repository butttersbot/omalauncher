package dev.omalauncher.theme.pack

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Something the loader has to say about one token, or about a line it couldn't use. */
data class PackIssue(
    /** The token's key, the unknown key, or "" for a line that named none. */
    val token: String,
    val kind: Kind,
    /** A sentence for whoever made the pack. */
    val detail: String,
) {
    enum class Kind(
        /** True when the token ended up with Console's value instead of the pack's (in `[panels]`, the default panels' value). */
        val fellBack: Boolean,
    ) {
        /** The pack doesn't set it. */
        Missing(true),

        /** The pack sets it to something that isn't a value for it. */
        Invalid(true),

        /** The pack names a font or image it doesn't hold. */
        FileMissing(true),
        FileTooLarge(true),

        /** The pack names a file that isn't inside it. */
        FileOutside(true),

        /** The value was out of range and pulled to the nearest one allowed; it is still the pack's. */
        Clamped(false),

        /** A key or line this build doesn't know, skipped; usually a newer pack, sometimes a typo. */
        Ignored(false),

        /** The pack was written for a newer format than this build reads. */
        Newer(false),
    }
}

/** What loading a pack came to. There is always a pack to draw from. */
class PackLoad(
    val pack: ThemePack,
    val tokens: PackTokens,
    /** Everything worth telling the pack's author, in the manifest's order. */
    val issues: List<PackIssue>,
    /** Set when the manifest couldn't be used at all, and [pack] is Console: a sentence the UI can show. */
    val error: String?,
) {
    /** The tokens that got Console's value instead of the pack's (PRD UP-10). */
    val fallbacks get() = issues.filter { it.kind.fellBack }

    /**
     * True when something in the pack is wrong, rather than merely left to Console: the manifest
     * couldn't be used, or an issue is of a kind other than Missing, Ignored or Newer. A pack that
     * sets only a few tokens isn't broken; one with a bad value, a lost file or a clamped number is.
     */
    val broken get() = error != null || issues.any { it.kind !in QUIET }

    /** True when the pack is [broken] only in its `[panels]`, where what stands in is the default panels', not Console's. */
    val brokenOnlyInPanels get() = error == null && broken && issues.filter { it.kind !in QUIET }.all { it.token.startsWith(PackPanels.SECTION + ".") }

    private companion object {
        val QUIET = setOf(PackIssue.Kind.Missing, PackIssue.Kind.Ignored, PackIssue.Kind.Newer)
    }
}

/**
 * Reads a pack folder into a [ThemePack]. Whatever is in the folder, this returns: a token the
 * pack leaves out or gets wrong is Console's, and a manifest that can't be read is all of Console.
 *
 * Two things this leaves to whoever builds on it:
 * - A font or image is checked against its size limit ([AssetType.maxBytes]) by what
 *   [PackSource.stat] reports, once, here. The code that later opens the file to decode it must
 *   stop reading at the limit itself, since the file may have changed or the source misreported.
 * - Any [id] of the right shape is taken, "console" included, from any source. The registry
 *   that lists installed packs must keep [ConsolePack.ID] for the built-in one, or a
 *   downloaded folder named console would pass for it.
 */
object PackLoader {
    const val MANIFEST = "pack.toml"

    /** A full manifest is about 6 KB. */
    const val MAX_MANIFEST = 64 * 1024

    private const val MAX_EXTRA = 64
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val EXTRA = Regex("^[a-z0-9_]{1,40}$")

    /** Loads the pack in [source], known by [id], its folder's name. */
    fun load(
        source: PackSource,
        id: String,
    ): PackLoad =
        try {
            read(source, id)
        } catch (e: Exception) {
            failed("The pack couldn't be read: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun failed(error: String) = PackLoad(ConsolePack.pack, ConsolePack.tokens, emptyList(), error)

    private fun read(
        source: PackSource,
        id: String,
    ): PackLoad {
        if (!ID.matches(id)) return failed("A pack's folder is named in lowercase letters, digits and dashes.")
        val text =
            when (val manifest = manifest(source)) {
                is Manifest.Text -> manifest.text
                is Manifest.Unusable -> return failed(manifest.error)
            }
        val parsed = PackManifest.parse(text)
        val raw = parsed.values
        val issues = ArrayList<PackIssue>()
        parsed.problems.mapTo(issues) { PackIssue("", PackIssue.Kind.Ignored, it) }

        val unreadable = parsed.unreadable.filterKeys { it !in raw }
        if (raw.isEmpty() && unreadable.isEmpty()) return failed("$MANIFEST has nothing in it this build can read.")
        val format = (raw["pack.format"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        if (format == null) {
            return failed(
                if ("pack.format" in raw || "pack.format" in unreadable) {
                    "$MANIFEST's pack format has to be a whole number from 1 up ([pack] format = ${PackSchema.FORMAT})."
                } else {
                    "$MANIFEST doesn't say which pack format it is written for ([pack] format = ${PackSchema.FORMAT})."
                },
            )
        }
        if (format > PackSchema.FORMAT) {
            issues += PackIssue("pack.format", PackIssue.Kind.Newer, "written for format $format; this build reads ${PackSchema.FORMAT} and skips what it doesn't know")
        }
        val given = raw["pack.name"]
        val named = (given as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && it.none(PackSchema::unshowable) }
        if ((given != null || "pack.name" in unreadable) && named == null) {
            issues += PackIssue("pack.name", PackIssue.Kind.Invalid, "not a name of up to 40 characters; the folder's name is used")
        }
        val name = named ?: id

        val extra = LinkedHashMap<String, PackColor>()
        for ((key, value) in raw) {
            if (!key.startsWith(EXTRA_PREFIX)) continue
            val color = (value as? String)?.let(PackColor::parse)
            val known = key.removePrefix(EXTRA_PREFIX)
            when {
                !EXTRA.matches(known) || color == null -> issues += PackIssue(key, PackIssue.Kind.Ignored, "not a named #rrggbb or #aarrggbb color")
                extra.size >= MAX_EXTRA -> issues += PackIssue(key, PackIssue.Kind.Ignored, "a pack has at most $MAX_EXTRA extra colors")
                else -> extra[known] = color
            }
        }

        val values = LinkedHashMap<String, Any>()
        for (token in PackSchema.tokens) {
            val key = token.key
            val console = ConsolePack.tokens.values.getValue(key)
            val given = raw[key]
            if (given == null) {
                // Set to something unreadable is a mistake to show; not set at all is a pack saying less.
                val slip = unreadable[key]
                issues += if (slip != null) PackIssue(key, PackIssue.Kind.Invalid, slip) else PackIssue(key, PackIssue.Kind.Missing, "not set")
                values[key] = console
                continue
            }
            when (val read = token.read(given, extra.keys)) {
                is TokenRead.Bad -> {
                    issues += PackIssue(key, read.kind, read.why)
                    values[key] = console
                }
                is TokenRead.Ok -> {
                    val missing = (token.kind as? TokenKind.Asset)?.let { file(source, read.value as String, it.type) }
                    if (missing != null) {
                        issues += PackIssue(key, missing.first, missing.second)
                        values[key] = console
                    } else {
                        if (read.clamped) issues += PackIssue(key, PackIssue.Kind.Clamped, "out of range; ${read.value} is used")
                        values[key] = read.value
                    }
                }
            }
        }
        val panels = panels(parsed, issues)
        for (key in raw.keys) {
            if (key !in PackSchema.byKey && key !in META && !key.startsWith(EXTRA_PREFIX) && !key.startsWith(PANELS_PREFIX)) {
                issues += PackIssue(key, PackIssue.Kind.Ignored, "not a key this build knows")
            }
        }
        for ((key, slip) in unreadable) {
            if (key !in PackSchema.byKey && key !in META && !key.startsWith(PANELS_PREFIX)) issues += PackIssue(key, PackIssue.Kind.Ignored, slip)
        }

        val tokens = PackTokens(id, name, format, values, extra, panels)
        val resolving = ArrayList<PackIssue>()
        val pack = PackBuilder(tokens, null, resolving).build()
        return PackLoad(pack, tokens, issues + resolving.distinct(), null)
    }

    private const val EXTRA_PREFIX = "palette.extra."
    private const val PANELS_PREFIX = PackPanels.SECTION + "."
    private val META = setOf("pack.format", "pack.name")

    /**
     * The pack's `[panels]`, or null when it has no such section, not even an empty header. Each key
     * is checked as a token is and says so the same way, but what it falls back to is the mockup's
     * panels ([PackPanels.defaults]), not Console's: Console has none.
     */
    private fun panels(
        parsed: PackManifest.Parsed,
        issues: MutableList<PackIssue>,
    ): PackPanels? {
        val raw = parsed.values
        val unreadable = parsed.unreadable.filterKeys { it !in raw }
        val present =
            parsed.sections.any { it == PackPanels.SECTION || it.startsWith(PANELS_PREFIX) } ||
                raw.keys.any { it.startsWith(PANELS_PREFIX) } ||
                unreadable.keys.any { it.startsWith(PANELS_PREFIX) }
        if (!present) return null
        val defaults = PackPanels.defaults.manifest()
        val values = LinkedHashMap<String, Any>()
        // Said in the section's own key order, after the tokens'.
        val said = ArrayList<PackIssue>()
        for (token in PackPanels.tokens) {
            val key = token.key
            val given = raw[key]
            if (given == null) {
                val slip = unreadable[key]
                said += if (slip != null) PackIssue(key, PackIssue.Kind.Invalid, slip) else PackIssue(key, PackIssue.Kind.Missing, "not set")
                values[key] = defaults.getValue(key)
                continue
            }
            when (val read = token.read(given)) {
                is TokenRead.Bad -> {
                    said += PackIssue(key, read.kind, read.why)
                    values[key] = defaults.getValue(key)
                }
                is TokenRead.Ok -> {
                    if (read.clamped) said += PackIssue(key, PackIssue.Kind.Clamped, "out of range; ${read.value} is used")
                    values[key] = read.value
                }
            }
        }
        // Dots wider than half their spacing would run into a flat gray, which is no screentone. The dot
        // then has one issue, where its own would be, saying what is used, whatever else was wrong with it.
        val dot = "panels.screentone.dot"
        val half = values.getValue("panels.screentone.spacing") as Float / 2f
        if (values.getValue(dot) as Float > half) {
            val order = PackPanels.tokens.map { it.key }
            val at = said.indexOfFirst { it.token == dot }.takeIf { it >= 0 } ?: said.indexOfFirst { order.indexOf(it.token) > order.indexOf(dot) }.takeIf { it >= 0 } ?: said.size
            said.removeAll { it.token == dot }
            said.add(minOf(at, said.size), PackIssue(dot, PackIssue.Kind.Clamped, "more than half the spacing, so the dots would run together; $half is used"))
            values[dot] = half
        }
        issues += said
        for (key in raw.keys) {
            if (key.startsWith(PANELS_PREFIX) && key !in PackPanels.byKey) issues += PackIssue(key, PackIssue.Kind.Ignored, "not a key this build knows")
        }
        for ((key, slip) in unreadable) {
            if (key.startsWith(PANELS_PREFIX) && key !in PackPanels.byKey) issues += PackIssue(key, PackIssue.Kind.Ignored, slip)
        }
        return PackPanels.from(values)
    }

    /** Why the file a token names can't be used, or null when it can. */
    private fun file(
        source: PackSource,
        path: String,
        type: AssetType,
    ): Pair<PackIssue.Kind, String>? {
        if (path.isEmpty()) return null
        // A source isn't meant to throw; one that does has lost this file, not the whole pack.
        val found =
            try {
                source.stat(path)
            } catch (e: Exception) {
                PackEntry.Missing
            }
        return when (val entry = found) {
            PackEntry.Missing -> PackIssue.Kind.FileMissing to "$path isn't in the pack"
            PackEntry.Outside -> PackIssue.Kind.FileOutside to "$path isn't inside the pack"
            is PackEntry.File ->
                when {
                    entry.size <= 0 -> PackIssue.Kind.FileMissing to "$path is empty"
                    entry.size > type.maxBytes -> PackIssue.Kind.FileTooLarge to "$path is over ${type.maxBytes / (1024 * 1024)} MB"
                    else -> null
                }
        }
    }

    private sealed interface Manifest {
        class Text(
            val text: String,
        ) : Manifest

        class Unusable(
            val error: String,
        ) : Manifest
    }

    private fun manifest(source: PackSource): Manifest {
        when (val entry = source.stat(MANIFEST)) {
            PackEntry.Missing -> return Manifest.Unusable("The pack has no $MANIFEST.")
            PackEntry.Outside -> return Manifest.Unusable("The pack's $MANIFEST isn't inside the pack.")
            is PackEntry.File -> if (entry.size > MAX_MANIFEST) return Manifest.Unusable(TOO_BIG)
        }
        // The size above is only what the source claims, so the read is capped as well.
        val bytes =
            source.open(MANIFEST)?.use { input ->
                val buffer = ByteArray(MAX_MANIFEST + 1)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                buffer.copyOf(size)
            } ?: return Manifest.Unusable("The pack's $MANIFEST couldn't be opened.")
        if (bytes.size > MAX_MANIFEST) return Manifest.Unusable(TOO_BIG)
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                return Manifest.Unusable("$MANIFEST isn't text.")
            }
        if ('\u0000' in text) return Manifest.Unusable("$MANIFEST isn't text.")
        return Manifest.Text(text)
    }

    private const val TOO_BIG = "$MANIFEST is larger than ${MAX_MANIFEST / 1024} KB."
}
