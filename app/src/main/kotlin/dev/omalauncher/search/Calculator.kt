package dev.omalauncher.search

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * The search's inline answers, as Raycast's calculator: sums ("12*7+3", "2^10", "sqrt(2)"), percentages
 * ("15% of 84", "84 + 15%"), and units ("5 mi to km", "72 f in c", "3 gb in mb"). Only a query that is clearly one of
 * these gets an answer: a bare number or a word is left to the rest of the search. Plain Kotlin.
 */
object Calculator {
    class Answer(
        /** As shown: grouped, at most ten significant digits. */
        val shown: String,
        /** As copied: plain digits. */
        val plain: String,
        /** What kind of answer it is: "Calculator", "Units", "Percentage". */
        val kind: String,
    )

    fun answer(query: String): Answer? {
        val s = query.trim().lowercase().replace('×', '*').replace('÷', '/').replace(",", "")
        if (s.isEmpty()) return null
        units(s)?.let { return it }
        percentOf(s)?.let { return it }
        // A sum: something to work out, not just a number.
        if (!Regex("[+*/^%()]|\\d\\s*-\\s*[\\d(.]|[a-z]+\\(").containsMatchIn(s)) return null
        val v = runCatching { Parser(s).parse() }.getOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return Answer(show(v), plain(v), "Calculator")
    }

    private fun percentOf(s: String): Answer? {
        val m = Regex("^([\\d.]+)\\s*%\\s*of\\s*([\\d.]+)$").find(s) ?: return null
        val v = (m.groupValues[1].toDoubleOrNull() ?: return null) * (m.groupValues[2].toDoubleOrNull() ?: return null) / 100
        return Answer(show(v), plain(v), "Percentage")
    }

    /** Each unit: its kind and how many base units one is (temperatures are converted apart). */
    private class Unit(val kind: String, val factor: Double, val name: String)

    private val UNITS: Map<String, Unit> =
        buildMap {
            fun add(
                kind: String,
                factor: Double,
                name: String,
                vararg keys: String,
            ) = keys.forEach { put(it, Unit(kind, factor, name)) }
            add("length", 1.0, "m", "m", "meter", "meters", "metre", "metres")
            add("length", 1000.0, "km", "km", "kilometer", "kilometers", "kilometre", "kilometres")
            add("length", 0.01, "cm", "cm", "centimeter", "centimeters")
            add("length", 0.001, "mm", "mm", "millimeter", "millimeters")
            add("length", 1609.344, "mi", "mi", "mile", "miles")
            add("length", 0.3048, "ft", "ft", "foot", "feet")
            add("length", 0.0254, "in", "inch", "inches")
            add("length", 0.9144, "yd", "yd", "yard", "yards")
            add("mass", 1.0, "kg", "kg", "kilogram", "kilograms", "kilo", "kilos")
            add("mass", 0.001, "g", "g", "gram", "grams")
            add("mass", 0.45359237, "lb", "lb", "lbs", "pound", "pounds")
            add("mass", 0.028349523125, "oz", "oz", "ounce", "ounces")
            add("volume", 1.0, "l", "l", "liter", "liters", "litre", "litres")
            add("volume", 0.001, "ml", "ml", "milliliter", "milliliters")
            add("volume", 3.785411784, "gal", "gal", "gallon", "gallons")
            add("volume", 0.0295735295625, "fl oz", "floz", "fl oz")
            add("volume", 0.2365882365, "cup", "cup", "cups")
            add("time", 1.0, "s", "s", "sec", "second", "seconds")
            add("time", 60.0, "min", "min", "minute", "minutes")
            add("time", 3600.0, "h", "h", "hr", "hour", "hours")
            add("time", 86400.0, "days", "day", "days")
            add("time", 604800.0, "weeks", "week", "weeks")
            add("data", 1.0, "B", "b", "byte", "bytes")
            add("data", 1e3, "KB", "kb")
            add("data", 1e6, "MB", "mb")
            add("data", 1e9, "GB", "gb")
            add("data", 1e12, "TB", "tb")
            add("speed", 1.0, "m/s", "m/s", "mps")
            add("speed", 0.44704, "mph", "mph")
            add("speed", 1 / 3.6, "km/h", "km/h", "kph", "kmh")
            add("temp", 0.0, "°C", "c", "°c", "celsius")
            add("temp", 0.0, "°F", "f", "°f", "fahrenheit")
            add("temp", 0.0, "K", "k", "kelvin")
        }

