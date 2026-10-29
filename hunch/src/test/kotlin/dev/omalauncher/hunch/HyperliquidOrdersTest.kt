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
    @Test fun venueSpecificRejectionsAreTerminalButUnknownOidNeverMeansCancelled() {
        for(status in listOf("rejected","tickRejected","minTradeNtlRejected","iocCancelRejected","insufficientSpotBalanceRejected","tooManyOpenOrdersRejected")) {
            assertEquals(OrderStage.Rejected,HyperliquidOrders.statusStage(JSONObject("""{"status":"order","order":{"status":"$status"}}""")))
        }
        for(status in listOf("outcomeSettledCanceled","scheduledCancel","internalCancel"))assertEquals(OrderStage.Cancelled,HyperliquidOrders.statusStage(JSONObject("""{"status":"order","order":{"status":"$status"}}""")))
        assertEquals(OrderStage.Unknown,HyperliquidOrders.statusStage(JSONObject("""{"status":"unknownOid"}""")))
    }
    @Test fun sdkEnvelopeCannotChangeTheNativeReviewOrAddAnOrder() {
        val now=1800000000000L
        val ticket=OrderTicket("0x1111111111111111111111111111111111111111",100000010,"Fixture","Yes",BigDecimal("0.5"),BigDecimal("20"),BigDecimal("10"),BigDecimal.ZERO,BigDecimal("10"),now)
        val cloid="0x11111111111111111111111111111111"
        fun body()=JSONObject("""{"action":{"type":"order","orders":[{"a":100000010,"b":true,"p":"0.50","s":"20","r":false,"t":{"limit":{"tif":"Ioc"}},"c":"$cloid"}],"grouping":"na"},"nonce":$now,"expiresAfter":${now+30000}}""")
        HyperliquidOrders.validate(body(),ticket,cloid,now)
        for((key,value) in listOf("a" to 100000011,"b" to false,"p" to "0.6","s" to "21","r" to true,"c" to "0x22222222222222222222222222222222")) {
            val changed=body();changed.getJSONObject("action").getJSONArray("orders").getJSONObject(0).put(key,value)
            refuses{HyperliquidOrders.validate(changed,ticket,cloid,now)}
        }
        for(changed in listOf(body().put("nonce",now+1),body().put("expiresAfter",now+30001),body().put("vaultAddress",ticket.wallet)))refuses{HyperliquidOrders.validate(changed,ticket,cloid,now)}
        val extra=body();extra.getJSONObject("action").getJSONArray("orders").put(JSONObject(extra.getJSONObject("action").getJSONArray("orders").getJSONObject(0).toString()))
        refuses{HyperliquidOrders.validate(extra,ticket,cloid,now)}
    }
    @Test fun cancellationCannotTargetAnotherOrderAssetOrNonce() {
        val now=1800000000000L;val cloid="0x11111111111111111111111111111111"
        val ticket=OrderTicket("0x1111111111111111111111111111111111111111",100000010,"Fixture","Yes",BigDecimal("0.5"),BigDecimal("20"),BigDecimal("10"),BigDecimal.ZERO,BigDecimal("10"),now)
        fun body()=JSONObject("""{"action":{"type":"cancelByCloid","cancels":[{"asset":100000010,"cloid":"$cloid"}]},"nonce":$now,"expiresAfter":${now+30000}}""")
        HyperliquidOrders.validateCancel(body(),ticket,cloid,now)
        for((key,value) in listOf("asset" to 100000011,"cloid" to "0x22222222222222222222222222222222")) {
            val changed=body();changed.getJSONObject("action").getJSONArray("cancels").getJSONObject(0).put(key,value)
            refuses{HyperliquidOrders.validateCancel(changed,ticket,cloid,now)}
        }
        refuses{HyperliquidOrders.validateCancel(body().put("nonce",now+1),ticket,cloid,now)}
        val extra=body();extra.getJSONObject("action").getJSONArray("cancels").put(JSONObject("""{"asset":100000011,"cloid":"$cloid"}"""))
        refuses{HyperliquidOrders.validateCancel(extra,ticket,cloid,now)}
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
