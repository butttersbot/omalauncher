package dev.omalauncher.hunch

import android.os.Bundle
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection
import java.math.BigDecimal
import java.util.concurrent.Executors

/** Original HTML/CSS is the UI; market parsing/depth validation remains native and read-only. */
class MainActivity:PaperActivity(){
    private val worker=Executors.newFixedThreadPool(2)
    private var fixture="";private var design="";private var chapter="today"
    @Volatile private var markets=emptyList<Market>()
    private val quoteWorker=Executors.newFixedThreadPool(2)
    private val listingQuotes=mutableMapOf<String,Pair<Long,JSONObject>>()
    private val quoting=mutableSetOf<String>()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        fixture=if(BuildConfig.DEBUG)intent.getStringExtra("fixture").orEmpty() else ""
        design=if(BuildConfig.DEBUG)intent.getStringExtra("design").orEmpty() else ""
        chapter=state?.getString("chapter")?:"today"
        openPaper(if(design.matches(Regex("[A-Za-z]+")))"?design=$design" else "")
        if(BuildConfig.DEBUG&&intent.getStringExtra("tradeFixture")=="demo"){
            intent.removeExtra("tradeFixture");fixture="demo";startActivity(Intent(this,TradeActivity::class.java).putExtra("demo",true).putExtra("market",1L).putExtra("side",0).putExtra("title","Fictional test market").putExtra("sideLabel","Yes"))
        }
        if(BuildConfig.DEBUG&&intent.getStringExtra("walletFixture")=="demo"){
            intent.removeExtra("walletFixture");startActivity(Intent(this,WalletActivity::class.java).putExtra("demo",true))
        }
        if(BuildConfig.DEBUG&&intent.getStringExtra("authFixture")=="demo"){
            intent.removeExtra("authFixture");startActivity(Intent(this,AccountActivity::class.java).putExtra("demo",true))
        }
    }
    override fun onSaveInstanceState(out:Bundle){out.putString("chapter",chapter);super.onSaveInstanceState(out)}
    override fun command(data:JSONObject){when(data.optString("type")){
        "ready"->{tell(JSONObject().put("type","config").put("chapter",chapter).put("fixture",fixture));if(design.isEmpty())loadMarkets()}
        "markets"->if(design.isEmpty())loadMarkets()
        "route"->if(data.optString("chapter") in listOf("today","bets","money"))chapter=data.getString("chapter")
        "trade"->{val m=markets.singleOrNull{it.id.toString()==data.optString("id")};val side=data.optInt("side",-1);if(m!=null&&side in 0..1)startActivity(Intent(this,TradeActivity::class.java).putExtra("market",m.id).putExtra("side",side).putExtra("title",m.title).putExtra("identity",m.identity).putExtra("sideLabel",m.sideLabel(side)))}
        "book"->if(design.isEmpty())loadBook(data)
        "listingQuotes"->if(design.isEmpty())loadListingQuotes(data)
        "account"->if(design.isEmpty())startActivity(Intent(this,AccountActivity::class.java).putExtra("demo",BuildConfig.DEBUG&&fixture=="demo"))
        "wallet"->if(design.isEmpty())startActivity(Intent(this,WalletActivity::class.java).putExtra("demo",BuildConfig.DEBUG&&fixture=="demo"))
        "live"->{intent.removeExtra("design");design="";chapter="today";openPaper()}
        "close"->finish()
    }}
    private fun request(body:JSONObject):JSONObject{
        if(fixture=="error")error("Offline fixture")
        if(fixture=="demo")return Demo.request(body)
        val c=URL("https://api.hyperliquid.xyz/info").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.connectTimeout=8000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use{it.write(body.toString().toByteArray())};check(c.responseCode==200)
            val bytes=c.inputStream.use{stream->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){check(!Thread.currentThread().isInterrupted);val n=stream.read(buf);if(n<0)break;check(out.size()+n<=4_000_000);out.write(buf,0,n)};out.toByteArray()}
            return JSONObject(String(bytes,Charsets.UTF_8))
        }finally{c.disconnect()}
    }
    private fun loadMarkets(){worker.submit{
        val result=runCatching{Market.parse(request(JSONObject().put("type","outcomeMeta")))}
        runOnUiThread{if(!isDestroyed){result.fold(onSuccess={found->markets=found;tell(JSONObject().put("type","markets").put("updated",System.currentTimeMillis()).put("rows",JSONArray(found.map{m->JSONObject().put("id",m.id.toString()).put("title",m.title).put("category",m.category).put("venue",m.venueName).put("quote",m.quote).put("date",m.deadline?.let{MarketDates.display(it)}?:"Read venue terms").put("identity",m.identity).put("sides",JSONArray((0..1).map{m.sideLabel(it)})).put("terms",JSONObject(m.fields))})))},onFailure={tell(JSONObject().put("type","marketError"))})}}
    }}
    private fun loadListingQuotes(data:JSONObject){
        val ids=data.optJSONArray("ids")?:return
        if(ids.length()>16)return
        val now=System.currentTimeMillis()
        for(i in 0 until ids.length()){
            val id=ids.optString(i);val market=markets.firstOrNull{it.id.toString()==id}?:continue
            val cached=listingQuotes[id]
            if(cached!=null&&now-cached.first<(if(cached.second.optBoolean("valid"))15_000L else 10_000L)){
                tell(JSONObject().put("type","listingQuote").put("quote",cached.second));continue
            }
            // Bound queued HTTP reads even when the owner scrolls rapidly. Detail requests use a separate pool.
            if(id in quoting||quoting.size>=24)continue
            quoting.add(id)
            quoteWorker.submit{
                val result=runCatching{ListingQuote.read(market,request(JSONObject().put("type","l2Book").put("coin",market.coin(0))),System.currentTimeMillis())}
                    .getOrElse{JSONObject().put("id",id).put("valid",false)}
                runOnUiThread{quoting.remove(id);if(!isDestroyed){listingQuotes[id]=System.currentTimeMillis() to result;tell(JSONObject().put("type","listingQuote").put("quote",result))}}
            }
        }
    }
    private fun loadBook(data:JSONObject){
        val id=data.optString("id");val m=markets.find{it.id.toString()==id}?:return
        val side=data.optInt("side",-1);if(side !in 0..1)return
        val budget=data.optInt("budget",20);if(budget !in 1..200)return
        worker.submit{
            val payload=JSONObject().put("type","book").put("id",id).put("side",side).put("budget",budget)
            try{val b=request(JSONObject().put("type","l2Book").put("coin",m.coin(side)));val bid=Book.best(b,0);val ask=Book.best(b,1)
                val age=System.currentTimeMillis()-b.optLong("time",0);val stale=age>60_000||age< -5000||b.optLong("time",0)<=0
                val crossed=bid!=null&&ask!=null&&bid>=ask
                payload.put("bid",bid?.toPlainString()?:JSONObject.NULL).put("ask",ask?.toPlainString()?:JSONObject.NULL).put("stale",stale).put("time",b.optLong("time"))
                val valid=!stale&&!crossed&&ask!=null&&m.quote=="USDC"
                payload.put("valid",valid).put("empty",ask==null||bid==null).put("crossed",crossed)
                if(valid){val preview=Book.preview(Book.levels(b,1),BigDecimal(budget));payload.put("complete",preview.complete).put("payout",Book.money(preview.grossPayout)).put("spent",Book.money(preview.spent))}
            }catch(e:Exception){payload.put("error",true)}
            runOnUiThread{if(!isDestroyed)tell(payload)}
        }
    }
    override fun onDestroy(){worker.shutdownNow();quoteWorker.shutdownNow();super.onDestroy()}
}
