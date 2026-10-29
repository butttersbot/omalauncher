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
        fun available(state: JSONObject, coin: String): BigDecimal {
            val rows = state.getJSONArray("balances")
            val found = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("coin") == coin }
            require(found.size <= 1)
            if (found.isEmpty()) return BigDecimal.ZERO
            val total = found.single().getString("total").toBigDecimal()
            val hold = found.single().getString("hold").toBigDecimal()
            require(total.signum() >= 0 && hold.signum() >= 0 && total >= hold)
            // Portfolio margin requires its own available-after-maintenance accounting; no borrowing here.
            require(!state.optBoolean("portfolioMarginEnabled")) { "Portfolio-margin accounts are not supported by this ticket" }
            return total.subtract(hold)
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
                "rejected" -> OrderStage.Rejected
                else -> if (state.endsWith("Canceled") || state == "canceled") OrderStage.Cancelled else OrderStage.Unknown
            }
        }
    }
}
