package dev.omalauncher.connections

import android.app.Activity
import android.os.Bundle
import android.os.CancellationSignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Non-exported bridge to an OS-authenticated signature. No intent carries a key, command, signature or plan. */
class ConnectionRestoreActivity : Activity() {
    private var cancellation: CancellationSignal? = null
    private var completed = false
    private var callback: ((Result<Pair<String, String>>) -> Unit)? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ticket = intent.getStringExtra("ticket") ?: run { finish(); return }
        val request = requests.remove(ticket) ?: run { finish(); return }
        callback = request.done
        if (state != null) { complete(Result.failure(IllegalStateException("Owner review interrupted"))); return }
        try {
            cancellation = ConnectionOwnerKey.authenticate(this, request.plan, request.description, ::complete)
        } catch (error: Exception) { complete(Result.failure(error)) }
    }
    private fun complete(result: Result<Pair<String, String>>) {
        if (completed) return
        completed = true
        callback?.invoke(result)
        callback = null
        finish()
    }
    override fun onDestroy() {
        cancellation?.cancel()
        if (!completed) complete(Result.failure(IllegalStateException("Owner review closed")))
        super.onDestroy()
    }
    companion object {
        private data class Request(val plan: OwnerServicePlan, val description: String, val done: (Result<Pair<String, String>>) -> Unit)
        private val requests = ConcurrentHashMap<String, Request>()
        internal fun launch(context: android.content.Context, plan: OwnerServicePlan, description: String,
            done: (Result<Pair<String, String>>) -> Unit) {
            val ticket = UUID.randomUUID().toString()
            requests[ticket] = Request(plan, description, done)
            try {
                context.startActivity(android.content.Intent(context, ConnectionRestoreActivity::class.java)
                    .putExtra("ticket", ticket).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: Exception) { requests.remove(ticket); done(Result.failure(error)) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                requests.remove(ticket)?.done?.invoke(Result.failure(IllegalStateException("Owner review expired")))
            }, 120_000)
        }
    }
}
