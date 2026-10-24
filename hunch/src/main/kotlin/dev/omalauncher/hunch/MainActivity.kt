package dev.omalauncher.hunch

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import android.text.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.text.SimpleDateFormat
import java.util.*

/** Separate read-only app: the only network action is POST /info. */
class MainActivity : Activity() {
    private val ink=Color.rgb(17,17,19);private val paper=Color.rgb(253,253,251)
    private val red=Color.rgb(215,38,45);private val blue=Color.rgb(31,111,176)
    private val worker=Executors.newSingleThreadExecutor();private var job:Future<*>?=null
    private var generation=0;private var markets=emptyList<Market>();private var updated=0L
    private var query="";private var category="All";private var chapter="Today"
    private var selected:Market?=null;private var side=0;private var budget=20
    private var fixture="";private var shown=40
    private lateinit var page:LinearLayout
    private lateinit var display:Typeface;private lateinit var body:Typeface;private lateinit var mono:Typeface
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        display=Typeface.createFromAsset(assets,"fonts/DelaGothicOne-Regular.ttf")
        body=Typeface.createFromAsset(assets,"fonts/ZenKakuGothicNew-Regular.ttf")
        mono=Typeface.createFromAsset(assets,"fonts/JetBrainsMono.ttf")
        fixture=if(BuildConfig.DEBUG) intent.getStringExtra("fixture").orEmpty() else ""
        query=state?.getString("query").orEmpty();chapter=state?.getString("chapter") ?: "Today"
        category=state?.getString("category") ?: "All";home()
    }
    override fun onSaveInstanceState(out:Bundle){out.putString("query",query);out.putString("chapter",chapter);out.putString("category",category);super.onSaveInstanceState(out)}
    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun text(words:String,size:Float=16f,color:Int=ink,face:Typeface=body)=TextView(this).apply{
        text=words;textSize=size;setTextColor(color);typeface=face;setPadding(dp(12),dp(10),dp(12),dp(10))
    }
    private fun border(color:Int=ink)=GradientDrawable().apply{setColor(paper);setStroke(dp(1),color)}
    private fun button(words:String,action:()->Unit)=text(words,13f,ink,mono).apply{
        background=border();isClickable=true;isFocusable=true;minHeight=dp(48);setOnClickListener{action()}
    }
    private fun box(parent:LinearLayout,view:View){parent.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})}
    private fun panel(words:String,color:Int=ink)=text(words,16f,color).apply{background=border(color)}
    private fun base(title:String) {
        val root=column().apply{setBackgroundColor(paper)}
        root.setOnApplyWindowInsetsListener { v,ins -> v.setPadding(ins.systemWindowInsetLeft,ins.systemWindowInsetTop,ins.systemWindowInsetRight,ins.systemWindowInsetBottom);ins }
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;fillViewport=true}
        page=column().apply{setPadding(dp(16),dp(12),dp(16),dp(16))}
        scroll.addView(page);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val tabs=LinearLayout(this)
        listOf("Today","Bets","Money").forEach { name ->
            tabs.addView(button(name){chapter=name;selected=null;home()}.apply{
                contentDescription="$name chapter${if(chapter==name)", selected" else ""}"
                if(chapter!=name)setBackgroundColor(Color.rgb(232,232,229))
            },LinearLayout.LayoutParams(0,-2,1f))
        };root.addView(tabs);setContentView(root);root.requestApplyInsets()
        box(page,text(if(fixture.isNotEmpty())"HUNCH / FICTIONAL DEMO" else "HUNCH / PUBLIC MARKETS",11f,red,mono))
        box(page,text(title,28f,ink,display))
    }
    private fun next():Int { job?.cancel(true);return ++generation }
    private fun task(token:Int,work:()->Unit){job=worker.submit{if(token==generation)work()}}
    private fun deliver(token:Int,work:()->Unit){runOnUiThread{if(token==generation&&!isDestroyed)work()}}
    private fun request(body:JSONObject):JSONObject {
        if(fixture=="error")error("Fictional offline state")
        if(fixture=="demo")return Demo.request(body)
        val c=URL("https://api.hyperliquid.xyz/info").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.connectTimeout=8000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use{it.write(body.toString().toByteArray())};check(c.responseCode==200){"Market service unavailable"}
            val bytes=c.inputStream.use{stream->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){check(!Thread.currentThread().isInterrupted);val n=stream.read(buf);if(n<0)break;check(out.size()+n<=4_000_000);out.write(buf,0,n)};out.toByteArray()}
            return JSONObject(String(bytes,Charsets.UTF_8))
        }finally{c.disconnect()}
    }
    private fun home() {
        selected=null;val token=next();base(when(chapter){"Bets"->"Your calls";"Money"->"Your money";else->"Today's issue"})
        if(chapter!="Today"){
            box(page,panel(if(chapter=="Bets")"No bets yet.\nThis read-only edition does not place or track bets." else "No wallet connected.\nThere is no balance to display."))
            box(page,button("Back to Today"){chapter="Today";home()});return
        }
        box(page,text("READ-ONLY EDITION · Browse first",11f,blue,mono))
        val search=EditText(this).apply{hint="Search teams, coins, people…";setSingleLine();typeface=body;textSize=16f;setText(query);contentDescription="Search markets";background=border();setPadding(dp(12),dp(12),dp(12),dp(12))};box(page,search)
        val chipScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val chips=LinearLayout(this);chipScroll.addView(chips);box(page,chipScroll)
        val status=text("Loading public markets…",12f,blue,mono);box(page,status)
        val rows=column();page.addView(rows)
        fun show() {
            chips.removeAllViews();rows.removeAllViews()
            listOf("All","Sports","Prices","Companies","Rates","Events").forEach{cat->
                val count=Market.groups(markets.filter{cat=="All"||it.category==cat}).size
                chips.addView(button("$cat $count"){category=cat;shown=40;show()}.apply{if(cat==category){setBackgroundColor(ink);setTextColor(paper)};contentDescription="$cat filter${if(cat==category)", selected" else ""}"})
            }
            val matches=Market.groups(markets.filter{(category=="All"||it.category==category)&&(it.title+it.fields.values.joinToString(" ")+it.venueName).contains(query,true)})
            status.text="${matches.size} markets · Updated ${clock(updated)} · tap a panel"
            if(matches.isEmpty()){box(rows,panel("No markets match.\nTry another team, coin or topic.",blue));return}
            val wide=resources.configuration.screenWidthDp>=700
            var row:LinearLayout?=null
            matches.take(shown).forEachIndexed{i,group->
                val m=group.first();val card=column().apply{background=border();isClickable=true;isFocusable=true;setOnClickListener{market(m,group)};contentDescription="Open market: ${m.title}"}
                card.addView(text("${m.category.uppercase()} / ${group.joinToString(" + "){it.venueName}}",10f,red,mono))
                card.addView(text(m.title,if(i==0)22f else 18f,ink,display))
                card.addView(text("${m.deadline?.let{"Market date: $it"} ?: "Read terms for timing"}\n${m.quote} · Open for live prices",12f,blue))
                if(wide){if(i%2==0){row=LinearLayout(this);box(rows,row!!)};row!!.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(8)})}else box(rows,card)
            }
            if(matches.size>shown)box(rows,button("Show more markets"){shown+=40;show()})
            box(rows,text("END OF THIS PAGE / refresh for new markets",10f,blue,mono))
        }
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun afterTextChanged(e:Editable?){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){query=s.toString();shown=40;show()}})
        if(markets.isNotEmpty())show()
        box(page,button("Refresh markets"){home()})
        task(token){try{val found=Market.parse(request(JSONObject().put("type","outcomeMeta")));deliver(token){markets=found;updated=System.currentTimeMillis();show()}}
            catch(e:Exception){deliver(token){status.text=if(markets.isEmpty())"Markets unavailable. Try Refresh markets." else "Could not refresh · showing earlier data from ${clock(updated)}"}}}
    }
    private fun clock(time:Long)=if(time==0L)"not loaded" else SimpleDateFormat("HH:mm:ss",Locale.US).format(Date(time))
    private fun market(m:Market,venues:List<Market> = markets.filter{it.identity==m.identity}) {
        selected=m;val token=next();base(m.title)
        box(page,button("← Today's issue"){home()})
        box(page,text("${m.category} · ${m.venueName} · ${m.quote}",12f,red,mono))
        if(venues.size>1){box(page,text("Same supplied terms · choose venue",12f));venues.forEach{v->box(page,button(v.venueName){market(v,venues)})}}
        box(page,text("Choose an outcome",14f))
        val sides=LinearLayout(this);(0..1).forEach{n->sides.addView(button(m.sideLabel(n)){side=n;market(m,venues)}.apply{contentDescription="${m.sideLabel(n)} outcome${if(n==side)", selected" else ""}";if(n==side){setBackgroundColor(ink);setTextColor(paper)}},LinearLayout.LayoutParams(0,-2,1f))};box(page,sides)
        val prices=panel("Reading live orders…",blue);box(page,prices)
        val preview=panel("Payout preview awaits a real ask.",blue)
        var asks=emptyList<Level>();var valid=false;var quoteTime=0L
        fun previewText(){
            if(!valid||System.currentTimeMillis()-quoteTime>60_000||m.quote!="USDC"){preview.text="No payout estimate. ${if(m.quote!="USDC")"Unsupported quote currency." else "No real ask orders."}";return}
            val q=Book.preview(asks,BigDecimal(budget))
            preview.text=if(q.complete)"${Book.money(BigDecimal(budget))} → ${Book.money(q.grossPayout)} if this outcome wins\nGross illustration at shown ask depth. Fees excluded. If it loses: $0." else "Only ${Book.money(q.spent)} of the $${budget} preview has quoted depth.\nNo full-budget payout estimate."
        }
        box(page,text("Explore a hypothetical amount. No order is sent.",13f))
        val amount=text("Preview amount: $${budget}",14f,ink,mono);box(page,amount)
        box(page,SeekBar(this).apply{max=99;progress=budget-1;contentDescription="Hypothetical amount";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(b:SeekBar?,p:Int,fromUser:Boolean){budget=p+1;amount.text="Preview amount: $${budget}";previewText()}
            override fun onStartTrackingTouch(b:SeekBar?){};override fun onStopTrackingTouch(b:SeekBar?){}
        })});box(page,preview)
        box(page,button("Refresh live orders"){market(m,venues)})
        box(page,text("HOW THIS MARKET IS DEFINED",12f,red,mono))
        box(page,panel(if(m.fields.isEmpty())"The venue supplied no descriptive terms. Resolution conditions are unverified." else m.fields.entries.joinToString("\n"){"${friendly(it.key)}: ${it.value}"}))
        box(page,text("These are venue-supplied terms, not a verified resolution guarantee. Dates are shown exactly as supplied. Market ${m.id}.",12f,blue))
        box(page,text("No wallet connected. Betting and deposits are unavailable in this edition.",13f))
        task(token){try{val b=request(JSONObject().put("type","l2Book").put("coin",m.coin(side)));val bid=Book.best(b,0);val ask=Book.best(b,1);val levels=Book.levels(b,1)
            deliver(token){val stale=b.optLong("time",0).let{it<=0||System.currentTimeMillis()-it>60_000||it>System.currentTimeMillis()+5000};val crossed=bid!=null&&ask!=null&&bid>=ask
                asks=levels;quoteTime=b.optLong("time");valid=!stale&&!crossed&&asks.isNotEmpty()
                prices.text="${m.sideLabel(side)} · ${if(stale)"STALE / UNVERIFIED" else "Snapshot ${clock(b.getLong("time"))}"}\nBuy: ${ask?.let{Book.money(it)} ?: "No asks"} · Sell: ${bid?.let{Book.money(it)} ?: "No bids"}\n"+when{stale->"Refresh before relying on this book.";crossed->"Inconsistent book. No estimate.";ask==null||bid==null->"Unpriced side: one side of the book is empty.";ask-bid>BigDecimal("0.10")->"Wide spread. No midpoint probability shown.";else->"Quoted orders, not a predicted probability."}
                previewText()
            }
        }catch(e:Exception){deliver(token){prices.text="Live orders unavailable. Tap Refresh live orders.";valid=false;previewText()}}}
    }
    private fun friendly(key:String)=key.replace(Regex("([a-z])([A-Z])"),"$1 $2").replaceFirstChar{it.uppercaseChar()}
    @Deprecated("Android back compatibility")override fun onBackPressed(){if(selected!=null){home()}else if(chapter!="Today"){chapter="Today";home()}else super.onBackPressed()}
    override fun onDestroy(){generation++;job?.cancel(true);worker.shutdownNow();super.onDestroy()}
}
