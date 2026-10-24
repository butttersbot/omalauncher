package dev.omalauncher.hunch

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class Market(val id: Long, val venue: String, val template: String, val fields: Map<String,String>, val quote: String,
    val sides: List<String> = listOf("Yes", "No"), val question: String = "") {
    val category get() = when { template.startsWith("sports") -> "Sports"; template.contains("Price",true)||template.startsWith("Recurring") -> "Prices"; template.startsWith("company") -> "Companies"; template.startsWith("policy") -> "Rates"; else -> "Events" }
    val venueName get() = when(venue){"out"->"Outcome";"txyz"->"Trade.xyz";"skew"->"Skew";"fox"->"Fox";""->"Hyperliquid";else->venue}
    val title: String get() {
        val pair=listOfNotNull(fields["participantA"], fields["participantB"]).joinToString(" vs ")
        return when {
            pair.isNotEmpty() -> when(template){"sportsTotal"->"$pair · ${fields["line"].orEmpty()} ${fields["measure"].orEmpty()}";"sportsContestDraw2"->"$pair · draw?";"sportsContestParticipant2"->"$pair · ${fields["participant"] ?: "selected participant"}";"template fallback"->"$pair · other result";"sportsSpread"->"$pair · spread ${fields["spread"] ?: fields["line"].orEmpty()}";else->pair}
            fields["company"]!=null -> when(template){"companyIpoConfirmed"->"${fields["company"]}: IPO confirmed?";"companyIpoFirstDayMarketCap"->"${fields["company"]}: first-day value > $${fields["marketCapThresholdB"].orEmpty()}B?";else->fields.getValue("company")}
            fields["perp"]!=null -> "${fields.getValue("perp").substringAfter(':')} ${if(template=="priceTouch") "touches" else "above"} ${fields["threshold"] ?: fields["targetPrice"] ?: "target"}?"
            fields["participant"]!=null -> "${fields["participant"]}${if(question.isNotBlank()) " · $question" else " · tournament outcome"}"
            fields["candidate"]!=null -> "${fields["candidate"]} · ${fields["award"] ?: "award outcome"}"
            question.isNotBlank() -> question
            template.startsWith("policy") -> "Policy rate: ${when(template){"policyRateIncrease"->"increase";"policyRateDecrease"->"decrease";else->"no change"}}"
            else -> "Market $id · description unavailable"
        }
    }
    val deadline get() = fields["resolutionDeadline"] ?: fields["listingDeadline"] ?: fields["time"] ?: fields["dateTime"] ?: fields["expiry"]
    // Group only exact semantic terms; no guessed cross-venue equivalence or order routing.
    val identity get() = if(fields.isEmpty()) "id:$id" else "$template|$quote|$sides|$question|"+fields.toSortedMap().entries.joinToString("|"){"${it.key}:${it.value}"}
    fun coin(side: Int): String { require(side in 0..1); return "#${Math.addExact(Math.multiplyExact(id,10), side.toLong())}" }
    fun sideLabel(side:Int)=sides.getOrElse(side){if(side==0)"Yes" else "No"}
    companion object {
        fun parse(root: JSONObject): List<Market> {
            fun terms(s:String)=s.split('|').mapNotNull { val i=it.indexOf(':');if(i>0)it.substring(0,i) to it.substring(i+1)else null }.toMap()
            val parent=mutableMapOf<Long,Map<String,String>>()
            val questions=root.optJSONArray("questions")
            if(questions!=null)for(i in 0 until questions.length()){
                val q=questions.getJSONObject(i);val f=terms(q.optString("description"))+mapOf("questionId" to q.getLong("question").toString())
                val named=q.optJSONArray("namedOutcomes")
                if(named!=null)for(n in 0 until named.length())parent[named.getLong(n)]=f
                if(q.has("fallbackOutcome"))parent[q.getLong("fallbackOutcome")]=f
            }
            val all = root.getJSONArray("outcomes")
            return (0 until all.length()).map { all.getJSONObject(it) }.map { x ->
                val id=x.getLong("outcome"); require(id>=0)
                val ss=x.optJSONArray("sideSpecs")
                Market(id,x.optString("venue"),x.getString("name").removePrefix("template:"),parent[id].orEmpty()+terms(x.optString("description")),x.optString("quoteToken","Unknown"),(0..1).map{ss?.optJSONObject(it)?.optString("name")?.removePrefix("template:")?.takeIf{n->n.isNotBlank()} ?: if(it==0)"Yes" else "No"})
            }.distinctBy { it.id }
        }
        fun groups(markets:List<Market>)=markets.groupBy { it.identity }.values.map { it.sortedBy { m->m.venueName } }
    }
}
data class Level(val price:BigDecimal,val size:BigDecimal)
data class Quote(val spent:BigDecimal,val grossPayout:BigDecimal,val complete:Boolean)
object Book {
    fun levels(root:JSONObject, side:Int):List<Level> {
        require(side in 0..1)
        val rows=root.getJSONArray("levels").getJSONArray(side)
        val found=(0 until rows.length()).mapNotNull { i ->
            val row=rows.getJSONObject(i);val px=row.optString("px").toBigDecimalOrNull();val sz=row.optString("sz").toBigDecimalOrNull()
            if(px!=null && px>BigDecimal.ZERO && px<BigDecimal.ONE && sz!=null && sz>BigDecimal.ZERO)Level(px,sz) else null
        }
        return if(side==0)found.sortedByDescending{it.price} else found.sortedBy{it.price}
    }
    fun best(root:JSONObject,side:Int)=levels(root,side).firstOrNull()?.price
    fun preview(asks:List<Level>,budget:BigDecimal):Quote {
        require(budget>BigDecimal.ZERO)
        var left=budget;var shares=BigDecimal.ZERO
        for(l in asks.sortedBy{it.price}) {
            require(l.price>BigDecimal.ZERO && l.price<BigDecimal.ONE && l.size>BigDecimal.ZERO)
            val spend=left.min(l.price*l.size)
            shares+=spend.divide(l.price,12,RoundingMode.DOWN);left-=spend
            if(left<=BigDecimal("0.000001"))break
        }
        return Quote(budget-left,shares,left<=BigDecimal("0.000001"))
    }
    fun money(n:BigDecimal)=String.format(Locale.US,"$%.2f",n)
}
