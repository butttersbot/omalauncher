package dev.omalauncher.hunch

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class OrderStage { Reviewed, Signing, Submitting, Unknown, Rejected, Resting, Filled, Cancelled }
internal data class JournalOrder(val id: String, val cloid: String, val stage: OrderStage, val ticket: JSONObject, val updated: Long, val venueId: String?)

/** Private app storage only. No key, signature or signed payload is recorded here.
 * Persist Submitting BEFORE touching the exchange. An interrupted send becomes Unknown on reopen.
 * Unknown must be reconciled by cloid; there is deliberately no resubmit transition.
 */
internal class OrderJournal(private val file: File) : java.io.Closeable {
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private var closed = false
    private var failed = false
    private val rows = linkedMapOf<String, JournalOrder>()
    private val canonicalPath = file.canonicalPath
    companion object { private val activePaths = mutableSetOf<String>() }
    init {
        // POSIX can release process locks when ANY descriptor for that inode closes.
        // Reject duplicate JVM owners before opening/closing another lock-file descriptor.
        synchronized(activePaths) { check(activePaths.add(canonicalPath)) { "Order journal already open" } }
        var channel: FileChannel? = null
        var acquired: java.nio.channels.FileLock? = null
        try {
            file.absoluteFile.parentFile.mkdirs()
            val opened = FileChannel.open(File(file.absoluteFile.parentFile, file.name + ".lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            channel = opened
            val held = checkNotNull(opened.tryLock()) { "Order journal already open" }
            acquired = held
            lockChannel = opened
            lock = held
            if (file.exists()) {
                require(file.length() <= 4_000_000) { "Order journal too large; reconcile before trading" }
                val saved = JSONObject(file.readText())
                require(saved.getInt("version") == 1)
                val entries = saved.getJSONArray("orders")
                for (i in 0 until entries.length()) {
                    val row = entries.getJSONObject(i)
                    val id = row.getString("id")
                    check(id !in rows) { "Duplicate journal id" }
                    val stage = OrderStage.valueOf(row.getString("stage"))
                    rows[id] = JournalOrder(id, row.getString("cloid"),
                        when(stage) { OrderStage.Submitting -> OrderStage.Unknown; OrderStage.Signing -> OrderStage.Cancelled; else -> stage },
                        row.getJSONObject("ticket"), row.getLong("updated"), row.optString("venueId").takeIf { it.isNotEmpty() })
                }
                save()
            }
        } catch (e: Exception) {
            runCatching { acquired?.release() }; runCatching { channel?.close() }
            synchronized(activePaths) { activePaths.remove(canonicalPath) }
            throw e
        }
    }
    private fun usable() = check(!closed && !failed) { "Order journal unavailable; close and reconcile before continuing" }
    @Synchronized override fun close() { if (!closed) {
        closed = true
        try { lock.release() } finally { lockChannel.close() }
        synchronized(activePaths) { activePaths.remove(canonicalPath) }
    } }
    @Synchronized fun all(): List<JournalOrder> { usable(); return rows.values.map { it.copy(ticket = JSONObject(it.ticket.toString())) } }
    @Synchronized fun review(ticket: OrderTicket, now: Long): JournalOrder {
        usable()
        require(ticket.fresh(now)) { "Review expired" }
        check(rows.values.none { it.stage in setOf(OrderStage.Signing, OrderStage.Submitting, OrderStage.Unknown, OrderStage.Resting) }) { "Reconcile the previous order first" }
        check(rows.size < 1000) { "Archive reconciled orders before continuing" }
        val id = UUID.randomUUID().toString()
        val row = JournalOrder(id, "0x" + id.replace("-", ""), OrderStage.Reviewed, ticketJson(ticket), now, null)
        rows[id] = row
        persistOrRollback(id, null)
        return row.copy(ticket = JSONObject(row.ticket.toString()))
    }
    /** Record the owner-confirmed cancel attempt before its signature/HTTP; the original stays unresolved. */
    @Synchronized fun beginCancel(id:String, now:Long, expiresAfter:Long=now+30_000):JournalOrder {
        usable();val old=checkNotNull(rows[id]);require(old.stage==OrderStage.Resting && expiresAfter>=now && expiresAfter-now<=30_000)
        val ticket=JSONObject(old.ticket.toString()).put("cancelNonce",now).put("cancelExpiresAfter",expiresAfter)
        val next=old.copy(stage=OrderStage.Unknown,ticket=ticket,updated=maxOf(old.updated,now))
        rows[id]=next;persistOrRollback(id,old)
        return next.copy(ticket=JSONObject(next.ticket.toString()))
    }
    @Synchronized fun transition(id: String, stage: OrderStage, now: Long, venueId: String? = null, venueConfirmed: Boolean = false): JournalOrder {
        usable()
        val old = checkNotNull(rows[id]) { "Unknown order" }
        val allowed = when (old.stage) {
            OrderStage.Reviewed -> setOf(OrderStage.Signing, OrderStage.Cancelled)
            OrderStage.Signing -> setOf(OrderStage.Submitting, OrderStage.Cancelled, OrderStage.Unknown)
            OrderStage.Submitting -> setOf(OrderStage.Unknown, OrderStage.Rejected, OrderStage.Resting, OrderStage.Filled, OrderStage.Cancelled)
            OrderStage.Unknown -> setOf(OrderStage.Rejected, OrderStage.Resting, OrderStage.Filled, OrderStage.Cancelled)
            OrderStage.Resting -> setOf(OrderStage.Filled, OrderStage.Cancelled, OrderStage.Unknown)
            else -> emptySet()
        }
        require(stage in allowed && now >= 0) { "Unsafe order transition" }
        if (old.stage in setOf(OrderStage.Submitting, OrderStage.Unknown, OrderStage.Resting) && stage != OrderStage.Unknown) {
            require(venueConfirmed) { "An in-flight order needs a confirmed venue result" }
        }
        if (stage == OrderStage.Signing || stage == OrderStage.Submitting) {
            require(now >= old.ticket.getLong("reviewedAt") && now - old.ticket.getLong("reviewedAt") <= 30_000) { "Review expired; prepare a new ticket" }
            check(rows.values.none { it.id != id && it.stage in setOf(OrderStage.Signing, OrderStage.Submitting, OrderStage.Unknown, OrderStage.Resting) }) { "Another order needs reconciliation" }
        }
        val next = old.copy(stage = stage, updated = maxOf(now, old.updated), venueId = venueId ?: old.venueId)
        rows[id] = next
        persistOrRollback(id, old)
        return next.copy(ticket = JSONObject(next.ticket.toString()))
    }
    private fun persistOrRollback(id: String, old: JournalOrder?) {
        try { save() } catch (e: Exception) { failed = true; if (old == null) rows.remove(id) else rows[id] = old; throw e }
    }
    private fun save() {
        file.parentFile?.mkdirs()
        val body = JSONObject().put("version", 1).put("orders", JSONArray(rows.values.map { row ->
            JSONObject().put("id", row.id).put("cloid", row.cloid).put("stage", row.stage.name)
                .put("ticket", row.ticket).put("updated", row.updated).apply { row.venueId?.let { put("venueId", it) } }
        })).toString().toByteArray(Charsets.UTF_8)
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { it.write(body); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Persist the rename as well as the contents before allowing the exchange call.
        FileChannel.open(file.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private fun ticketJson(t: OrderTicket) = JSONObject().put("wallet", t.wallet).put("asset", t.asset)
        .put("market", t.market).put("side", t.side).put("price", t.limitPrice.toPlainString()).put("shares", t.shares.toPlainString())
        .put("feeRateCeiling",t.feeRateCeiling.toPlainString()).put("notional", t.notional.toPlainString()).put("feeCeiling", t.feeCeiling.toPlainString())
        .put("buy", t.buy).put("testnet", t.testnet).put("maximumDebit", t.maximumDebit.toPlainString()).put("reviewedAt", t.reviewedAt).put("timeInForce", t.timeInForce)
}
