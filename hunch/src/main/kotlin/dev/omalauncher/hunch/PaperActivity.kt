package dev.omalauncher.hunch

import android.app.Activity
import android.os.Bundle
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
    private val origin="appassets.androidplatform.net"
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if(android.os.Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
        setContentView(frame);frame.requestApplyInsets()
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
