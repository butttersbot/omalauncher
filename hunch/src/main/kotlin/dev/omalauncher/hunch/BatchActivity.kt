package dev.omalauncher.hunch

import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import io.privy.auth.AuthState
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.math.BigDecimal

/** One owner-confirmed signed SDK envelope; every child has its own durable cloid and venue result. */
class BatchActivity:PaperActivity() {
    private val scope=MainScope()
    private var journal:OrderJournal?=null
    private lateinit var drafts:BetDrafts
    private var selected=emptyList<JSONObject>()
    private var tickets=emptyList<OrderTicket>()
    private var rows=emptyList<JournalOrder>()
    private var results=JSONArray()
    private var demo=false;private var ready=false;private var sdkReady=false
    private var busy=false;private var signing=false;private var signatureTaken=false;private var submitted=false
    private var network=0;private var phase="setup";private var message="";private var batch="";private var nonce=0L
    private lateinit var confirmButton:Button
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        demo=BuildConfig.DEBUG&&intent.getBooleanExtra("demo",false);network=if(demo)1 else 0
        drafts=BetDrafts(this,demo)
        try {
            journal=OrderJournal(File(filesDir,if(demo)"orders-fixture.json" else "orders.json"))
            cleanCompletedDrafts()
            val ids=intent.getStringArrayListExtra("draftIds").orEmpty()
            require(ids.isNotEmpty()&&ids.size<=20&&ids.distinct().size==ids.size){"Choose between one and twenty bets"}
            val saved=drafts.all().associateBy{it.getString("draftId")}
            selected=ids.map{checkNotNull(saved[it]){"This bet was already handled. Return to your slip."}}
            if(journal!!.all().any{it.stage in unresolved})message="Check previous order status before placing more bets. Nothing will be retried automatically."
        }catch(e:Exception){message=e.message?:"Your bet slip could not be opened"}
        confirmButton=Button(this).apply{
            setTextColor(Color.rgb(253,253,251));textSize=17f;isAllCaps=false
            typeface=Typeface.createFromAsset(assets,"fonts/DelaGothicOne-Regular.ttf")
            background=GradientDrawable().apply{setColor(Color.rgb(17,17,19));setStroke(2,Color.rgb(17,17,19))}
            visibility=View.GONE;setOnClickListener{confirm()}
        }
        val density=resources.displayMetrics.density
        (paper.parent as FrameLayout).addView(confirmButton,FrameLayout.LayoutParams(-1,(56*density).toInt(),Gravity.BOTTOM).apply{setMargins((12*density).toInt(),0,(12*density).toInt(),(12*density).toInt())})
        openPaper("?batch=1")
    }
    private val unresolved=setOf(OrderStage.Signing,OrderStage.Submitting,OrderStage.Unknown,OrderStage.Resting)
    private val terminal=setOf(OrderStage.Filled,OrderStage.Rejected,OrderStage.Cancelled)
    private fun cleanCompletedDrafts(){
        val ids=journal!!.all().filter{it.stage in terminal&&it.ticket.optBoolean("submittedBatch")}.map{it.ticket.optString("draftId")}.filter{it.isNotEmpty()}.toSet()
        if(ids.isNotEmpty())drafts.remove(ids)
    }
    private fun emit(){
        if(!ready||isFinishing||isDestroyed)return
        confirmButton.visibility=if(phase=="review"&&!busy)View.VISIBLE else View.GONE
        confirmButton.text=if(tickets.size==1)"Confirm bet" else "Confirm ${tickets.size} bets"
        tell(JSONObject().put("type","batch").put("phase",phase).put("busy",busy).put("sdkReady",sdkReady).put("demo",demo).put("network",network).put("message",message)
            .put("blocked",runCatching{journal!!.all().any{it.stage in unresolved}}.getOrDefault(true)).put("drafts",JSONArray(selected)).put("results",results).put("orders",JSONArray(tickets.mapIndexed{i,t->
                JSONObject().put("draftId",rows.getOrNull(i)?.ticket?.optString("draftId")).put("title",t.market).put("label",t.side).put("risk",t.maximumDebit.toPlainString())
                    .put("payout",t.shares.toPlainString()).put("fees",t.feeCeiling.toPlainString()).put("shares",t.shares.toPlainString()).put("price",t.limitPrice.toPlainString())
            })).put("wallet",tickets.firstOrNull()?.wallet.orEmpty()))
    }
    override fun command(data:JSONObject){when(data.optString("type")){
        "ready"->{ready=true;emit()}
        "tradeReady"->{sdkReady=true;emit()}
        "batchAction"->when(data.optString("action")){
            "network"->if(!busy&&!signing&&!demo){cancelReview();network=data.optInt("network").takeIf{it in 0..2}?:0;phase="setup";emit()}
            "review"->prepare()
            "status"->reconcile()
            "close"->if(!busy&&!signing){cancelReview();finish()}
        }
        "tradeSign"->sign(data)
        "tradeSubmit"->submit(data)
        "tradeFailed"->if(signing&&data.optString("id")==batch)uncertain("Signing or submission did not complete. Check status before another attempt.")
    }}
    private fun cancelReview(){
        if(signing)return
        if(batch.isNotEmpty()&&phase=="review")journal?.abandonBatch(batch,System.currentTimeMillis())
        tickets=emptyList();rows=emptyList();batch=""
    }
    private fun prepare(){
        if(busy||signing||journal==null)return
        if(journal!!.all().any{it.stage in unresolved}){message="Check previous order status before placing more bets. Nothing will be retried.";emit();return}
        if(!sdkReady){message="Signing tools are loading. Try again shortly.";emit();return}
        if(network !in 1..2){message="Choose test funds or real funds before reviewing.";emit();return}
        busy=true;message="Refreshing prices, fees and available trading cash…";emit()
        scope.launch{try{
            cancelReview();phase="setup"
            val current=drafts.all().associateBy{it.getString("draftId")}
            require(selected.isNotEmpty()&&selected.all{current.containsKey(it.getString("draftId"))}){"A bet was already handled. Return to your slip."}
            val testnet=network==1
            val wallet=checkNotNull(if(demo)"0x1111111111111111111111111111111111111111" else LiveWallet(this@BatchActivity).current().address){"Open your wallet and sign in first"}
            val prepared=withContext(Dispatchers.IO){
                val service=HyperliquidOrders(testnet)
                val meta=if(demo)Demo.request(JSONObject().put("type","outcomeMeta")) else service.info(JSONObject().put("type","outcomeMeta"))
                val markets=Market.parse(meta).associateBy{it.id}
                val balances=if(demo)JSONObject("""{"balances":[{"coin":"USDC","token":0,"total":"10000","hold":"0"}]}""") else service.balances(wallet)
                val balanceAt=System.currentTimeMillis()
                val rate=if(demo)BigDecimal("0.0007") else {
                    val fees=service.info(JSONObject().put("type","userFees").put("user",wallet))
                    val scale=BigDecimal(meta.get("feeScale").toString());require(scale>=BigDecimal.ZERO)
                    maxOf(BigDecimal(fees.getString("userSpotCrossRate")),BigDecimal(fees.getString("userSpotAddRate")),BigDecimal.ZERO)*scale
                }
                val funding=FundingSnapshot(wallet,balanceAt,null,null,HyperliquidOrders.available(balances,"USDC"))
                val built=selected.map {draft->
                    val m=checkNotNull(markets[draft.getString("marketId").toLong()]){"A market is not listed on the chosen network"};val side=draft.getInt("side")
                    require(side in 0..1&&m.quote=="USDC"&&m.identity==draft.getString("identity")&&m.title==draft.getString("title")&&m.sideLabel(side)==draft.getString("label")){"Market terms changed. Return and add this bet again."}
                    val book=if(demo)Demo.request(JSONObject().put("type","l2Book").put("coin",m.coin(side))) else service.info(JSONObject().put("type","l2Book").put("coin",m.coin(side)))
                    val now=System.currentTimeMillis();val asset=Math.toIntExact(Math.addExact(100000000L,Math.addExact(Math.multiplyExact(m.id,10L),side.toLong())))
                    val rules=VenueOrderRules(asset,m.title,m.sideLabel(side),BigDecimal("0.00000001"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,now)
                    BetBatch.fromBudget(OrderWallet(wallet),rules,book,m.coin(side),draft.getString("budget").toBigDecimal(),rate,funding,now,testnet)
                }
                val now=System.currentTimeMillis();BetBatch.requireAffordable(built,funding,now)
                // One shared expiry for the exact multi-order signature, after all fresh reads succeed.
                built.map{it.copy(reviewedAt=now)}
            }
            tickets=prepared;rows=journal!!.reviewBatch(tickets,selected.map{it.getString("draftId")},System.currentTimeMillis());batch=rows.first().ticket.getString("batchId")
            phase="review";message="Exact risk includes the fee ceiling. Orders may fill separately or partially; an unfilled remainder cancels."
            val reviewedBatch=batch
            scope.launch{delay(30001);if(phase=="review"&&batch==reviewedBatch&&!signing){cancelReview();phase="setup";message="Prices expired. Review again before placing these bets.";emit()}}
        }catch(e:Exception){message=e.message?:"Review unavailable. Nothing sent.";phase="setup"}
        finally{busy=false;emit()}}
    }
    /** Only this native button calls confirm; no JS confirm command exists. */
    private fun confirm(){
        if(busy||signing||phase!="review"||tickets.isEmpty()||journal==null)return
        try{
            val now=System.currentTimeMillis();require(tickets.all{it.fresh(now)}){"Review expired. Review these bets again."}
            nonce=now;rows=journal!!.beginBatch(batch,nonce);signing=true;signatureTaken=false;submitted=false;busy=true;phase="sending";message="Signing your confirmed ${tickets.size} bet${if(tickets.size==1)"" else "s"}…";emit()
            val data=JSONObject().put("id",batch).put("wallet",tickets.first().wallet).put("testnet",tickets.first().testnet).put("nonce",nonce).put("expiresAfter",tickets.first().reviewedAt+30000)
                .put("orders",JSONArray(tickets.mapIndexed{i,t->JSONObject().put("asset",t.asset).put("buy",true).put("price",t.limitPrice.stripTrailingZeros().toPlainString()).put("shares",t.shares.stripTrailingZeros().toPlainString()).put("tif","Ioc").put("cloid",rows[i].cloid)}))
            paper.evaluateJavascript("window.HunchSdk.send($data)",null)
        }catch(e:Exception){message=e.message?:"Confirmation unavailable";if(signing)uncertain(message) else {cancelReview();phase="setup";emit()}}
    }
    private fun reply(call:String,result:Any?=null,error:String?=null){
        val json=when(result){null->"null";is String->JSONObject.quote(result);else->result.toString()}
        paper.evaluateJavascript("window.HunchSdk.reply(${JSONObject.quote(call)},$json,${error?.let{JSONObject.quote(it)}?:"null"})",null)
    }
    private fun sign(data:JSONObject){
        val call=data.optString("call")
        if(!signing||signatureTaken||data.optString("id")!=batch){reply(call,error="No owner-confirmed batch");return}
        signatureTaken=true
        scope.launch{try{
            require(tickets.all{it.fresh(System.currentTimeMillis())}){"Review expired"}
            val ticket=tickets.first();val typed=data.getJSONObject("typed");val domain=typed.getJSONObject("domain");val msg=typed.getJSONObject("message")
            require(domain.getString("name")=="Exchange"&&domain.getInt("chainId")==1337&&domain.getString("version")=="1"&&domain.getString("verifyingContract")=="0x0000000000000000000000000000000000000000")
            require(typed.getString("primaryType")=="Agent"&&msg.getString("source")==if(ticket.testnet)"b" else "a")
            if(demo){reply(call,"0x"+"1".padStart(64,'0')+"1".padStart(64,'0')+"1b");return@launch}
            val user=(PrivySession.get(this@BatchActivity).getAuthState() as? AuthState.Authenticated)?.user?:error("Wallet sign-in required")
            user.refresh().getOrThrow();val wallet=user.embeddedEthereumWallets.singleOrNull{it.address.equals(ticket.wallet,true)}?:error("Signing wallet changed")
            val remaining=ticket.reviewedAt+30000-System.currentTimeMillis();require(remaining>0){"Review expired"}
            val signature=withTimeout(remaining){wallet.provider.request(EthereumRpcRequest.ethSignTypedDataV4(ticket.wallet,typed.toString())).getOrThrow().data}
            require(Regex("0x[0-9a-fA-F]{130}").matches(signature));reply(call,signature)
        }catch(e:Exception){reply(call,error="Signing cancelled or expired");uncertain("Signing did not complete before submission. Nothing sent.")}}
    }
    private fun submit(data:JSONObject){
        val call=data.optString("call")
        if(!signing||!signatureTaken||submitted||data.optString("id")!=batch){reply(call,error="No signed confirmed batch");return}
        try{
            val now=System.currentTimeMillis();require(tickets.all{it.fresh(now)}){"Review expired"}
            val body=data.getJSONObject("body");HyperliquidOrders.validateBatch(body,tickets,rows,nonce)
            rows=journal!!.submitBatch(batch,now);submitted=true;message="Sent once. Reading each bet's result…";emit()
            scope.launch{try{
                val result=withContext(Dispatchers.IO){if(demo)demoResponse() else HyperliquidOrders(tickets.first().testnet).submit(body)}
                val stages=HyperliquidOrders.batchStages(result,tickets);rows=journal!!.finishBatch(batch,stages,System.currentTimeMillis(),tickets.indices.map{result.optJSONObject("response")?.optJSONObject("data")?.optJSONArray("statuses")?.optJSONObject(it)})
                cleanCompletedDrafts();results=JSONArray(rows.mapIndexed{i,row->resultRow(row,result.optJSONObject("response")?.optJSONObject("data")?.optJSONArray("statuses")?.optJSONObject(i)).apply{if(result.optString("status")=="err")put("error",result.optString("response").take(300))}})
                signing=false;busy=false;phase="results";message=if(stages.any{it.first==OrderStage.Unknown})"Some results are unclear. Check status; do not place those bets again." else if(stages.all{it.first==OrderStage.Rejected})"No bets were accepted. Each rejection is shown below." else "Each bet has its own result. A filled order is an open prediction, not a settled win."
                reply(call,result);emit()
            }catch(e:Exception){reply(call,error="Result unclear; no retry");uncertain("Result unclear. Check status before any further order; nothing will be resent.")}}
        }catch(e:Exception){reply(call,error="Submission refused");uncertain("Could not complete submission. Check status before another order.")}
    }
    private fun resultRow(row:JournalOrder,status:JSONObject?=null)=JSONObject().put("draftId",row.ticket.optString("draftId")).put("title",row.ticket.getString("market")).put("label",row.ticket.getString("side")).put("stage",row.stage.name)
        .put("shares",row.ticket.getString("shares")).put("filled",status?.optJSONObject("filled")?.optString("totalSz")?:row.ticket.optString("filledSz")).put("error",status?.optString("error").orEmpty().take(300)).put("cloid",row.cloid)
    private fun demoResponse():JSONObject{
        val kind=intent.getStringExtra("resultFixture").orEmpty()
        if(kind=="unknown")return JSONObject().put("status","ok").put("response",JSONObject().put("type","order").put("data",JSONObject().put("statuses",JSONArray())))
        val statuses=JSONArray(tickets.mapIndexed{i,t->if(kind=="mixed"&&i==1)JSONObject().put("error","Fictional venue rejection") else JSONObject().put("filled",JSONObject().put("oid",i+1).put("totalSz",if(kind=="mixed"&&i==0)t.shares.divide(BigDecimal(2),0,java.math.RoundingMode.DOWN).max(BigDecimal.ONE).toPlainString() else t.shares.toPlainString()).put("avgPx",t.limitPrice.toPlainString()))})
        return JSONObject().put("status","ok").put("response",JSONObject().put("type","order").put("data",JSONObject().put("statuses",statuses)))
    }
    private fun uncertain(copy:String){
        runCatching{if(batch.isNotEmpty())rows=journal!!.abandonBatch(batch,System.currentTimeMillis())}
        results=JSONArray(rows.map{resultRow(it)});signing=false;busy=false;phase="results";message=copy;emit()
    }
    private fun reconcile(){
        if(busy||signing||journal==null)return
        busy=true;message="Checking venue status. No order will be resent.";emit()
        scope.launch{try{
            val active=journal!!.all().filter{it.stage in unresolved}
            val updated=withContext(Dispatchers.IO){active.map{row->
                val result=if(demo)JSONObject("""{"status":"order","order":{"status":"filled"}}""") else HyperliquidOrders(row.ticket.optBoolean("testnet")).status(row.ticket.getString("wallet"),row.cloid)
                val stage=HyperliquidOrders.statusStage(result)
                if(stage!=OrderStage.Unknown&&stage!=row.stage)journal!!.transition(row.id,stage,System.currentTimeMillis(),venueConfirmed=true) else row
            }}
            cleanCompletedDrafts();results=JSONArray(updated.map{resultRow(it)});message=if(updated.any{it.stage in unresolved})"Some bets still need a confirmed result. No retry is allowed." else if(updated.isEmpty())"No unresolved orders." else "Venue status refreshed. Return to your slip for remaining bets."
            if(updated.isNotEmpty())phase="results"
        }catch(e:Exception){message="Status unavailable. No order retried."}finally{busy=false;emit()}}
    }
    override fun back(){if(!busy&&!signing){cancelReview();finish()}}
    override fun onDestroy(){scope.cancel();journal?.close();super.onDestroy()}
}
