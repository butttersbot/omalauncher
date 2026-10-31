package dev.omalauncher.hunch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListingQuoteTest {
    private val now = 1_800_000_000_000L
    private val market = Market(1,"out","binaryPrice",emptyMap(),"USDC")
    private fun book(bid:String="0.39",ask:String="0.415",time:Long=now,coin:String="#10") = JSONObject()
        .put("coin",coin).put("time",time).put("levels",org.json.JSONArray()
            .put(org.json.JSONArray().put(JSONObject().put("px",bid).put("sz","20")))
            .put(org.json.JSONArray().put(JSONObject().put("px",ask).put("sz","30"))))

    @Test fun chanceIsImpliedBySideZeroAskWithoutRoundingToWholeCents() {
        val q=ListingQuote.read(market,book(),now)
        assertTrue(q.getBoolean("valid"));assertEquals("0.415",q.getString("ask"))
        assertEquals("41.5",q.getString("percent"));assertEquals("Yes",q.getString("side"))
    }
    @Test fun wrongCoinStaleFutureCrossedAndNonUsdcAreNeverDisplayed() {
        for (b in listOf(book(coin="#11"),book(time=now-60001),book(time=now+5001),book(bid="0.5"),book(ask="1"))) {
            val q=ListingQuote.read(market,b,now);assertFalse(q.getBoolean("valid"));assertFalse(q.has("ask"))
        }
        assertFalse(ListingQuote.read(market.copy(quote="Unknown"),book(),now).getBoolean("valid"))
    }
    @Test fun emptyBooksDoNotBecomeZeroOrComplementPrices() {
        val q=ListingQuote.read(market,JSONObject().put("coin","#10").put("time",now).put("levels",org.json.JSONArray().put(org.json.JSONArray()).put(org.json.JSONArray())),now)
        assertFalse(q.getBoolean("valid"));assertFalse(q.has("percent"))
    }
    @Test fun noAskComesFromItsOwnCoinAndDepthUsesNotional() {
        val q=ListingQuote.read(market,book(coin="#11",ask="0.62"),now,1)
        assertTrue(q.getBoolean("valid"));assertEquals("No",q.getString("side"));assertEquals("0.62",q.getString("ask"))
        assertEquals(0,q.getString("depth").toBigDecimal().compareTo(java.math.BigDecimal("18.60")))
        assertFalse(ListingQuote.read(market,book(coin="#10"),now,1).getBoolean("valid"))
    }

}
