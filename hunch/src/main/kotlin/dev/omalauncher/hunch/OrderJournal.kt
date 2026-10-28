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
internal class OrderJournal(private val file: File) {
    private val rows = linkedMapOf<String, JournalOrder>()
    init {
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
                    if (stage == OrderStage.Submitting || stage == OrderStage.Signing) OrderStage.Unknown else stage,
                    row.getJSONObject("ticket"), row.getLong("updated"), row.optString("venueId").takeIf { it.isNotEmpty() })
            }
            save() // Durable recovery before any caller can authorize a new send.
        }
    }
    @Synchronized fun all(): List<JournalOrder> = rows.values.map { it.copy(ticket = JSONObject(it.ticket.toString())) }
    @Synchronized fun review(ticket: OrderTicket, now: Long): JournalOrder {
        require(ticket.fresh(now)) { "Review expired" }
        check(rows.values.none { it.stage in setOf(OrderStage.Signing, OrderStage.Submitting, OrderStage.Unknown) }) { "Reconcile the previous order first" }
        check(rows.size < 1000) { "Archive reconciled orders before continuing" }
        val id = UUID.randomUUID().toString()
        val row = JournalOrder(id, "0x" + id.replace("-", ""), OrderStage.Reviewed, ticketJson(ticket), now, null)
        rows[id] = row
        persistOrRollback(id, null)
        return row.copy(ticket = JSONObject(row.ticket.toString()))
    }
    @Synchronized fun transition(id: String, stage: OrderStage, now: Long, venueId: String? = null): JournalOrder {
        val old = checkNotNull(rows[id]) { "Unknown order" }
        val allowed = when (old.stage) {
            OrderStage.Reviewed -> setOf(OrderStage.Signing, OrderStage.Cancelled)
            OrderStage.Signing -> setOf(OrderStage.Submitting, OrderStage.Cancelled, OrderStage.Unknown)
            OrderStage.Submitting -> setOf(OrderStage.Unknown, OrderStage.Rejected, OrderStage.Resting, OrderStage.Filled, OrderStage.Cancelled)
            OrderStage.Unknown -> setOf(OrderStage.Rejected, OrderStage.Resting, OrderStage.Filled, OrderStage.Cancelled)
            OrderStage.Resting -> setOf(OrderStage.Filled, OrderStage.Cancelled, OrderStage.Unknown)
            else -> emptySet()
        }
        require(stage in allowed && now >= old.updated) { "Unsafe order transition" }
        if (stage == OrderStage.Signing || stage == OrderStage.Submitting) {
            require(now >= old.ticket.getLong("reviewedAt") && now - old.ticket.getLong("reviewedAt") <= 30_000) { "Review expired; prepare a new ticket" }
            check(rows.values.none { it.id != id && it.stage in setOf(OrderStage.Signing, OrderStage.Submitting, OrderStage.Unknown) }) { "Another order needs reconciliation" }
        }
        val next = old.copy(stage = stage, updated = now, venueId = venueId ?: old.venueId)
        rows[id] = next
        persistOrRollback(id, old)
        return next.copy(ticket = JSONObject(next.ticket.toString()))
    }
    private fun persistOrRollback(id: String, old: JournalOrder?) {
        try { save() } catch (e: Exception) { if (old == null) rows.remove(id) else rows[id] = old; throw e }
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
        .put("notional", t.notional.toPlainString()).put("feeCeiling", t.feeCeiling.toPlainString())
        .put("maximumDebit", t.maximumDebit.toPlainString()).put("reviewedAt", t.reviewedAt).put("timeInForce", t.timeInForce)
}
