package dev.omalauncher.hunch

import org.json.JSONObject
import java.math.BigDecimal

data class Market(val id: Long, val venue: String, val template: String, val fields: Map<String,String>, val quote: String) {
    val title: String get() = listOfNotNull(fields["participantA"], fields["participantB"]).takeIf { it.isNotEmpty() }?.joinToString(" vs ")
        ?: fields["participant"] ?: fields["company"] ?: fields["perp"]?.let { "$it · ${fields["threshold"].orEmpty()}" } ?: "$template · $id"
    fun coin(side: Int): String { require(side in 0..1); return "#${Math.addExact(Math.multiplyExact(id,10), side.toLong())}" }
    companion object {
        fun parse(root: JSONObject): List<Market> {
            val all = root.getJSONArray("outcomes")
            return (0 until all.length()).map { all.getJSONObject(it) }.map { x ->
                val id=x.getLong("outcome"); require(id>=0)
                Market(id,x.optString("venue"),x.getString("name").removePrefix("template:"),x.optString("description").split('|').mapNotNull {
                    val at=it.indexOf(':'); if(at>0) it.substring(0,at) to it.substring(at+1) else null
                }.toMap(),x.optString("quoteToken","Unknown"))
            }
        }
    }
}
object Book {
    // An executable price is a real order with positive size, never a synthetic midpoint.
    fun best(root: JSONObject, side: Int): BigDecimal? {
        val rows=root.getJSONArray("levels").getJSONArray(side)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstNotNullOfOrNull {
            val px=it.optString("px").toBigDecimalOrNull(); val sz=it.optString("sz").toBigDecimalOrNull()
            px?.takeIf { p -> p>BigDecimal.ZERO && p<BigDecimal.ONE && sz!=null && sz>BigDecimal.ZERO }
        }
    }
}
