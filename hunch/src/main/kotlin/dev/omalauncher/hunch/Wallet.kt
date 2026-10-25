package dev.omalauncher.hunch

import android.content.Context
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

internal data class WalletIdentity(val signed:Boolean,val address:String?=null)
internal interface WalletAccess {
    suspend fun current():WalletIdentity
    suspend fun create():String
}
internal class LiveWallet(context:Context):WalletAccess {
    private val privy=PrivySession.get(context)
    private suspend fun user():PrivyUser? = when(val state=privy.getAuthState()) {
        is AuthState.Authenticated -> state.user
        is AuthState.AuthenticatedUnverified -> error("Unverified session")
        else -> null
    }
    override suspend fun current():WalletIdentity {
        val user=user()?:return WalletIdentity(false)
        user.refresh().getOrThrow()
        return WalletIdentity(true,user.embeddedEthereumWallets.firstOrNull()?.address?.also{check(WalletAmounts.address(it))})
    }
    override suspend fun create():String {
        val user=checkNotNull(user())
        user.refresh().getOrThrow()
        // Reuse the existing embedded wallet, including after an interrupted response.
        // Never request an additional HD wallet on a retry.
        val address=user.embeddedEthereumWallets.firstOrNull()?.address
            ?: user.createEthereumWallet(allowAdditional=false).getOrThrow().address
        check(WalletAmounts.address(address));return address
    }
}
internal class DemoWallet:WalletAccess {
    private var address:String?=null
    override suspend fun current()=WalletIdentity(true,address)
    override suspend fun create():String {address="0x1111111111111111111111111111111111111111";return address!!}
}
internal object WalletAmounts {
    const val USDC="0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
    fun address(value:String)=Regex("0x[0-9a-fA-F]{40}").matches(value)&&value.drop(2).any{it!='0'}
    fun units(hex:String,decimals:Int):String {
        require(Regex("0x[0-9a-fA-F]{1,64}").matches(hex))
        return BigDecimal(BigInteger(hex.drop(2),16),decimals).stripTrailingZeros().toPlainString()
    }
    fun spot(value:JSONObject):Pair<String,String> {
        val rows=value.getJSONArray("balances")
        val usdc=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.optString("coin")=="USDC"&&it.optInt("token",-1)==0}
            ?:return "0" to "0"
        val total=BigDecimal(usdc.getString("total"));val hold=BigDecimal(usdc.getString("hold"))
        require(total.signum()>=0&&hold.signum()>=0&&hold<=total)
        return total.toPlainString() to total.subtract(hold).toPlainString()
    }
}
/** Only read methods at fixed public endpoints. No signing/transfer/exchange API here. */
internal object WalletReads {
    fun post(url:String,body:JSONObject):JSONObject {
        require(url in listOf("https://arb1.arbitrum.io/rpc","https://api.hyperliquid.xyz/info"))
        val c=URL(url).openConnection() as HttpURLConnection
        try {c.requestMethod="POST";c.connectTimeout=8000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Hunch/0.5 Android")
            c.outputStream.use{it.write(body.toString().toByteArray())};check(c.responseCode==200)
            val bytes=c.inputStream.use{s->val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=s.read(b);if(n<0)break;check(out.size()+n<=1_000_000);out.write(b,0,n)};out.toByteArray()}
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally {c.disconnect()}
    }
    private fun rpc(method:String,params:JSONArray):String {
        val result=post("https://arb1.arbitrum.io/rpc",JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",params))
        check(!result.has("error"));return result.getString("result")
    }
    fun arbitrum(address:String):Pair<String,String> {
        require(WalletAmounts.address(address));check(rpc("eth_chainId",JSONArray())=="0xa4b1")
        val call=JSONObject().put("to",WalletAmounts.USDC).put("data","0x70a08231"+address.drop(2).lowercase().padStart(64,'0'))
        val usdc=WalletAmounts.units(rpc("eth_call",JSONArray().put(call).put("latest")),6)
        val eth=WalletAmounts.units(rpc("eth_getBalance",JSONArray().put(address).put("latest")),18)
        return usdc to eth
    }
    fun hyperliquid(address:String):Pair<String,String> {
        require(WalletAmounts.address(address))
        return WalletAmounts.spot(post("https://api.hyperliquid.xyz/info",JSONObject().put("type","spotClearinghouseState").put("user",address)))
    }
}
