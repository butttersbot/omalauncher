package dev.omalauncher.hunch

import android.app.Activity
import android.os.Bundle
import android.content.res.Configuration
import android.view.Gravity
import android.graphics.Color
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Only bundled assets can execute script. Provider strings are JSON/text, never markup or code. */
abstract class PaperActivity:Activity() {
    protected lateinit var paper:WebView
    private val panelWindow get() = this is WalletActivity || this is AccountActivity || this is BatchActivity
    // Use the full display metrics, not the dialog WebView's narrower content metrics.
    private fun sizePanelWindow(){
        if(!panelWindow)return
        val bounds=if(android.os.Build.VERSION.SDK_INT>=30)windowManager.maximumWindowMetrics.bounds else android.graphics.Rect(0,0,resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
        val density=resources.displayMetrics.density
        val twoPane=bounds.width()/density>=600 && bounds.width()>bounds.height()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(253,253,251)))
        window.decorView.setPadding(0,0,0,0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(if(twoPane)Gravity.RIGHT or Gravity.TOP else Gravity.CENTER)
        val header=if(twoPane)((intent.getIntExtra("panelTop",57).takeIf{it>0}?:57)*density).toInt() else 0
        val insets=if(android.os.Build.VERSION.SDK_INT>=30)windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else android.graphics.Insets.NONE
        val available=bounds.height()-insets.top-insets.bottom-header
        val sheet=this is BatchActivity
        val attrs=window.attributes;attrs.y=if(sheet)0 else header;window.attributes=attrs
        if(sheet)window.setGravity(Gravity.BOTTOM or if(twoPane)Gravity.RIGHT else Gravity.CENTER_HORIZONTAL)
        window.setLayout(if(twoPane)bounds.width()/2 else bounds.width(),if(sheet)(available*.88).toInt() else if(twoPane)available else WindowManager.LayoutParams.MATCH_PARENT)
    }
    override fun onResume(){super.onResume();sizePanelWindow()}
    override fun onConfigurationChanged(config:Configuration){super.onConfigurationChanged(config);sizePanelWindow();paper.post{paper.evaluateJavascript("window.paperResize&&window.paperResize()",null)}}
    private val origin="appassets.androidplatform.net"
    override fun onCreate(state:Bundle?){
        if(panelWindow)setTheme(R.style.HunchPanel)
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if(android.os.Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        paper=WebView(this).apply{
            setBackgroundColor(Color.rgb(253,253,251))
            settings.javaScriptEnabled=true;settings.domStorageEnabled=false
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls=false;settings.displayZoomControls=false
            addJavascriptInterface(Bridge(),"SomaHunch")
            webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                    val u=request.url
                    if(u.scheme!="https"||u.host!=origin||!u.path.orEmpty().startsWith("/hunch/"))return denied()
                    val path=u.path.orEmpty().removePrefix("/hunch/")
                    if(path.contains("..")||!Regex("[A-Za-z0-9_./-]+").matches(path))return denied()
                    return try{WebResourceResponse(when(path.substringAfterLast('.')){"html"->"text/html";"js"->"text/javascript";"css"->"text/css";"json"->"application/json";"ttf"->"font/ttf";else->"application/octet-stream"},"UTF-8",assets.open(path))}catch(e:Exception){denied()}
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            }
        }
        val frame=FrameLayout(this).apply{
            setBackgroundColor(Color.rgb(253,253,251))
            addView(paper,FrameLayout.LayoutParams(-1,-1))
            setOnApplyWindowInsetsListener{v,i->
                if(android.os.Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}
                else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom)
                if(android.os.Build.VERSION.SDK_INT>=30)WindowInsets.CONSUMED else i.consumeSystemWindowInsets()
            }
        }
        setContentView(frame);sizePanelWindow();frame.requestApplyInsets()
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){back()}
    }
    private fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(byteArrayOf()))
    protected fun openPaper(query:String=""){paper.loadUrl("https://$origin/hunch/paper/index.html$query")}
    protected fun tell(data:JSONObject){if(!isDestroyed&&!isFinishing)paper.evaluateJavascript("window.receiveNative&&window.receiveNative("+data.toString()+")",null)}
    protected abstract fun command(data:JSONObject)
    private inner class Bridge{
        @JavascriptInterface fun post(raw:String){if(raw.length>8192)return;val data=runCatching{JSONObject(raw)}.getOrNull()?:return;runOnUiThread{if(!isDestroyed)command(data)}}
    }
    protected open fun back(){paper.evaluateJavascript("window.paperBack?window.paperBack():false"){result->if(result!="true")finish()}}
    @Deprecated("Older Android back")override fun onBackPressed(){back()}
    override fun onDestroy(){paper.removeJavascriptInterface("SomaHunch");paper.stopLoading();paper.destroy();super.onDestroy()}
}
