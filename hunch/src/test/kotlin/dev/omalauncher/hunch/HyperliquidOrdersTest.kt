package dev.omalauncher.hunch

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HyperliquidOrdersTest {
    private fun refuses(block:()->Unit){try{block();fail("Must refuse")}catch(_:IllegalArgumentException){}}
    @Test fun balancesSubtractHoldsAndNeverBorrowOrConfuseSideTokens(){
        val state=JSONObject("""{"balances":[{"coin":"USDC","total":"12","hold":"2"},{"coin":"+10","total":"4","hold":"1"},{"coin":"+11","total":"90","hold":"0"}]}""")
        assertEquals(BigDecimal("10"),HyperliquidOrders.available(state,"USDC"))
        assertEquals(BigDecimal("3"),HyperliquidOrders.available(state,"+10"))
        assertEquals(BigDecimal.ZERO,HyperliquidOrders.available(state,"+20"))
        refuses{HyperliquidOrders.available(JSONObject(state.toString()).put("portfolioMarginEnabled",true),"+10")}
        refuses{HyperliquidOrders.available(JSONObject("""{"balances":[{"coin":"USDC","total":"1","hold":"2"}]}"""),"USDC")}
    }
    @Test fun responseDistinguishesFilledRestingRejectedAndUnknown(){
        fun reply(s:String)=JSONObject("""{"status":"ok","response":{"type":"order","data":{"statuses":[$s]}}}""")
        assertEquals(OrderStage.Resting to "10",HyperliquidOrders.replyStage(reply("""{"resting":{"oid":10}}""")))
        assertEquals(OrderStage.Filled to "11",HyperliquidOrders.replyStage(reply("""{"filled":{"oid":11,"totalSz":"1","avgPx":"0.5"}}""")))
        assertEquals(OrderStage.Rejected,HyperliquidOrders.replyStage(reply("""{"error":"Insufficient funds"}""")).first)
        assertEquals(OrderStage.Unknown,HyperliquidOrders.replyStage(reply("{}" )).first)
        assertEquals(OrderStage.Unknown,HyperliquidOrders.statusStage(JSONObject("""{"status":"unknownOid"}""")))
        assertEquals(OrderStage.Cancelled,HyperliquidOrders.statusStage(JSONObject("""{"status":"order","order":{"status":"iocCancelCanceled"}}""")))
    }
    @Test fun sellingUsesOwnedFreeSharesAndNeverCreatesAnUncoveredPosition(){
        val now=1800000000000L;val wallet="0x1111111111111111111111111111111111111111"
        val rules=VenueOrderRules(100000010,"Fixture","Yes",BigDecimal("0.01"),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,now)
        val funds=FundingSnapshot(wallet,now,null,null,BigDecimal.ZERO)
        fun ticket(shares:String,available:String,at:Long=now)=OrderTickets.prepareFunded(OrderWallet(wallet),rules,BigDecimal("0.5"),BigDecimal(shares),BigDecimal("0.01"),funds,at,false,"Gtc",true,BigDecimal(available))
        val sell=ticket("4","4")
        assertFalse(sell.buy);assertTrue(sell.testnet);assertEquals("Gtc",sell.timeInForce)
        assertEquals(BigDecimal.ZERO,sell.maximumDebit);assertEquals(BigDecimal("0.04"),sell.feeCeiling)
        refuses{ticket("5","4")};refuses{ticket("4","4",now+30001)}
    }
}
