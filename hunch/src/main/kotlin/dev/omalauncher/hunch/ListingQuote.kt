package dev.omalauncher.hunch

import org.json.JSONObject
import java.math.BigDecimal

/** Listing prices come from that outcome's side-zero ask, never a last-trade fallback or a guessed complement. */
object ListingQuote {
    fun read(market: Market, book: JSONObject, now: Long): JSONObject {
        val time = book.optLong("time", 0)
        val ask = Book.best(book, 1)
        val bid = Book.best(book, 0)
        val valid = market.quote == "USDC" && book.optString("coin") == market.coin(0) &&
            time > 0 && now - time in -5_000L..60_000L && ask != null && (bid == null || bid < ask)
        return JSONObject().put("id", market.id.toString()).put("time", time).put("valid", valid).apply {
            if (valid) {
                put("ask", ask!!.toPlainString())
                put("percent", (ask * BigDecimal(100)).stripTrailingZeros().toPlainString())
                put("side", market.sideLabel(0))
            }
        }
    }
}
