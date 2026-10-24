package dev.omalauncher.hunch

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ink=Color.rgb(17,17,19); private val paper=Color.rgb(253,253,251)
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var page: LinearLayout
    private var markets=emptyList<Market>(); private var generation=0
    private var detail=false
    override fun onCreate(state: Bundle?) { super.onCreate(state); home() }
    private fun label(text:String,size:Float=16f):TextView = TextView(this).apply {
        this.text=text; textSize=size; setTextColor(ink);setPadding(14,14,14,14)
        if(size>=26) typeface=Typeface.createFromAsset(assets,"fonts/DelaGothicOne-Regular.ttf")
    }
    private fun base(title:String) {
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false;setBackgroundColor(paper) }
        page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,40,16,36) };scroll.addView(page);setContentView(scroll)
        page.addView(label("HUNCH / READ ONLY",12f));page.addView(label(title,30f))
    }
    private fun panel(text:String,action:(()->Unit)?=null):TextView = label(text).also { v ->
        v.background=GradientDrawable().apply { setColor(paper);setStroke(1,ink) }
        page.addView(v,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=12 })
        action?.let { v.setOnClickListener { it() } }
    }
    private fun request(body:JSONObject):JSONObject {
        val c=URL("https://api.hyperliquid.xyz/info").openConnection() as HttpURLConnection
        try { c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=10000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            check(c.responseCode==200) { "Market service unavailable (${c.responseCode})" }
            val bytes=c.inputStream.use { stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=stream.read(buffer);if(n<0)break;check(out.size()+n<=4_000_000){"Response too large"};out.write(buffer,0,n)}
                out.toByteArray()
            }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    private fun load(work:()->Unit) { worker.execute { work() } }
    private fun home() {
        detail=false;val token=++generation;base("Today's issue")
        panel("Public markets only. No wallet connected.")
        val input=EditText(this).apply { hint="Search markets";setSingleLine() };page.addView(input)
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };page.addView(rows)
        fun show() { rows.removeAllViews();val q=input.text.toString();markets.filter { (it.title+it.template+it.venue).contains(q,true) }.take(100).forEach { m ->
            rows.addView(label("${m.title}\n${m.venue} · ${m.quote}").apply { setOnClickListener { market(m) } })
        };if(rows.childCount==0)rows.addView(label("No markets match.")) }
        input.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){show()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        rows.addView(label("Loading public markets…"))
        load { try { val found=Market.parse(request(JSONObject().put("type","outcomeMeta")));runOnUiThread { if(token==generation&&!isDestroyed){markets=found;show()} } }
            catch(e:Exception){runOnUiThread {if(token==generation&&!isDestroyed){rows.removeAllViews();rows.addView(label("Markets unavailable. Tap to retry.").apply {setOnClickListener {home()}})}}} }
    }
    private fun market(m:Market) {
        detail=true;val token=++generation;base(m.title);panel("← Today's issue") {home()}
        panel("${m.venue} · ${m.quote}\n${m.template}")
        val price=panel("Reading live orders…")
        panel("Market terms\n"+m.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        panel("Wallet and betting are not connected.")
        load {try {val book=request(JSONObject().put("type","l2Book").put("coin",m.coin(0)));val bid=Book.best(book,0);val ask=Book.best(book,1)
            runOnUiThread {if(token==generation&&!isDestroyed){price.text="Side 0 live orders\nBid: ${bid ?: "No orders"}\nAsk: ${ask ?: "No orders"}\nNo midpoint estimate.";if(bid==null||ask==null)price.setTextColor(Color.rgb(31,111,176))}}
        }catch(e:Exception){runOnUiThread {if(token==generation&&!isDestroyed)price.text="Live orders unavailable. No price estimate."}}}
    }
    @Deprecated("Android back compatibility") override fun onBackPressed(){if(detail)home() else super.onBackPressed()}
    override fun onDestroy(){generation++;worker.shutdownNow();super.onDestroy()}
}
