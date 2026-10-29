package dev.omalauncher.hunch

import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Fixed hosts only, one HTTP attempt. No key, signature, wallet RPC or retry logic here. */
internal class HyperliquidOrders(val testnet: Boolean) {
    private val host = if (testnet) "https://api.hyperliquid-testnet.xyz" else "https://api.hyperliquid.xyz"
    fun info(body: JSONObject) = post("info", body)
    fun submit(body: JSONObject) = post("exchange", body)
    private fun post(path: String, body: JSONObject): JSONObject {
        val c = URL("$host/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Connection", "close")
            val payload = body.toString().toByteArray()
            c.setFixedLengthStreamingMode(payload.size)
            c.outputStream.use { it.write(payload) }
            check(c.responseCode == 200) { "Venue HTTP ${c.responseCode}; check status before another order" }
            val bytes = c.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = stream.read(chunk); if (n < 0) break; check(out.size() + n <= 4_000_000); out.write(chunk, 0, n) }
                out.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    fun balances(wallet: String) = info(JSONObject().put("type", "spotClearinghouseState").put("user", wallet))
    fun status(wallet: String, cloid: String) = info(JSONObject().put("type", "orderStatus").put("user", wallet).put("oid", cloid))
    companion object {
        /** Validate the SDK transport envelope against the native immutable review before HTTP. */
        fun validate(body: JSONObject, ticket: OrderTicket, cloid: String, nonce: Long) {
            val action=body.getJSONObject("action");val orders=action.getJSONArray("orders")
            require(action.getString("type")=="order" && action.getString("grouping")=="na" && !action.has("builder") && orders.length()==1)
            val order=orders.getJSONObject(0)
            require(order.getInt("a")==ticket.asset && order.getBoolean("b")==ticket.buy && !order.getBoolean("r") && order.getString("c")==cloid)
            require(order.getString("p").toBigDecimal().compareTo(ticket.limitPrice)==0 && order.getString("s").toBigDecimal().compareTo(ticket.shares)==0)
            require(order.getJSONObject("t").getJSONObject("limit").getString("tif")==ticket.timeInForce)
            require(body.getLong("nonce")==nonce && body.getLong("expiresAfter")==ticket.reviewedAt+30_000 && body.isNull("vaultAddress"))
        }
        fun validateCancel(body:JSONObject,ticket:OrderTicket,cloid:String,nonce:Long) {
            val action=body.getJSONObject("action");val cancels=action.getJSONArray("cancels")
            require(action.getString("type")=="cancelByCloid" && cancels.length()==1 && !action.has("orders"))
            require(cancels.getJSONObject(0).getInt("asset")==ticket.asset && cancels.getJSONObject(0).getString("cloid")==cloid)
            require(body.getLong("nonce")==nonce && body.getLong("expiresAfter")==ticket.reviewedAt+30_000 && body.isNull("vaultAddress"))
        }
        fun available(state: JSONObject, coin: String): BigDecimal {
            val rows = state.getJSONArray("balances")
            val found = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("coin") == coin }
            require(found.size <= 1)
            if (found.isEmpty()) return BigDecimal.ZERO
            if(coin=="USDC")require(found.single().getInt("token")==0) { "Verify canonical venue USDC" }
            val total = found.single().getString("total").toBigDecimal()
            val hold = found.single().getString("hold").toBigDecimal()
            require(total.signum() >= 0 && hold.signum() >= 0 && total >= hold)
            // Portfolio margin requires its own available-after-maintenance accounting; no borrowing here.
            require(!state.optBoolean("portfolioMarginEnabled")) { "Portfolio-margin accounts are not supported by this ticket" }
            return total.subtract(hold)
        }
        fun describe(reply:JSONObject,stage:OrderStage):String {
            val status=reply.optJSONObject("response")?.optJSONObject("data")?.optJSONArray("statuses")?.optJSONObject(0)
            return when(stage){
                OrderStage.Filled -> status?.optJSONObject("filled")?.let{"Filled ${it.optString("totalSz").take(64)} shares at ${it.optString("avgPx").take(64)} USDC. Unfilled IOC quantity is cancelled."} ?: "The venue confirms this order is filled."
                OrderStage.Resting -> "The limit order is resting at the venue. Check status or use Cancel resting order."
                OrderStage.Cancelled -> "The venue confirms cancellation. Shares already filled are not reversed."
                OrderStage.Rejected -> "Venue refused the order: "+(status?.optString("error")?.takeIf{it.isNotBlank()} ?: reply.optString("response","See the venue status")).take(300)
                else -> "The outcome is not confirmed. Check status before any further order; nothing is automatically retried."
            }
        }
        fun replyStage(reply: JSONObject): Pair<OrderStage, String?> {
            if (reply.optString("status") == "err") return OrderStage.Rejected to null
            require(reply.getString("status") == "ok")
            val response = reply.getJSONObject("response")
            require(response.getString("type") == "order")
            val statuses = response.getJSONObject("data").getJSONArray("statuses")
            require(statuses.length() == 1)
            val status = statuses.getJSONObject(0)
            return when {
                status.has("error") -> OrderStage.Rejected to null
                status.has("filled") -> OrderStage.Filled to status.getJSONObject("filled").getLong("oid").toString()
                status.has("resting") -> OrderStage.Resting to status.getJSONObject("resting").getLong("oid").toString()
                else -> OrderStage.Unknown to null
            }
        }
        fun statusStage(reply: JSONObject): OrderStage {
            if (reply.optString("status") != "order") return OrderStage.Unknown
            return when (val state = reply.getJSONObject("order").getString("status")) {
                "open", "triggered" -> OrderStage.Resting
                "filled" -> OrderStage.Filled
                "rejected", "tickRejected", "minTradeNtlRejected", "perpMarginRejected", "reduceOnlyRejected",
                "badAloPxRejected", "iocCancelRejected", "badTriggerPxRejected", "marketOrderNoLiquidityRejected",
                "positionIncreaseAtOpenInterestCapRejected", "positionFlipAtOpenInterestCapRejected",
                "tooAggressiveAtOpenInterestCapRejected", "openInterestIncreaseRejected", "insufficientSpotBalanceRejected",
                "oracleRejected", "perpMaxPositionRejected", "tooManyOpenOrdersRejected" -> OrderStage.Rejected
                "scheduledCancel", "internalCancel" -> OrderStage.Cancelled
                else -> if (state.endsWith("Canceled") || state == "canceled") OrderStage.Cancelled else OrderStage.Unknown
            }
        }
    }
}
