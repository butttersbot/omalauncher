package dev.omalauncher.hunch

import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.util.Base64
import android.view.WindowManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Owner creates/reopens a wallet and reads balances. No transaction submission. */
class WalletActivity:PaperActivity(){
    private val scope=MainScope()
    private lateinit var access:WalletAccess
    private var demo=false;private var ready=false;private var busy=false;private var identity=WalletIdentity(false)
    private var message="";private var addressQr="";private var balances=JSONObject()
    override fun onCreate(state:Bundle?){super.onCreate(state)
        demo=BuildConfig.DEBUG&&intent.getBooleanExtra("demo",false)
        if(!demo)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        access=if(demo)DemoWallet() else LiveWallet(applicationContext)
        openPaper("?wallet=1")
    }
    override fun onResume(){super.onResume();if(ready&&!busy)refresh()}
    private fun render(){if(ready)tell(JSONObject().put("type","wallet").put("demo",demo).put("busy",busy).put("signed",identity.signed).put("address",identity.address.orEmpty()).put("qr",addressQr).put("message",message).put("balances",balances))}
    override fun command(data:JSONObject){when(data.optString("type")){
        "ready"->{ready=true;refresh()}
        "close"->finish()
        "walletAction"->if(!busy)when(data.optString("action")){
            "check"->refresh()
            "login"->if(!identity.signed)startActivity(Intent(this,AccountActivity::class.java))
            "create"->if(identity.signed&&identity.address==null)work("Creating your Hunch wallet…"){
                try {identity=WalletIdentity(true,access.create())} catch(e:Exception){identity=access.current();throw e}
                readBalances();message="Wallet ready. Verify the address before using it."
            }
            "copy"->identity.address?.let{address->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hunch wallet · Arbitrum",address))
                message="Wallet address copied.";render()
            }
        }
    }}
    private fun refresh(){work("Checking your wallet…"){identity=access.current();readBalances();message=""}}
    private suspend fun readBalances(){
        balances=JSONObject();addressQr=""
        val address=identity.address?:return
        addressQr=withContext(Dispatchers.Default){
            val matrix=QRCodeWriter().encode(address,BarcodeFormat.QR_CODE,256,256)
            val pixels=IntArray(256*256){i->if(matrix[i%256,i/256])0xff111113.toInt() else 0xfffdfdfb.toInt()}
            val bmp=Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888)
            val out=ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.PNG,100,out);bmp.recycle()
            Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
        }
        if(demo){balances=JSONObject().put("arbitrumUsdc","12.50").put("arbitrumEth","0.001").put("hyperliquidTotal","0").put("hyperliquidAvailable","0").put("checked",System.currentTimeMillis());return}
        withContext(Dispatchers.IO){
            try{val (usdc,eth)=WalletReads.arbitrum(address);balances.put("arbitrumUsdc",usdc).put("arbitrumEth",eth)}catch(e:Exception){balances.put("arbitrumError",true)}
            try{val (total,available)=WalletReads.hyperliquid(address);balances.put("hyperliquidTotal",total).put("hyperliquidAvailable",available)}catch(e:Exception){balances.put("hyperliquidError",true)}
            balances.put("checked",System.currentTimeMillis())
        }
    }
    private fun work(wait:String,operation:suspend ()->Unit){if(busy)return;busy=true;message=wait;balances=JSONObject();render();scope.launch{
        try{withTimeout(60_000){operation()}}
        catch(e:TimeoutCancellationException){message="This took too long. Check your wallet before trying again.";balances=JSONObject()}
        catch(e:CancellationException){throw e}
        catch(e:Exception){message="Could not finish. Check your connection and wallet status before trying again.";balances=JSONObject()}
        finally{busy=false;if(!isFinishing&&!isDestroyed)render()}
    }}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
