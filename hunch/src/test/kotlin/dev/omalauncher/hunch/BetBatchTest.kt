package dev.omalauncher.hunch

import java.math.BigDecimal
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BetBatchTest {
    private val now=1_800_000_000_000L
    private val wallet="0x1111111111111111111111111111111111111111"
    private val owner=OrderWallet(wallet)
    private val rules=VenueOrderRules(100000010,"Fictional first market","Yes",d("0.00000001"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,now)
    private fun d(value:String)=BigDecimal(value)
    private fun funds(cash:String="1000")=FundingSnapshot(wallet,now,null,null,d(cash))
    private fun book(coin:String="#10",time:Long=now,bid:String="0.38",ask:String="0.4",size:String="1000")=JSONObject().put("coin",coin).put("time",time).put("levels",JSONArray().put(JSONArray().put(JSONObject().put("px",bid).put("sz","1000"))).put(JSONArray().put(JSONObject().put("px",ask).put("sz",size))))
    private fun ticket(budget:String="20",fee:String="0.001")=BetBatch.fromBudget(owner,rules,book(),"#10",d(budget),d(fee),funds(),now,true)
    private fun refuses(block:()->Unit){try{block();fail("Must refuse")}catch(_:IllegalArgumentException){}}
    private fun blocked(block:()->Unit){try{block();fail("Must block")}catch(_:IllegalStateException){}}
    @Test fun maximumRiskIncludesFeesAndRoundsSharesDown(){
        val t=BetBatch.fromBudget(owner,rules,book(ask="0.8348"),"#10",d("20.00"),d("0.0007"),funds(),now,true)
        assertEquals(d("23"),t.shares);assertTrue(t.maximumDebit<=d("20"));assertEquals(t.notional+t.feeCeiling,t.maximumDebit)
        assertEquals("Ioc",t.timeInForce);assertTrue(t.buy)
    }
    @Test fun feeCeilingsNeverIncreaseTheEnteredRisk(){
        for(fee in listOf("0","0.000000001","0.0007","0.1","1")){
            val t=ticket("20.01",fee);assertTrue(t.maximumDebit<=d("20.01"));assertEquals(BigDecimal.ZERO,t.shares.remainder(BigDecimal.ONE))
        }
    }
    @Test fun entireBatchMustFitAvailableCashIncludingAllFees(){
        val t=ticket();BetBatch.requireAffordable(listOf(t,t.copy(asset=100000020)),funds("40"),now)
        refuses{BetBatch.requireAffordable(listOf(t,t.copy(asset=100000020)),funds("30"),now)}
        refuses{BetBatch.requireAffordable(listOf(t,t.copy(testnet=false)),funds(),now)}
        refuses{BetBatch.requireAffordable(listOf(t),funds(),now+30001)}
    }
    @Test fun wrongCoinEmptyCrossedStaleFutureAndTinyBudgetsRefuse(){
        for(b in listOf(book(coin="#11"),book(time=now-30001),book(time=now+5001),book(bid="0.6"),book(size="0")))refuses{BetBatch.fromBudget(owner,rules,b,"#10",d("20"),d("0"),funds(),now,true)}
        refuses{ticket("0.01")};refuses{ticket("-1")}
    }
    @Test fun noApplicationSpendCapIsIntroduced(){
        val t=BetBatch.fromBudget(owner,rules,book(size="1000000"),"#10",d("10000.00"),d("0.001"),funds("20000"),now,true)
        assertTrue(t.maximumDebit>d("1000"));assertTrue(t.maximumDebit<=d("10000"))
    }
    @Test fun depthCannotCreateSharesThatArentAvailable(){
        val t=BetBatch.fromBudget(owner,rules,book(size="3"),"#10",d("20"),d("0"),funds(),now,true)
        assertEquals(d("3"),t.shares);assertTrue(t.maximumDebit<=d("20"))
    }
    @Test fun batchUsesDistinctCloidsAndOneDurableNonceBeforeSubmit(){
        val dir=Files.createTempDirectory("hunch-batch").toFile()
        try{OrderJournal(dir.resolve("orders.json")).use{journal->
            val tickets=listOf(ticket(),ticket().copy(asset=100000020))
            val rows=journal.reviewBatch(tickets,listOf("first","second"),now);val batch=rows.first().ticket.getString("batchId")
            assertEquals(2,rows.map{it.cloid}.distinct().size);assertEquals(1,rows.map{it.ticket.getString("batchId")}.distinct().size)
            journal.beginBatch(batch,now+1);assertTrue(dir.resolve("orders.json").readText().contains("Signing"))
            assertTrue(journal.all().all{it.ticket.getLong("nonce")==now+1&&it.ticket.getLong("expiresAfter")==now+30000})
            journal.submitBatch(batch,now+2);assertTrue(journal.all().all{it.stage==OrderStage.Submitting&&it.ticket.getBoolean("submittedBatch")})
            blocked{journal.review(ticket(),now+3)}
            val finished=journal.finishBatch(batch,listOf(OrderStage.Filled to "1",OrderStage.Rejected to null),now+3)
            assertEquals(listOf(OrderStage.Filled,OrderStage.Rejected),finished.map{it.stage})
            blocked{journal.reviewBatch(tickets,listOf("first","second"),now+4)}
        }}finally{dir.deleteRecursively()}
    }
    @Test fun crashAfterSubmissionRestoresEveryChildAsUnknownAndNeverResends(){
        val dir=Files.createTempDirectory("hunch-batch-crash").toFile();val file=dir.resolve("orders.json")
        try{
            val ids=OrderJournal(file).use{journal->val rows=journal.reviewBatch(listOf(ticket(),ticket()),listOf("a","b"),now);val batch=rows.first().ticket.getString("batchId");journal.beginBatch(batch,now);journal.submitBatch(batch,now);rows.map{it.cloid}}
            OrderJournal(file).use{journal->assertEquals(ids,journal.all().map{it.cloid});assertTrue(journal.all().all{it.stage==OrderStage.Unknown});blocked{journal.reviewBatch(listOf(ticket()),listOf("c"),now+1)}}
        }finally{dir.deleteRecursively()}
    }
    @Test fun interruptedSigningIsKnownPreSendButUnknownChildBlocksFurtherBets(){
        val dir=Files.createTempDirectory("hunch-batch-sign").toFile();val file=dir.resolve("orders.json")
        try{
            OrderJournal(file).use{j->val rows=j.reviewBatch(listOf(ticket()),listOf("a"),now);j.beginBatch(rows.first().ticket.getString("batchId"),now)}
            OrderJournal(file).use{j->assertEquals(OrderStage.Cancelled,j.all().single().stage);val rows=j.reviewBatch(listOf(ticket(),ticket()),listOf("a","b"),now);val id=rows.first().ticket.getString("batchId");j.beginBatch(id,now);j.submitBatch(id,now);j.finishBatch(id,listOf(OrderStage.Filled to "1",OrderStage.Unknown to null),now);blocked{j.review(ticket(),now)}}
        }finally{dir.deleteRecursively()}
    }
    @Test fun missingOrReorderedBatchResultsNeverBecomeAnAllFilledClaim(){
        val tickets=listOf(ticket(),ticket().copy(asset=100000020))
        val mixed=JSONObject("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"filled":{"oid":1,"totalSz":"2","avgPx":"0.4"}},{"error":"Fictional no liquidity"}]}}}""")
        assertEquals(listOf(OrderStage.Filled,OrderStage.Rejected),HyperliquidOrders.batchStages(mixed,tickets).map{it.first})
        mixed.getJSONObject("response").getJSONObject("data").getJSONArray("statuses").remove(1)
        assertTrue(HyperliquidOrders.batchStages(mixed,tickets).all{it.first==OrderStage.Unknown})
        assertTrue(HyperliquidOrders.batchStages(JSONObject("""{"status":"err","response":"Rejected"}"""),tickets).all{it.first==OrderStage.Rejected})
    }
    @Test fun overfilledOrOverpricedResultStaysUnknownForThatChild(){
        val tickets=listOf(ticket(),ticket())
        val response=JSONObject("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"filled":{"oid":1,"totalSz":"999999","avgPx":"0.4"}},{"filled":{"oid":2,"totalSz":"2","avgPx":"0.4"}}]}}}""")
        assertEquals(listOf(OrderStage.Unknown,OrderStage.Filled),HyperliquidOrders.batchStages(response,tickets).map{it.first})
    }
    @Test fun validatesEverySignedOrderAgainstTheExactNativeReview(){
        val dir=Files.createTempDirectory("hunch-batch-envelope").toFile()
        try{OrderJournal(dir.resolve("orders.json")).use{j->
            val tickets=listOf(ticket(),ticket().copy(asset=100000020));val rows=j.reviewBatch(tickets,listOf("a","b"),now)
            val orders=JSONArray(tickets.mapIndexed{i,t->JSONObject().put("a",t.asset).put("b",true).put("p",t.limitPrice.toPlainString()).put("s",t.shares.toPlainString()).put("r",false).put("t",JSONObject().put("limit",JSONObject().put("tif","Ioc"))).put("c",rows[i].cloid)})
            val body=JSONObject().put("action",JSONObject().put("type","order").put("orders",orders).put("grouping","na")).put("nonce",now).put("expiresAfter",now+30000).put("vaultAddress",JSONObject.NULL)
            HyperliquidOrders.validateBatch(body,tickets,rows,now)
            orders.getJSONObject(1).put("s","99999");refuses{HyperliquidOrders.validateBatch(body,tickets,rows,now)}
            orders.getJSONObject(1).put("s",tickets[1].shares.toPlainString()).put("c",rows[0].cloid);refuses{HyperliquidOrders.validateBatch(body,tickets,rows,now)}
        }}finally{dir.deleteRecursively()}
    }
    @Test fun confirmedPartialFillDetailsPersistAcrossReopen(){
        val dir=Files.createTempDirectory("hunch-partial-persist").toFile();val file=dir.resolve("orders.json")
        try{
            OrderJournal(file).use{j->val row=j.reviewBatch(listOf(ticket()),listOf("a"),now).single();val batch=row.ticket.getString("batchId");j.beginBatch(batch,now);j.submitBatch(batch,now);j.finishBatch(batch,listOf(OrderStage.Filled to "1"),now,listOf(JSONObject("""{"filled":{"totalSz":"2","avgPx":"0.4"}}""")))}
            OrderJournal(file).use{j->assertEquals("2",j.all().single().ticket.getString("filledSz"));assertEquals("0.4",j.all().single().ticket.getString("fillAvgPx"))}
        }finally{dir.deleteRecursively()}
    }

}