    private fun units(s: String): Answer? {
        val m = Regex("^(-?[\\d.]+)\\s*([a-z°/ ]+?)\\s+(?:to|in|as)\\s+([a-z°/ ]+)$").find(s) ?: return null
        val x = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = UNITS[m.groupValues[2].trim()] ?: return null
        val to = UNITS[m.groupValues[3].trim()] ?: return null
        if (from.kind != to.kind) return null
        val v =
            if (from.kind == "temp") {
                val c =
                    when (from.name) {
                        "°F" -> (x - 32) * 5 / 9
                        "K" -> x - 273.15
                        else -> x
                    }
                when (to.name) {
                    "°F" -> c * 9 / 5 + 32
                    "K" -> c + 273.15
                    else -> c
                }
            } else {
                x * from.factor / to.factor
            }
        return Answer(show(v) + " " + to.name, plain(v), "Units")
    }

    private fun round(v: Double) = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_EVEN)).stripTrailingZeros()

    fun plain(v: Double): String = if (v == 0.0) "0" else round(v).toPlainString()

    fun show(v: Double): String {
        if (v == 0.0) return "0"
        if (abs(v) >= 1e15 || abs(v) < 1e-6) return round(v).toString().replace("E+", "e").replace("E", "e")
        val p = round(v).toPlainString()
        val neg = p.startsWith("-")
        val body = p.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    /** A recursive-descent reader of sums: + - * / ^, a trailing %, brackets, and a few functions and constants. */
    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = sum()
            skip()
            require(i == s.length) { "left over" }
            return v
        }

        fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                if (i >= s.length) return v
                val op = s[i]
                if (op != '+' && op != '-') return v
                i++
                val r = product()
                // "84 + 15%" adds fifteen percent of 84, as a calculator's percent key does.
                val pct = lastPercent
                v = if (pct) (if (op == '+') v + v * r else v - v * r) else if (op == '+') v + r else v - r
            }
        }

        var lastPercent = false

        fun product(): Double {
            var v = power()
            while (true) {
                skip()
                if (i >= s.length) return v
                val op = s[i]
                if (op != '*' && op != '/') return v
                i++
                val r = power()
                v = if (op == '*') v * r else v / r
                lastPercent = false
            }
        }

        fun power(): Double {
            val b = unary()
            skip()
            if (i < s.length && s[i] == '^') {
                i++
                return b.pow(power())
            }
            return b
        }

        fun unary(): Double {
            skip()
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unary()
            }
            return postfix()
        }

        fun postfix(): Double {
            var v = atom()
            lastPercent = false
            skip()
            if (i < s.length && s[i] == '%') {
                i++
                v /= 100
                lastPercent = true
            }
            return v
        }

        fun atom(): Double {
            skip()
            require(i < s.length) { "end" }
            val c = s[i]
            if (c == '(') {
                i++
                val v = sum()
                skip()
                require(i < s.length && s[i] == ')') { "bracket" }
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && s[i] == 'e' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(start, i).toDouble()
            }
            if (c.isLetter() || c == 'π') {
                val start = i
                while (i < s.length && (s[i].isLetter() || s[i] == 'π')) i++
                val name = s.substring(start, i)
                when (name) {
                    "pi", "π" -> return Math.PI
                    "e" -> return Math.E
                }
                val arg = atom()
                return when (name) {
                    "sqrt" -> kotlin.math.sqrt(arg)
                    "abs" -> abs(arg)
                    "sin" -> kotlin.math.sin(arg)
                    "cos" -> kotlin.math.cos(arg)
                    "tan" -> kotlin.math.tan(arg)
                    "ln" -> kotlin.math.ln(arg)
                    "log" -> kotlin.math.log10(arg)
                    "round" -> kotlin.math.round(arg)
                    "floor" -> kotlin.math.floor(arg)
                    "ceil" -> kotlin.math.ceil(arg)
                    else -> error("unknown $name")
                }
            }
            error("unexpected $c")
        }
    }
}
