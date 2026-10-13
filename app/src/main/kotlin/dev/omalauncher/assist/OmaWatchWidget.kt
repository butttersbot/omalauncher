package dev.omalauncher.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.omalauncher.theme.LookWidgets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The day's three biggest gainers of the owner's watchlist, as a scrap of the look in use; drawn by
 * [LookWidgets]. It reads the quotes the watchlist widget on home keeps, and since that widget only asks for
 * prices while home shows, asks for them itself when they have grown old. A name and a change only: no price
 * and no amount, on a screen anyone can read.
 */
class OmaWatchWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = LookWidgets.refresh(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = LookWidgets.refresh(context)

    /** One line of the widget: a ticker, its change over the day as it is printed, and whether that is a rise. */
    data class Gainer(
        val code: String,
        val change: String,
        val up: Boolean,
    )

    companion object {
        /** The watchlist widget's own kept values, by its file name. */
        private const val STATE = "widget-state/watchlist.owl.json"

        /** Its own copy of the quotes, for when it had to ask for them itself. */
        private const val OWN = "widget-state/watchlist-lock.json"
        private const val SCAN = "https://scanner.tradingview.com/global/scan"

        /** How many it shows. */
        const val SHOWN = 3

        /** Quotes older than this are asked for again, and no more often than that. */
        const val STALE_SECONDS = 120L

        private val SYMBOL = Regex("^[A-Z0-9:._!-]+$")

        @Volatile private var asking = false

        /** A tap goes home, where the whole watchlist is. */
        fun tap(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 2, Intent(context, dev.omalauncher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

        /** A feed symbol as the watchlist prints it: the code after the exchange, a coin without its "USD". */
        internal fun code(symbol: String): String {
            val name = symbol.substringAfterLast(':')
            return if (name.length > 3 && name.endsWith("USD")) name.dropLast(3) else name
        }

        /** A day's change as the watchlist prints it: one decimal and a true minus; a rise is what prints as one. */
        internal fun changeText(percent: Double): Pair<String, Boolean> {
            val tenths = (percent * 10).roundToInt()
            val up = tenths >= 0
            return (if (up) "+" else "−") + "${abs(tenths) / 10}.${abs(tenths) % 10}%" to up
        }

        /**
         * The [count] biggest gainers in [quotes], the scanner's rows (`s` a symbol, `d` its close and its change
         * in percent), best first. On a red day these are the smallest losers. A row with no change is left out.
         */
        internal fun top(
            quotes: JSONArray,
            count: Int = SHOWN,
        ): List<Gainer> =
            (0 until quotes.length())
                .mapNotNull { quotes.optJSONObject(it) }
                .mapNotNull { row ->
                    val change = row.optJSONArray("d")?.optDouble(1, Double.NaN) ?: Double.NaN
                    val symbol = row.optString("s")
                    if (change.isNaN() || symbol.isEmpty()) null else symbol to change
                }.sortedByDescending { it.second }
                .take(count)
                .map { (symbol, change) -> changeText(change).let { (text, up) -> Gainer(code(symbol), text, up) } }

        /** The quotes and when they were had, in seconds, from kept values as the widget engine writes them; null if there are none. */
        internal fun kept(text: String): Pair<JSONArray, Long>? =
            runCatching {
                val values = JSONObject(text).getJSONObject("values")
                val quotes = values.getJSONArray("quotes")
                if (quotes.length() == 0) null else quotes to values.optLong("quotesAt")
            }.getOrNull()

        /** The scanner's question for the symbols in [quotes]; only what is made of feed-symbol characters is asked for. */
        internal fun question(quotes: JSONArray): String? {
            val symbols = (0 until quotes.length()).mapNotNull { quotes.optJSONObject(it)?.optString("s") }.filter { SYMBOL.matches(it) }
            if (symbols.isEmpty()) return null
            return JSONObject()
                .put("symbols", JSONObject().put("tickers", JSONArray(symbols)).put("query", JSONObject().put("types", JSONArray())))
                .put("columns", JSONArray(listOf("close", "change")))
                .toString()
        }

        /** The newer of the watchlist's quotes and this widget's own. */
        private fun newest(context: Context): Pair<JSONArray, Long>? =
            listOf(STATE, OWN)
                .mapNotNull { name -> runCatching { File(context.filesDir, name).readText() }.getOrNull()?.let(::kept) }
                .maxByOrNull { it.second }

        /** What to show now, and how many seconds old it is; empty until the watchlist has had prices once. */
        fun shown(
            context: Context,
            now: Long = System.currentTimeMillis() / 1000,
        ): Pair<List<Gainer>, Long> = newest(context)?.let { (quotes, at) -> top(quotes) to (now - at).coerceAtLeast(0) } ?: (emptyList<Gainer>() to 0L)

        /**
         * Asks for fresh prices when the ones kept are old, off the main thread, and has the widgets drawn again
         * when they come. One question at a time; a failure leaves what was shown.
         */
        fun freshen(context: Context) {
            val app = context.applicationContext
            val (quotes, at) = newest(app) ?: return
            val now = System.currentTimeMillis() / 1000
            if (asking || now - at < STALE_SECONDS) return
            val ask = question(quotes) ?: return
            asking = true
            Thread({
                try {
                    val link = (URL(SCAN).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 8000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    link.outputStream.use { it.write(ask.toByteArray()) }
                    val rows = JSONObject(link.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
                    if (rows.length() > 0) {
                        val values = JSONObject().put("quotes", rows).put("quotesAt", System.currentTimeMillis() / 1000)
                        File(app.filesDir, OWN).apply { parentFile?.mkdirs() }.writeText(JSONObject().put("values", values).toString())
                        LookWidgets.refresh(app, watchOnly = true)
                    }
                } catch (_: Exception) {
                    // Offline, or the feed said no: the prices already shown stand, with their age.
                } finally {
                    asking = false
                }
            }, "watch-quotes").start()
        }
    }
}
