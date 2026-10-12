package dev.omalauncher.theme.pack

/**
 * A pack's `pack.toml`: the part of TOML a pack needs, read without a TOML library like
 * Omarchy's colors.toml is. `[section]` headers, `key = value` lines and `#` comments, where a
 * value is a quoted string, a number, or true/false. Keys come out dotted: `[scrap.tape]` and
 * `color = "…"` is `scrap.tape.color`.
 */
object PackManifest {
    /** What a manifest said, and the lines that said nothing we could read. */
    class Parsed(
        /** Each key's value: a String, Long, Double or Boolean. The first of a repeated key wins. */
        val values: Map<String, Any>,
        /** One sentence per line that was skipped and named no key, with its line number. */
        val problems: List<String>,
        /**
         * Keys that were set to something this build can't read (`radius = NaN`, a list), each with
         * a sentence saying where, so a slip isn't taken for a key left out. A key also in
         * [values] was set again, readably.
         */
        val unreadable: Map<String, String> = emptyMap(),
    )

    /** No token needs a longer line, and a short one keeps the patterns below cheap whatever a pack holds. */
    private const val MAX_LINE = 1024

    private val SECTION = Regex("""^\[\s*([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)\s*]\s*(?:#.*)?$""")
    private val KEY = Regex("""^([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)\s*=\s*(.*)$""")
    private val WHOLE = Regex("""^[+-]?\d+$""")
    private val DECIMAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

    /** Reads what it can and never throws: a line it can't read is a problem, not a failure. */
    fun parse(text: String): Parsed {
        val values = LinkedHashMap<String, Any>()
        val problems = ArrayList<String>()
        val unreadable = LinkedHashMap<String, String>()
        var section = ""
        // A section header we couldn't read hides its keys, rather than filing them under the one before.
        var lost = false
        text.removePrefix("﻿").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val at = "line ${index + 1}"
            if (line.length > MAX_LINE) {
                problems += "$at: longer than $MAX_LINE characters"
                if (line.startsWith("[")) lost = true
                return@forEachIndexed
            }
            if (line.startsWith("[")) {
                val name = SECTION.find(line)?.groupValues?.get(1)
                lost = name == null
                if (name == null) problems += "$at: not a section this build reads" else section = name
                return@forEachIndexed
            }
            if (lost) return@forEachIndexed
            val match = KEY.find(line)
            if (match == null) {
                problems += "$at: not a key = value line"
                return@forEachIndexed
            }
            val key = if (section.isEmpty()) match.groupValues[1] else section + "." + match.groupValues[1]
            val value = value(match.groupValues[2])
            when {
                value == null -> unreadable.putIfAbsent(key, "$at: not a value this build can read")
                key in values -> problems += "$at: $key is set twice; the first one counts"
                else -> values[key] = value
            }
        }
        return Parsed(values, problems, unreadable)
    }

    /** One value with what follows it on the line, which may only be a comment. Null if it isn't one we read. */
    private fun value(text: String): Any? {
        if (text.startsWith("\"")) return quoted(text)
        if (text.startsWith("'")) {
            val end = text.indexOf('\'', 1)
            return if (end > 0 && onlyComment(text, end + 1)) text.substring(1, end) else null
        }
        val bare = text.substringBefore('#').trim()
        return when {
            bare == "true" -> true
            bare == "false" -> false
            WHOLE.matches(bare) -> bare.toLongOrNull() ?: bare.toDoubleOrNull()
            DECIMAL.matches(bare) -> bare.toDoubleOrNull()
            else -> null
        }
    }

    private fun onlyComment(
        text: String,
        from: Int,
    ) = text.substring(from).trim().let { it.isEmpty() || it.startsWith("#") }

    private fun quoted(text: String): String? {
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return if (onlyComment(text, i + 1)) out.toString() else null
                c != '\\' -> out.append(c)
                else -> {
                    i++
                    when (text.getOrNull(i) ?: return null) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            val code = text.substring(i + 1, minOf(i + 5, text.length)).takeIf { it.length == 4 }?.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
            }
            i++
        }
        return null
    }

    /** The manifest text for [values], grouped into sections in the order the keys come. */
    fun write(values: Map<String, Any>): String {
        val out = StringBuilder()
        for ((section, keys) in values.entries.groupBy { it.key.substringBeforeLast('.', "") }) {
            if (out.isNotEmpty()) out.append('\n')
            if (section.isNotEmpty()) out.append('[').append(section).append("]\n")
            for ((key, value) in keys) out.append(key.substringAfterLast('.')).append(" = ").append(text(value)).append('\n')
        }
        return out.toString()
    }

    private fun text(value: Any): String =
        when (value) {
            is String -> buildString {
                append('"')
                for (c in value) {
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '\t' -> append("\\t")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }
            // By way of its text, so 0.9f is written 0.9 and not the double nearest to it.
            is Float -> number(value.toString().toDouble())
            is Double -> number(value)
            else -> value.toString()
        }

    /** Whole numbers without a trailing `.0`, so `border = 2` reads the way a person would write it. */
    private fun number(value: Double): String = if (value == Math.rint(value) && Math.abs(value) < 1e9) value.toLong().toString() else value.toString()
}
