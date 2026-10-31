package dev.omalauncher.hunch

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Exact outcome-coin contexts; never use the ordinary spot array position as an outcome ID. */
object MarketVolumes {
    fun read(markets: List<Market>, response: JSONArray, checked: Long): JSONArray {
        val contexts=response.getJSONArray(1)
        val values=mutableMapOf<String,BigDecimal>()
        for(i in 0 until contexts.length()) {
            val row=contexts.getJSONObject(i)
            val volume=row.optString("dayNtlVlm").toBigDecimalOrNull()
            if(volume!=null && volume>=BigDecimal.ZERO) values[row.optString("coin")]=volume
        }
        return JSONArray(markets.map { market ->
            val yes=values[market.coin(0)];val no=values[market.coin(1)]
            JSONObject().put("id",market.id.toString()).put("checked",checked).apply {
                // Both sides must be available to label this as total market volume.
                if(market.quote=="USDC" && yes!=null && no!=null) put("volume24h",(yes+no).toPlainString())
            }
        })
    }
}
