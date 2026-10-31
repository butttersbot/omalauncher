package dev.omalauncher.hunch

import org.json.JSONObject
import java.math.BigDecimal

/** Listing prices come from each outcome side's actual ask, never a last-trade fallback or a guessed complement. */
object ListingQuote {
    fun read(market: Market, book: JSONObject, now: Long, side: Int = 0): JSONObject {
        val time = book.optLong("time", 0)
        val ask = Book.best(book, 1)
        val bid = Book.best(book, 0)
        val valid = market.quote == "USDC" && book.optString("coin") == market.coin(side) &&
            time > 0 && now - time in -5_000L..60_000L && ask != null && (bid == null || bid < ask)
        val status=when {
            market.quote!="USDC" || book.optString("coin")!=market.coin(side) -> "unavailable"
            time<=0 || now-time !in -5_000L..60_000L -> "stale"
            bid!=null && ask!=null && bid>=ask -> "invalid"
            ask==null -> "noAsks"
            else -> "live"
        }
        return JSONObject().put("id", market.id.toString()).put("time", time).put("valid", valid).put("status",status).apply {
            if (valid) {
                put("ask", ask!!.toPlainString())
                put("percent", (ask * BigDecimal(100)).stripTrailingZeros().toPlainString())
                put("side", market.sideLabel(side))
                put("depth", Book.levels(book,1).fold(BigDecimal.ZERO) { total, level -> total + level.price * level.size }.toPlainString())
            }
        }
    }
}
