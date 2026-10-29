package dev.omalauncher.hunch

import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.*
import io.privy.auth.AuthState
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.io.File

/** Not exported. Only bundled JS computes SDK signing data; native owns review, Confirm and journal. */
class TradeActivity : PaperActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var journal: OrderJournal? = null
    private var reviewed: Pair<JournalOrder, OrderTicket>? = null
    private var signing = false; private var signatureTaken = false; private var submitted = false
    private var demo=false
    private var sdkReady=false
    private var nonce = 0L
    private var marketId = -1L; private var outcomeSide = -1
    private lateinit var network: Spinner; private lateinit var direction: Spinner; private lateinit var tif: Spinner
    private lateinit var price: EditText; private lateinit var size: EditText; private lateinit var message: TextView
    private lateinit var review: Button
    private var api: HyperliquidOrders? = null
    private val bg = Color.rgb(253,253,251)
    private fun text(s: String, points: Float = 16f) = TextView(this).apply {
        text = s; textSize = points; setTextColor(Color.rgb(17,17,19))
        typeface = Typeface.createFromAsset(assets, "fonts/ZenKakuGothicNew-Regular.ttf")
        setPadding(0, 8, 0, 8)
    }
    private fun styleButton(button:Button) {
        button.setTextColor(Color.rgb(17,17,19)); button.isAllCaps=false
        button.typeface=Typeface.createFromAsset(assets,"fonts/ZenKakuGothicNew-Bold.ttf")
        button.background=GradientDrawable().apply{setColor(bg);setStroke(3,Color.rgb(17,17,19))}
        button.setPadding(12,12,12,12)
        button.minHeight=56
    }
    private fun choice(values: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@TradeActivity, android.R.layout.simple_spinner_dropdown_item, values) }
    private fun field(label: String) = EditText(this).apply { hint = label; contentDescription = label; setSingleLine(); inputType = 8194; textSize = 20f }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        demo=BuildConfig.DEBUG && intent.getBooleanExtra("demo",false)
        marketId = intent.getLongExtra("market", -1); outcomeSide = intent.getIntExtra("side", -1)
        if (marketId < 0 || outcomeSide !in 0..1) { finish(); return }
        // The offline SDK gets no network access and has no private key.
        paper.layoutParams = android.widget.FrameLayout.LayoutParams(1,1)
        paper.loadUrl("https://appassets.androidplatform.net/hunch/paper/trade-sdk.html")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16); setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { addView(root) }
        (paper.parent as android.widget.FrameLayout).addView(scroll, android.widget.FrameLayout.LayoutParams(-1,-1))
        root.addView(text(if(demo)"HUNCH / FICTIONAL TEST ORDER" else "HUNCH / ORDER", 28f)); root.addView(text(intent.getStringExtra("title").orEmpty(),22f))
        root.addView(text(intent.getStringExtra("sideLabel").orEmpty()))
        root.addView(text("Network")); network = choice(listOf("TESTNET · test funds", "MAINNET · real funds")); root.addView(network);network.setSelection(if(demo)0 else 1);if(demo)network.isEnabled=false
        root.addView(text("Action")); direction = choice(listOf("Buy", "Sell owned shares")); root.addView(direction)
        root.addView(text("Order")); tif = choice(listOf("IOC · unfilled part cancels", "Limit · rests until filled or cancelled")); root.addView(tif)
        price = field("Limit price · USDC per share"); root.addView(price)
        size = field("Exact number of shares"); root.addView(size)
        review = Button(this).apply { text = "Review order"; setOnClickListener { prepare() } }; root.addView(review)
        root.addView(Button(this).apply { text = "Check previous order status"; setOnClickListener { reconcile() } })
        message = text("Choose the network, action, price and size. Nothing is signed or sent when you open this ticket."); root.addView(message)
        root.addView(Button(this).apply { text = "Back to market"; setOnClickListener { if(!signing)finish() } })
        for(i in 0 until root.childCount)(root.getChildAt(i) as? Button)?.let{styleButton(it)}
        try { journal = OrderJournal(File(filesDir,if(demo)"orders-fixture.json" else "orders.json")) }
        catch (_: Exception) { review.isEnabled=false; message.text="Trading disabled: the journal could not be opened. Reconcile before continuing." }
    }
    private fun prepare() {
        if(signing || journal == null)return
        if(!sdkReady){message.text="Signing SDK is loading; try again shortly.";return}
        val buy = direction.selectedItemPosition == 0; val testnet = network.selectedItemPosition == 0
        val chosenTif=if(tif.selectedItemPosition==0)"Ioc" else "Gtc"
        val limit = runCatching { BigDecimal(price.text.toString()) }.getOrNull()
        val shares = runCatching { BigDecimal(size.text.toString()) }.getOrNull()
        if(limit == null || shares == null) { message.text="Enter a price and exact size.";return }
        review.isEnabled=false; message.text="Checking market, fees and available balances…"
        scope.launch { try {
            val wallet = checkNotNull(if(demo)"0x1111111111111111111111111111111111111111" else LiveWallet(this@TradeActivity).current().address) { "Open your wallet and sign in first" }
            val service = HyperliquidOrders(testnet)
            val prepared = withContext(Dispatchers.IO) {
                if(demo){
                    val now=System.currentTimeMillis()
                    val rules=VenueOrderRules(100000010,"Fictional test market","Yes",BigDecimal("0.00000001"),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,now)
                    return@withContext service to OrderTickets.prepareFunded(OrderWallet(wallet),rules,limit,shares,BigDecimal("0.0007"),FundingSnapshot(wallet,now,null,null,BigDecimal("10000")),now,buy,chosenTif,true,BigDecimal("100"))
                }
                val meta = service.info(JSONObject().put("type","outcomeMeta"))
                val market = Market.parse(meta).singleOrNull { it.id == marketId } ?: error("This market is not listed on the chosen network")
                require(market.identity==intent.getStringExtra("identity") && market.title==intent.getStringExtra("title") && market.sideLabel(outcomeSide)==intent.getStringExtra("sideLabel")) { "Selected market differs on this network. Return to the market list." }
                require(market.quote == "USDC") { "Only USDC outcomes are supported" }
                val encoding = Math.addExact(Math.multiplyExact(marketId,10),outcomeSide.toLong())
                val asset = Math.toIntExact(Math.addExact(100_000_000L,encoding))
                require(limit.stripTrailingZeros().precision() <= 5 && limit.stripTrailingZeros().scale() <= 8) { "Price exceeds venue precision" }
                val balances = service.balances(wallet)
                val fees = service.info(JSONObject().put("type","userFees").put("user",wallet))
                val scale = BigDecimal(meta.get("feeScale").toString()); require(scale.signum()>=0)
                val rate = maxOf(BigDecimal(fees.getString("userSpotCrossRate")),BigDecimal(fees.getString("userSpotAddRate")),BigDecimal.ZERO).multiply(scale)
                val now = System.currentTimeMillis()
                // Outcomes use integral shares. Venue enforces its own notional minimum as well;
                // a rejection is shown, never enlarged/retried. No guessed account spending cap.
                val rules = VenueOrderRules(asset,market.title,market.sideLabel(outcomeSide),BigDecimal("0.00000001"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,now)
                val ticket = OrderTickets.prepareFunded(OrderWallet(wallet),rules,limit,shares,rate,
                    FundingSnapshot(wallet,now,null,null,HyperliquidOrders.available(balances,"USDC")),now,
                    buy,chosenTif,testnet,HyperliquidOrders.available(balances,"+$encoding"))
                service to ticket
            }
            api = prepared.first
            val ticket = prepared.second
            check(!signing) { "An order is already being signed" };cancelReview()
            val row = journal!!.review(ticket,System.currentTimeMillis()); reviewed = row to ticket
            val body = "${if(ticket.testnet) "TESTNET" else "MAINNET · REAL FUNDS"}\n${ticket.market}\n${if(ticket.buy) "Buy" else "Sell owned"} ${ticket.side}\nWallet ${ticket.wallet}\n\nExact size: ${ticket.shares.toPlainString()} shares\nLimit: ${ticket.limitPrice.toPlainString()} USDC\nFee ceiling: ${ticket.feeCeiling.toPlainString()} USDC\n" +
                (if(ticket.buy) "Maximum debit / loss: ${ticket.maximumDebit.toPlainString()} USDC" else "Minimum proceeds: ${ticket.notional.subtract(ticket.feeCeiling).toPlainString()} USDC\nValue relinquished: up to ${ticket.shares.toPlainString()} USDC at settlement") +
                "\n${if(ticket.timeInForce=="Ioc") "IOC: partial fills possible; remainder cancels." else "Limit GTC: may remain open. Cancel it on the venue if needed."}\n\nConfirm signs and sends this order once. Review expires after 30 seconds."
            val content = text(body).apply { setPadding(28,12,28,12) }
            AlertDialog.Builder(this@TradeActivity).setTitle("Review order").setView(ScrollView(this@TradeActivity).apply{addView(content)})
                .setNegativeButton("Back") { _,_ -> cancelReview() }.setPositiveButton("Confirm") { _,_ -> confirm(row,ticket) }
                .setOnCancelListener { cancelReview() }.show()
            message.text="Review ready. Nothing has been signed or submitted."
        } catch(e:Exception) { message.text=e.message?:"Review unavailable. Nothing sent." }
        finally { if(!signing)review.isEnabled=journal!=null }
        }
    }
    private fun cancelReview() {
        if(signing)return
        reviewed?.let { current -> runCatching {
            if(journal!!.all().single{it.id==current.first.id}.stage==OrderStage.Reviewed)
                journal!!.transition(current.first.id,OrderStage.Cancelled,System.currentTimeMillis())
        } };reviewed=null
    }
    private fun confirm(row: JournalOrder,ticket: OrderTicket) {
        if(signing || reviewed?.first?.id!=row.id)return
        try {
            check(ticket.fresh(System.currentTimeMillis())) { "Review expired. Review this order again." }
            journal!!.transition(row.id,OrderStage.Signing,System.currentTimeMillis())
            signing=true; signatureTaken=false;submitted=false;review.isEnabled=false;nonce=System.currentTimeMillis()
            val data=JSONObject().put("id",row.id).put("wallet",ticket.wallet).put("asset",ticket.asset).put("buy",ticket.buy)
                .put("price",ticket.limitPrice.stripTrailingZeros().toPlainString()).put("shares",ticket.shares.stripTrailingZeros().toPlainString())
                .put("tif",ticket.timeInForce).put("testnet",ticket.testnet).put("cloid",row.cloid).put("nonce",nonce).put("expiresAfter",ticket.reviewedAt+30_000)
            paper.evaluateJavascript("window.HunchSdk.send($data)",null); message.text="Signing the confirmed order…"
        } catch(e:Exception) { message.text=e.message;if(signing)uncertain("Signing did not start. Review again.") else cancelReview() }
    }
    override fun command(data: JSONObject) {
        when(data.optString("type")) {
            "tradeReady" -> sdkReady=true
            "tradeSign" -> sign(data)
            "tradeSubmit" -> submit(data)
            "tradeFailed" -> if(signing && data.optString("id")==reviewed?.first?.id) uncertain(data.optString("message","SDK could not complete"))
        }
    }
    private fun reply(call:String,result:Any?=null,error:String?=null) {
        val resultJson=when(result){null->"null";is String->JSONObject.quote(result);else->result.toString()}
        paper.evaluateJavascript("window.HunchSdk.reply(${JSONObject.quote(call)},$resultJson,${error?.let{JSONObject.quote(it)}?:"null"})",null)
    }
    private fun sign(data: JSONObject) {
        val current=reviewed ?: return;val row=current.first;val ticket=current.second
        val call=data.optString("call")
        if(!signing || signatureTaken || data.optString("id")!=row.id) { reply(call,error="No confirmed order");return }
        signatureTaken=true
        scope.launch { try {
            check(ticket.fresh(System.currentTimeMillis())) { "Review expired" }
            val typed=data.getJSONObject("typed")
            val domain=typed.getJSONObject("domain");val msg=typed.getJSONObject("message")
            require(domain.getString("name")=="Exchange" && domain.getInt("chainId")==1337 && domain.getString("version")=="1" && domain.getString("verifyingContract")=="0x0000000000000000000000000000000000000000")
            require(typed.getString("primaryType")=="Agent" && msg.getString("source")== (if(ticket.testnet)"b" else "a"))
            if(demo){reply(call,"0x"+"1".padStart(64,'0')+"1".padStart(64,'0')+"1b");return@launch}
            val privy=PrivySession.get(this@TradeActivity)
            val user=(privy.getAuthState() as? AuthState.Authenticated)?.user ?: error("Wallet sign-in required")
            user.refresh().getOrThrow()
            val wallet=user.embeddedEthereumWallets.singleOrNull { it.address.equals(ticket.wallet,true) } ?: error("Signing wallet changed")
            check(ticket.fresh(System.currentTimeMillis())) { "Review expired before signing" }
            val signature=wallet.provider.request(EthereumRpcRequest.ethSignTypedDataV4(ticket.wallet,typed.toString())).getOrThrow().data
            check(Regex("0x[0-9a-fA-F]{130}").matches(signature)) { "Invalid signature response" }
            reply(call,signature)
        }catch(e:Exception){reply(call,error=e.message?:"Signing failed");uncertain("Signing cancelled or failed before submission. Nothing sent; review a new order when ready.")}
        }
    }
    private fun submit(data:JSONObject) {
        val current=reviewed?:return;val row=current.first;val ticket=current.second;val call=data.optString("call")
        if(!signing || !signatureTaken || submitted || data.optString("id")!=row.id) {reply(call,error="No signed confirmed order");return}
        try {
            check(ticket.fresh(System.currentTimeMillis())) {"Review expired"}
            val body=data.getJSONObject("body")
            HyperliquidOrders.validate(body,ticket,row.cloid,nonce)
            journal!!.transition(row.id,OrderStage.Submitting,System.currentTimeMillis());submitted=true;message.text="Order sent once. Reading venue result…"
            scope.launch {
                try {
                    val result=withContext(Dispatchers.IO){if(demo)JSONObject("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"filled":{"oid":1,"totalSz":"${ticket.shares}","avgPx":"${ticket.limitPrice}"}}]}}}""") else checkNotNull(api).submit(body)}
                    val stage=HyperliquidOrders.replyStage(result)
                    journal!!.transition(row.id,stage.first,System.currentTimeMillis(),stage.second,venueConfirmed=true)
                    reply(call,result);message.text="${stage.first}: ${result.toString().take(1800)}\nUse Check previous order status to read back from the venue."
                    signing=false;reviewed=null;review.isEnabled=true
                } catch(e:Exception){reply(call,error="Result unclear; no retry");uncertain("Result unclear: ${e.message}. Check status; do not submit again.")}
            }
        } catch(e:Exception){reply(call,error=e.message?:"Unsafe request");uncertain("Order could not be sent. Check status before another order.")}
    }
    private fun uncertain(reason:String) {
        reviewed?.let { runCatching { journal!!.transition(it.first.id,if(submitted)OrderStage.Unknown else OrderStage.Cancelled,System.currentTimeMillis()) } }
        signing=false;reviewed=null;review.isEnabled=journal!=null;message.text=reason
    }
    private fun reconcile() {
        if(signing || journal==null)return
        review.isEnabled=false
        scope.launch {try {
            val all=journal!!.all()
            val active=all.filter {it.stage in setOf(OrderStage.Unknown,OrderStage.Resting,OrderStage.Submitting,OrderStage.Signing)}
            val rows=if(active.isNotEmpty())active else all.takeLast(1)
            if(rows.isEmpty()){message.text="No unresolved order in the journal.";return@launch}
            val notes=withContext(Dispatchers.IO){rows.map {row ->
                val service=HyperliquidOrders(row.ticket.optBoolean("testnet"))
                val result=if(demo)JSONObject("""{"status":"order","order":{"status":"filled"}}""") else service.status(row.ticket.getString("wallet"),row.cloid)
                val stage=HyperliquidOrders.statusStage(result)
                if(stage!=OrderStage.Unknown && row.stage in setOf(OrderStage.Unknown,OrderStage.Resting,OrderStage.Submitting,OrderStage.Signing) && stage!=row.stage)journal!!.transition(row.id,stage,System.currentTimeMillis(),venueConfirmed=true)
                "${row.cloid}: $stage\n${result.toString().take(1200)}"
            }}
            message.text=notes.joinToString("\n\n")
        }catch(e:Exception){message.text="Status unavailable: ${e.message}. No order retried."}finally{review.isEnabled=true}}
    }
    override fun back(){if(!signing)finish()}
    override fun onDestroy(){scope.cancel();journal?.close();super.onDestroy()}
}
