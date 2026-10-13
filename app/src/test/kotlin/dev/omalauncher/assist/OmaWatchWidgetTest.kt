package dev.omalauncher.assist

import dev.omalauncher.theme.LookWidgets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmaWatchWidgetTest {
    private fun rows(vararg quotes: Pair<String, Double?>) =
        JSONArray(quotes.map { (s, c) -> JSONObject().put("s", s).put("d", JSONArray(listOf(100.0, c ?: JSONObject.NULL))) })

    @Test fun theThreeBiggestGainersBestFirstByTheirPrintedNames() {
        val top = OmaWatchWidget.top(rows("COINBASE:BTCUSD" to 1.24, "AMEX:SPY" to -0.3, "NASDAQ:NVDA" to 4.06, "COINBASE:HYPEUSD" to 9.95, "NASDAQ:QQQ" to 0.0))
        assertEquals(listOf("HYPE", "NVDA", "BTC"), top.map { it.code })
        assertEquals(listOf("+10.0%", "+4.1%", "+1.2%"), top.map { it.change })
        assertTrue(top.all { it.up })
    }

    @Test fun onARedDayTheSmallestLosersWithATrueMinus() {
        val top = OmaWatchWidget.top(rows("AMEX:SPY" to -2.0, "NASDAQ:QQQ" to -0.04, "NASDAQ:NVDA" to -5.5, "COINBASE:ETHUSD" to -1.0))
        assertEquals(listOf("QQQ", "ETH", "SPY"), top.map { it.code })
        // What rounds to nothing prints as a rise of nothing, as the watchlist prints it.
        assertEquals(listOf("+0.0%", "−1.0%", "−2.0%"), top.map { it.change })
        assertEquals(listOf(true, false, false), top.map { it.up })
    }

    @Test fun aSymbolWithNoChangeYetIsLeftOutAndFewerThanThreeAreShownAsTheyAre() {
        val top = OmaWatchWidget.top(rows("CRYPTO:NEETUSD" to null, "AMEX:SPY" to 0.5))
        assertEquals(listOf("SPY"), top.map { it.code })
        assertTrue(OmaWatchWidget.top(JSONArray()).isEmpty())
    }

    @Test fun aCoinLosesItsDollarAndAStockKeepsItsName() {
        assertEquals("BTC", OmaWatchWidget.code("COINBASE:BTCUSD"))
        assertEquals("SPY", OmaWatchWidget.code("AMEX:SPY"))
        assertEquals("USD", OmaWatchWidget.code("X:USD"))
    }

    @Test fun keptValuesAreReadAsTheEngineWritesThemAndNothingElseIs() {
        val text = """{"v":1,"savedAt":5,"values":{"staged":"x","quotes":[{"s":"AMEX:SPY","d":[1,2]}],"quotesAt":1760000000}}"""
        val (quotes, at) = OmaWatchWidget.kept(text)!!
        assertEquals(1, quotes.length())
        assertEquals(1760000000L, at)
        assertNull(OmaWatchWidget.kept("""{"v":1,"values":{"quotes":[]}}"""))
        assertNull(OmaWatchWidget.kept("not json"))
    }

    @Test fun onlyFeedSymbolsAreAskedFor() {
        val ask = JSONObject(OmaWatchWidget.question(rows("AMEX:SPY" to 1.0, "bad symbol'; rm" to 1.0))!!)
        assertEquals("[\"AMEX:SPY\"]", ask.getJSONObject("symbols").getJSONArray("tickers").toString())
        assertEquals("[\"close\",\"change\"]", ask.getJSONArray("columns").toString())
        assertNull(OmaWatchWidget.question(rows("bad symbol" to 1.0)))
    }

    @Test fun pricesSayTheirAgeOnlyOnceItMatters() {
        assertEquals("", LookWidgets.ageText(14 * 60))
        assertEquals("20m old", LookWidgets.ageText(20 * 60))
        assertEquals("3h old", LookWidgets.ageText(3 * 3600 + 5))
        assertEquals("2d old", LookWidgets.ageText(2 * 86400))
    }
}
