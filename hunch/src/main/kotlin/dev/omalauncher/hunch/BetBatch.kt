package dev.omalauncher.hunch

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/** Stake is a maximum INCLUDING the fee ceiling. Fresh native books set a bounded IOC price. */
internal object BetBatch {
    fun fromBudget(owner:OrderWallet,rules:VenueOrderRules,book:JSONObject,coin:String,budget:BigDecimal,rate:BigDecimal,funding:FundingSnapshot,now:Long,testnet:Boolean):OrderTicket {
        require(budget>BigDecimal.ZERO && budget.scale()<=2 && budget.precision()<=14){"Enter a positive stake with up to two decimals"}
        require(rate>=BigDecimal.ZERO && rate<=BigDecimal.ONE)
        require(book.optString("coin")==coin && book.optLong("time")>0 && now-book.getLong("time") in -5000L..30000L){"Refresh the market quote"}
        val asks=Book.levels(book,1);val bid=Book.best(book,0)
        require(asks.isNotEmpty() && (bid==null || bid<asks.first().price)){"No current executable ask"}
        var depth=BigDecimal.ZERO;var bestPrice=BigDecimal.ZERO;var bestSize=BigDecimal.ZERO
        for(level in asks){
            depth+=level.size
            val perShare=level.price*(BigDecimal.ONE+rate)
            var size=budget.divide(perShare,0,RoundingMode.DOWN).min(depth.setScale(0,RoundingMode.DOWN))
            // A rounded-up fee ceiling must still fit inside the entered maximum.
            if(size>BigDecimal.ZERO && level.price*size+(level.price*size*rate).setScale(8,RoundingMode.CEILING)>budget)size-=BigDecimal.ONE
            if(size>bestSize){bestSize=size;bestPrice=level.price}
        }
        require(bestSize>BigDecimal.ZERO){"Stake or available depth is below one outcome share"}
        require(bestPrice.stripTrailingZeros().precision()<=5 && bestPrice.stripTrailingZeros().scale()<=8){"Ask exceeds venue precision"}
        val ticket=OrderTickets.prepareFunded(owner,rules,bestPrice,bestSize,rate,funding,now,true,"Ioc",testnet)
        require(ticket.maximumDebit<=budget){"Fees exceed the entered maximum"};return ticket
    }
    fun requireAffordable(tickets:List<OrderTicket>,funding:FundingSnapshot,now:Long){
        require(tickets.isNotEmpty() && tickets.size<=20)
        require(tickets.map{it.wallet}.distinct().size==1 && tickets.map{it.testnet}.distinct().size==1 && tickets.all{it.buy&&it.timeInForce=="Ioc"&&it.fresh(now)})
        val total=tickets.fold(BigDecimal.ZERO){n,t->n+t.maximumDebit}
        require(FundingChecks.check(funding,tickets.first().wallet,total,now).tradingCashReady){"Not enough available trading cash for all bets including fees"}
    }
}
