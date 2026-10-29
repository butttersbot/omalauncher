package dev.omalauncher.connections

import android.content.Context
import android.os.PowerManager
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.QuickLane
import dev.omalauncher.phone.PhoneAccess
import dev.omalauncher.phone.PhoneDisplays
import dev.omalauncher.phone.PhoneService
import java.io.File
import java.util.UUID

/** Owner-confirmed recovery of missing fixed native-supervised services. No guest commands or process kills. */
internal class ConnectionPhoneRecovery(private val context: Context, private val runtime: Runtime) {
    enum class Action { Keeper, BackgroundWatcher }
    data class Target(val action: Action, val approval: ServiceApproval)
    private fun noWork(): Boolean = !AgentTasks.working && AgentTasks.builders.all { it.waiting == 0 && it.pending.isEmpty() } &&
        !QuickLane.connectionsHasWork() && runtime.wm.appWindows.isEmpty() && PhoneService.instance?.connectionsBusy != true
    fun guardEvidence(): ConnectionCheck = ConnectionCheck("phone_recovery_work_guard", "phone", result = if (noWork()) "pass" else "blocked",
        fields = mapOf("builders_active_or_front" to (AgentTasks.working || QuickLane.connectionsHasWork()).toString(),
            "queued_turns" to AgentTasks.builders.sumOf { it.waiting }.toString(),
            "pending_approvals" to AgentTasks.pending.size.toString(),
            "phone_request_or_linux_window" to (PhoneService.instance?.connectionsBusy == true || runtime.wm.appWindows.isNotEmpty()).toString()))
    fun candidates(): List<Target> {
        if (!runtime.connectionsLinuxRunning || !noWork()) return emptyList()
        val actions = mutableListOf<Action>()
        if (!runtime.connectionsKeeperStarted && connectionKeeperProbe(runtime.proot.home).result != "pass") actions += Action.Keeper
        val paused = context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).getBoolean("paused", false)
        if (!paused && PhoneAccess.active && context.getSystemService(PowerManager::class.java).isInteractive &&
            !File(runtime.proot.home, ".config/oma-development/bg-paused").exists() && !PhoneDisplays.helperUp() && !runtime.connectionsBgWatcherStarted) actions += Action.BackgroundWatcher
        return actions.map { action -> Target(action, ServiceApproval("phone_" + UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID().toString().replace("-", ""), if (action == Action.Keeper) "phone-keeper" else "phone-background-watcher", "phone", System.currentTimeMillis() + 120_000)) }
    }
    /** Called only after the exact target is included in the OS-authenticated plan. */
    fun recover(target: Target): List<ConnectionCheck> {
        require(target.approval.fresh(System.currentTimeMillis()) && noWork()) { "Phone work became active; no local recovery started" }
        require(runtime.connectionsLinuxRunning) { "Linux runtime is intentionally off" }
        if (target.action == Action.BackgroundWatcher) {
            require(context.getSystemService(PowerManager::class.java).isInteractive) { "Phone is asleep; no helper recovery attempted" }
            require(!context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).getBoolean("paused", false) && PhoneAccess.active) { "Phone control is paused or disabled" }
            require(!File(runtime.proot.home, ".config/oma-development/bg-paused").exists()) { "Background helper is intentionally paused" }
            // The existing owner-authorized heal is the only path allowed to request Wireless debugging.
            dev.omalauncher.phone.AdbWifiHeal.look(context)
        }
        require(runtime.connectionsEnsureMissingService(target.action == Action.Keeper)) { "Service already running or recovery unavailable; no process terminated" }
        val deadline = System.nanoTime() + if (target.action == Action.Keeper) 8_000_000_000L else 65_000_000_000L
        do {
            val proof = if (target.action == Action.Keeper) connectionKeeperProbe(runtime.proot.home) else
                ConnectionCheck("background_helper_authenticated_status", "phone", result = if (PhoneDisplays.helperUp()) "pass" else "unverified")
            if (proof.result == "pass") return listOf(proof)
            Thread.sleep(1000)
        } while (System.nanoTime() < deadline)
        return listOf(ConnectionCheck("phone_service_recovery", "phone", result = "unverified", fields = mapOf("action" to target.action.name, "reason" to "No complete capability proof after recovery request")))
    }
}
