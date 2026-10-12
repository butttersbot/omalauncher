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
        /** True when the token ended up with Console's value instead of the pack's. */
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
}

/**
 * Reads a pack folder into a [ThemePack]. Whatever is in the folder, this returns: a token the
 * pack leaves out or gets wrong is Console's, and a manifest that can't be read is all of Console.
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

        if (raw.isEmpty()) return failed("$MANIFEST has nothing in it this build can read.")
        val format = (raw["pack.format"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: return failed("$MANIFEST doesn't say which pack format it is written for ([pack] format = ${PackSchema.FORMAT}).")
        if (format > PackSchema.FORMAT) {
            issues += PackIssue("pack.format", PackIssue.Kind.Newer, "written for format $format; this build reads ${PackSchema.FORMAT} and skips what it doesn't know")
        }
        val given = raw["pack.name"]
        val named = (given as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && it.none { c -> c < ' ' || c == '\u007f' } }
        if (given != null && named == null) {
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
                issues += PackIssue(key, PackIssue.Kind.Missing, "not set")
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
        for (key in raw.keys) {
            if (key !in PackSchema.byKey && key !in META && !key.startsWith(EXTRA_PREFIX)) {
                issues += PackIssue(key, PackIssue.Kind.Ignored, "not a key this build knows")
            }
        }

        val tokens = PackTokens(id, name, format, values, extra)
        val resolving = ArrayList<PackIssue>()
        val pack = PackBuilder(tokens, null, resolving).build()
        return PackLoad(pack, tokens, issues + resolving.distinct(), null)
    }

    private const val EXTRA_PREFIX = "palette.extra."
    private val META = setOf("pack.format", "pack.name")

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
