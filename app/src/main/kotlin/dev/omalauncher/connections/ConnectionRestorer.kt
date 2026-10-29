package dev.omalauncher.connections

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.Runtime
import dev.omalauncher.assist.readMacMachines
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only the widget's tap begins a repair. Monitoring resumes reads, never repeats a mutation. */
internal class ConnectionRestorer(private val context: Context, private val runtime: Runtime,
    private val incidents: ConnectionIncidentJournal) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "connections-restore").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("connections-operations", Context.MODE_PRIVATE)
    private data class Target(val machine: String, val client: ServiceControlClient, val approval: ServiceApproval)
    private fun publish(summary: String, record: JSONObject? = null) = main.post {
        ConnectionsSource.repair = summary
        record?.let { ConnectionsSource.diagnostic = it.toString(2) }
    }
    private fun record(service: String, host: String, status: String, reason: String, checks: List<ConnectionCheck> = emptyList()): JSONObject? =
        runCatching { incidents.record(service, host, status, "Restore $status · details saved for agents.", reason, checks,
            listOf(ConnectionNextStep("agent", "inspect_restore_evidence", "Read the check evidence; do not repeat an uncertain restart."))) }.getOrNull()
    fun start() {
        if (!busy.compareAndSet(false, true)) { publish("Restore already in progress."); return }
        io.execute {
            try {
                if (pending().length() > 0) {
                    resumePending()
                    publish("Previous repair needs verification · details for agents.")
                    busy.set(false); return@execute
                }
                // Creation/export happens only after the owner's Restore tap. This is a public key, never enrollment.
                val key = ConnectionOwnerKey.exportPublic(runtime.proot.home)
                val targets = mutableListOf<Target>()
                val blocked = mutableListOf<ConnectionCheck>()
                for (machine in readMacMachines(runtime.proot.launcherConfig)) {
                    val client = ServiceControlClient(ConnectionEndpoint(machine.config.url, machine.config.key))
                    val reply = client.inventory()
                    if (reply.code != 200) {
                        blocked += ConnectionCheck("service_inventory", machine.id, reply.code, "unavailable")
                        continue
                    }
                    val inventory = JSONObject(reply.text)
                    val host = inventory.getString("host")
                    if (!host.matches(Regex("[A-Za-z0-9._-]{1,100}"))) error("Invalid runner identity")
                    val trust = inventory.optJSONObject("owner_trust")?.optString("state")
                    if (trust != "ready") {
                        blocked += ConnectionCheck("owner_trust", host, reply.code, trust ?: "unknown",
                            mapOf("owner_key_id" to key.getString("key_id"), "next" to "Owner-approved Mac trust provisioning is required; no remote enrollment."))
                        continue
                    }
                    val services = inventory.getJSONArray("services")
                    require(services.length() <= CONNECTION_SERVICE_IDS.size)
                    val seen = mutableSetOf<String>()
                    for (i in 0 until services.length()) {
                        val service = services.getJSONObject(i)
                        val id = service.getString("id")
                        require(id in CONNECTION_SERVICE_IDS && seen.add(id))
                        val checks = service.optJSONObject("probe")?.optJSONArray("checks") ?: JSONArray()
                        val failed = (0 until checks.length()).any { checks.getJSONObject(it).optString("state") == "fail" }
                        if (!failed) continue // Unknown, unsupported or stale never authorize an inferred repair.
                        if (service.optJSONObject("restart")?.optBoolean("supported") != true) {
                            blocked += ConnectionCheck(id, host, result = "blocked", fields = mapOf("reason" to service.optJSONObject("restart")?.optString("blocked_reason").orEmpty()))
                            continue
                        }
                        val response = client.challenge(id)
                        require(response.code == 202) { "Service approval unavailable (HTTP ${response.code})" }
                        val approval = ServiceApproval.parse(JSONObject(response.text).getJSONObject("approval"), id, host, System.currentTimeMillis())
                        targets += Target(machine.id, client, approval)
                    }
                }
                if (targets.isEmpty()) {
                    val entry = record("restore", "paired-services", "blocked", "No failed service has a verified, owner-trusted repair route.", blocked)
                    publish("No verified repair available · details for agents.", entry)
                    busy.set(false); return@execute
                }
                val plan = OwnerServicePlan(targets.map { it.approval })
                val description = targets.joinToString("\n") { "${it.approval.host}: ${it.approval.service}" }
                main.post {
                    publish("Owner approval required for ${targets.size} targeted repair(s).")
                    ConnectionRestoreActivity.launch(context, plan, description) { result -> io.execute {
                        try {
                            val signed = result.getOrThrow()
                            require(plan.fresh(System.currentTimeMillis())) { "Owner repair approval expired" }
                            for (target in targets) {
                                // Persist before the only POST. A dropped reply must never cause a second restart.
                                savePending(target, null)
                                val reply = target.client.settle(target.approval, plan, signed.first, signed.second, System.currentTimeMillis())
                                if (reply.code == 202) {
                                    val operation = ServiceOperation.parse(JSONObject(reply.text).getJSONObject("operation"), null, target.approval)
                                    savePending(target, operation.id)
                                } else if (reply.code in 400..499) {
                                    record(target.approval.service, target.approval.host, "blocked", "Owner-approved request refused (HTTP ${reply.code}).",
                                        listOf(ConnectionCheck("restart_request", target.approval.host, reply.code, "refused")))
                                }
                                // Non-HTTP or 5xx: retain the pre-submit marker and reconcile, never retry.
                            }
                            resumePending()
                        } catch (error: Exception) {
                            publish("Restore interrupted · details for agents.", record("restore", "phone", "failed", error.message.orEmpty()))
                        } finally { busy.set(false) }
                    } }
                }
            } catch (error: Exception) {
                publish("Restore blocked · details for agents.", record("restore", "phone", "blocked", error.message.orEmpty()))
                busy.set(false)
            }
        }
    }
    private fun pending() = runCatching { JSONArray(prefs.getString("pending", "[]")) }.getOrElse { error("Repair journal unreadable; no restart attempted") }
    private fun savePending(target: Target, operation: String?) {
        val array = pending()
        val replacement = JSONArray()
        for (i in 0 until array.length()) if (array.getJSONObject(i).getString("approval") != target.approval.id) replacement.put(array.getJSONObject(i))
        replacement.put(JSONObject().put("machine", target.machine).put("approval", target.approval.id).put("service", target.approval.service)
            .put("host", target.approval.host).put("expires_at", target.approval.expiresAt).put("operation", operation ?: JSONObject.NULL))
        require(prefs.edit().putString("pending", replacement.toString()).commit()) { "Repair journal could not be persisted" }
    }
    private fun removePending(approval: String) {
        val array = pending(); val replacement = JSONArray()
        for (i in 0 until array.length()) if (array.getJSONObject(i).getString("approval") != approval) replacement.put(array.getJSONObject(i))
        require(prefs.edit().putString("pending", replacement.toString()).commit()) { "Repair result could not be persisted" }
    }
    fun resume() {
        if (!busy.compareAndSet(false, true)) return
        io.execute { try { resumePending() } catch (error: Exception) {
            publish("Repair status unavailable · details for agents.", record("restore", "phone", "unknown", error.message.orEmpty()))
        } finally { busy.set(false) } }
    }
    private fun resumePending() {
        val array = pending()
        val machines = readMacMachines(runtime.proot.launcherConfig).associateBy { it.id }
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val machine = machines[row.getString("machine")] ?: continue
            val client = ServiceControlClient(ConnectionEndpoint(machine.config.url, machine.config.key))
            val approval = ServiceApproval(row.getString("approval"), "", row.getString("service"), row.getString("host"), row.getLong("expires_at"))
            val id = row.optString("operation").takeIf { it.isNotBlank() && it != "null" }
            val response = if (id == null) client.operationForApproval(approval.id) else client.operation(id)
            if (response.code != 200) {
                publish("Repair result unverified · details for agents.", record(approval.service, approval.host, "unknown", "Operation lookup HTTP ${response.code}; no restart retried."))
                continue
            }
            val operation = ServiceOperation.parse(JSONObject(response.text).getJSONObject("operation"), id, approval)
            if (operation.terminal) {
                val entry = record(approval.service, approval.host, operation.stage, "Service operation ${operation.id}", operation.checks)
                removePending(approval.id)
                publish(if (operation.restored) "Restored · ${approval.host}: ${approval.service}." else "Restore ${operation.stage} · details for agents.", entry)
            } else publish("Verifying · ${approval.host}: ${approval.service}.")
        }
    }
}
