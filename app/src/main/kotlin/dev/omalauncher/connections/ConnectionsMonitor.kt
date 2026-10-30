package dev.omalauncher.connections

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.assist.readMacMachines
import dev.omalauncher.assist.tailscaleAddress
import dev.omalauncher.phone.PhoneAccess
import dev.omalauncher.phone.PhoneDisplays
import dev.omalauncher.phone.PhoneService
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process monitor, not a UI timer or a tunnel-delivered push. Android can still suspend/kill Soma. */
class ConnectionsMonitor(private val context: Context, private val runtime: Runtime) {
    private val preferences = context.getSharedPreferences("connections", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "connections-monitor").apply { isDaemon = true } }
    private val readers = Executors.newFixedThreadPool(3) { r -> Thread(r, "connections-check").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val alerts = ConnectionAlerts()
    internal val incidents = ConnectionIncidentJournal(File(runtime.proot.home, ".local/share/soma/connections/incidents")) {
        val values = readMacMachines(runtime.proot.launcherConfig).map { it.config.key }.toMutableList()
        runCatching { File(runtime.proot.home, ".config/soma-canvas/token").readText().trim() }.getOrNull()?.let { values += it }
        runCatching { JSONObject(File(runtime.proot.launcherConfig, "connections.json").takeIf { it.length() <= 65_536 }!!.readText()).optJSONArray("checks") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("key")?.let { values += it }
        }
        values
    }
    private val restorer by lazy { ConnectionRestorer(context, runtime, incidents) }
    @Volatile private var working: Int? = null
    @Volatile private var queued: Int? = null
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val channel = "connections"
    private val paused get() = preferences.getLong("paused-until", 0) > System.currentTimeMillis()
    private val enabled get() = preferences.getBoolean("alerts", false)
    private val notificationAllowed get() = nm.areNotificationsEnabled() && nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    fun start() {
        runCatching {
            val saved = JSONObject(preferences.getString("debounce", "{}")!!)
            for (key in saved.keys()) saved.optJSONObject(key)?.let {
                alerts.pending[key] = ConnectionAlerts.Pending(it.optLong("since"), it.optBoolean("announced"), it.optLong("last_seen", it.optLong("since")),
                    it.optJSONArray("checks")?.let { a -> (0 until a.length()).map { i -> a.getString(i) }.toSet() }.orEmpty())
            }
        }
        nm.createNotificationChannel(NotificationChannel(channel, "Connections outages & recovery", NotificationManager.IMPORTANCE_DEFAULT))
        ConnectionsSource.refresh = { check(false) }
        ConnectionsSource.restore = { restorer.start() }
        ConnectionsSource.togglePause = {
            preferences.edit().putLong("paused-until", if (paused) 0 else System.currentTimeMillis() + 3_600_000).apply()
            updateSettings(); check(false)
        }
        ConnectionsSource.toggleAlerts = {
            if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else preferences.edit().putBoolean("alerts", !enabled).apply()
            updateSettings()
        }
        updateSettings()
        scheduler.scheduleWithFixedDelay({ check(false) }, 1, 30, TimeUnit.SECONDS)
    }
    private fun updateSettings() { main.post {
        ConnectionsSource.enabled = enabled && notificationAllowed
        ConnectionsSource.paused = paused
        ConnectionsSource.alerts = when {
            !nm.areNotificationsEnabled() -> "Android notifications are blocked. Enable them in Settings, then enable outage alerts here."
            nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE -> "The Connections notification channel is blocked in Android Settings."
            paused -> "Monitoring paused intentionally for up to one hour."
            enabled -> "Local Android alerts, independent of Tailscale. Checks run while Soma is alive; Android may delay them in deep sleep."
            else -> "Alerts are off. Enable here to receive debounced outage and verified recovery notices."
        }
    } }
    private fun check(restore: Boolean) {
        if (!running.compareAndSet(false, true)) {
            if (restore) {
                main.post { ConnectionsSource.repair = "Already checking." }
                readers.execute {
                    val record = runCatching { incidents.record("restore", "phone", "blocked", "A check is already in progress.", "check_in_flight",
                        listOf(ConnectionCheck("monitor_guard", "phone", result = "busy")), listOf(ConnectionNextStep("agent", "wait_for_check", "No restart was attempted."))) }.getOrNull()
                    main.post { ConnectionsSource.diagnostic = record?.toString(2) }
                }
            }
            return
        }
        main.post { ConnectionsSource.checking = true; if (restore) { ConnectionsSource.repair = "Checking device and active jobs…"; ConnectionsSource.guide = null } }
        readers.execute {
            try {
                restorer.resume()
                val result = collect()
                val now = System.currentTimeMillis()
                val diagnostic = recordChecks(result, now)
                val quiet = !enabled || paused || !notificationAllowed
                val previousAlerts = alerts.pending.toMap()
                val notices = alerts.update(result, now, quiet)
                val saved = JSONObject()
                alerts.pending.forEach { (key, value) -> saved.put(key, JSONObject().put("since", value.since).put("announced", value.announced).put("last_seen", value.lastSeen).put("checks", JSONArray(value.checks.toList()))) }
                // Persist before posting: a process death cannot make an already posted incident page again.
                val stored = preferences.edit().putString("debounce", saved.toString()).commit()
                if (stored) for (notice in notices) postNotice(notice, result, now)
                else { alerts.pending.clear(); alerts.pending.putAll(previousAlerts) }
                val restoreRecord = if (restore) runCatching {
                    val advice = connectionRepairAdvice(result, now, working, queued)
                    incidents.record("restore", advice.guide?.name ?: "paired-services", "blocked",
                        "Restore blocked; diagnostic saved for agents.", advice.text,
                        result.readings.flatMap { it.checks }, listOf(ConnectionNextStep(
                            if (advice.guide != null) "owner" else "agent",
                            advice.guide?.let { "owner_${it.name.lowercase()}_attention" } ?: "inspect_service_control_and_active_work", advice.text)))
                }.getOrNull() else null
                main.post {
                    val before = ConnectionsSource.snapshot.incidents(now).map { it.key to it.readings.map { r -> r.state } }
                    val after = result.incidents(now).map { it.key to it.readings.map { r -> r.state } }
                    if (!restore && before != after && !repairLineStays(ConnectionsSource.repair, after.isNotEmpty())) { ConnectionsSource.repair = null; ConnectionsSource.guide = null }
                    ConnectionsSource.repair = repairAfterTraffic(ConnectionsSource.repair, result)
                    ConnectionsSource.snapshot = result
                    ConnectionsSource.diagnostic = diagnostic?.toString(2)
                    if (restore) {
                        ConnectionsSource.diagnostic = restoreRecord?.toString(2) ?: diagnostic?.toString(2)
                        ConnectionsSource.repair = if (restoreRecord != null) "Restore blocked."
                            else "Restore blocked · diagnostic storage unavailable."
                        ConnectionsSource.guide = null
                    }
                }
            } catch (error: Exception) {
                val record = runCatching { incidents.record("monitor", "phone", "failed", "Check failed; no restart attempted.", "monitor_exception",
                    listOf(ConnectionCheck("monitor", "phone", result = error.javaClass.simpleName, stderrTail = error.message.orEmpty())),
                    listOf(ConnectionNextStep("agent", "inspect_monitor", "Previous readings will become stale until a fresh check succeeds."))) }.getOrNull()
                main.post { ConnectionsSource.repair = if (record != null) "Check failed." else "Check failed · diagnostic storage unavailable."; ConnectionsSource.diagnostic = record?.toString(2) }
            } finally {
                running.set(false)
                main.post { ConnectionsSource.checking = false }
                updateSettings()
            }
        }
    }
    private fun postNotice(notice: ConnectionAlerts.Notice, snapshot: ConnectionsSnapshot, now: Long) {
        if (!notificationAllowed) return
        val affected = snapshot.readings.filter { it.root == notice.key }.map { it.capability.title }.distinct().joinToString(" + ")
        val title = if (notice.recovered) "Connections recovered" else "Connections need attention"
        val detail = if (notice.recovered) "The failed connection passed its authenticated checks. Open Connections for remaining unknown checks." else "$affected unavailable for at least 60 seconds. Open Connections for details."
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
        if (open != null) builder.setContentIntent(PendingIntent.getActivity(context, 91, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // Stable tag survives dismissal without re-alerting; recovery replaces that incident's notice.
        runCatching { nm.notify("connection:${notice.key}", 91, builder.build()) }
    }

    private fun collect(): ConnectionsSnapshot {
        val rows = mutableListOf<ConnectionReading>()
        fun add(c: Capability, state: ConnectionState, detail: String, root: String? = null, host: String = "", sourceHost: String = "", checks: List<ConnectionCheck> = emptyList()) {
            rows += ConnectionReading(c, state, detail, System.currentTimeMillis(), root, host, sourceHost, checks)
        }
        if (paused) {
            for (c in Capability.entries) add(c, ConnectionState.Paused, "Monitoring intentionally paused; no outage is inferred.")
            return ConnectionsSnapshot(rows, System.currentTimeMillis())
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val tunnel = runCatching { cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { tailscaleAddress(it.address.hostAddress.orEmpty()) }
        } }.getOrNull()
        add(Capability.Network, if (tunnel == true) ConnectionState.Unknown else if (tunnel == false) ConnectionState.Failed else ConnectionState.Unknown,
            if (tunnel == true) "Tailscale-addressed VPN interface present. Peer routing and authenticated pairing are checked separately below. Relay/direct path is not exposed by Android."
            else "No verified Tailscale VPN interface. An intentional disconnect must be paused here to suppress alerts.", if (tunnel == false) "network" else null)
        if (tunnel == true) add(Capability.Network, ConnectionState.Ready, "Tailscale VPN interface present again; peer health is checked independently.", "network")
        val phonePaused = context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).getBoolean("paused", false)
        val awake = context.getSystemService(PowerManager::class.java).isInteractive
        val link = runCatching { PhoneAccess.link(context) }.getOrNull()
        val phone = when {
            phonePaused -> ConnectionState.Paused
            !awake -> ConnectionState.Sleeping
            link == dev.omalauncher.phone.PhoneLink.Off -> ConnectionState.Disabled
            link == dev.omalauncher.phone.PhoneLink.Disconnected -> ConnectionState.Failed
            PhoneService.instance != null && PhoneDisplays.helperUp() -> ConnectionState.Ready
            else -> ConnectionState.Unknown
        }
        if (!phonePaused && awake && link != dev.omalauncher.phone.PhoneLink.Off && PhoneService.instance != null) add(Capability.Phone, ConnectionState.Ready,
            "Android accessibility binding verified; ADB/helper evidence remains separate.", "phone-binding")
        add(Capability.Phone, phone, when (phone) {
            ConnectionState.Paused -> "Phone control intentionally paused."
            ConnectionState.Sleeping -> "Display asleep; Fold app controls are unavailable. No repair attempted."
            ConnectionState.Disabled -> "Phone control is switched off intentionally in Android Settings."
            ConnectionState.Failed -> "Android expects phone control to be on, but its accessibility binding did not recover after the existing startup grace period. Owner Settings are needed; nothing was changed."
            ConnectionState.Degraded -> "Phone capability needs verification."
            ConnectionState.Ready -> "Accessibility bound and authenticated background helper available. Hidden display opens only on demand; ADB is checked separately."
            else -> if (PhoneService.instance == null) "Android is still checking the accessibility binding. No ready state is assumed." else if (PhoneDisplays.helperUp()) "Accessibility bound; background helper answered. Hidden display is created only on demand. Trusted wireless ADB still needs an end-to-end probe." else "Accessibility bound. Background helper is not currently verified; idle/off is not an outage. Trusted wireless ADB still needs an end-to-end probe."
        }, if (phone == ConnectionState.Failed) "phone-binding" else null)
        if (runtime.connectionsLinuxRunning) {
            if (!phonePaused && awake && link != dev.omalauncher.phone.PhoneLink.Off) {
            val adb = runCatching {
                val config = File(runtime.proot.home, ".config/oma-development")
                val port = File(config, "install-port").takeIf { it.length() <= 20 }!!.readText().trim().toInt()
                val serial = JSONObject(File(config, "device.json").takeIf { it.length() <= 4096 }!!.readText()).getString("serial")
                connectionAdbProbe(port, serial)
            }.getOrElse { ConnectionCheck("adb_transport_shell_identity", "phone", result = "unverified", fields = mapOf("reason" to "trusted_transport_not_configured")) }
            add(Capability.Phone, when (adb.result) { "pass" -> ConnectionState.Ready; "auth" -> ConnectionState.Auth; else -> ConnectionState.Unknown },
                when (adb.result) { "pass" -> "Existing trusted ADB transport executed a read-only shell check and matched this phone."
                    "auth" -> "The existing ADB key is not authorized. No pairing or setting was changed."
                    else -> "Existing trusted ADB transport is unverified. No server, scan, pairing or display was started." },
                if (adb.result in setOf("auth", "pass")) "phone-adb" else null, checks = listOf(adb))
            }
            val keeper = connectionKeeperProbe(runtime.proot.home)
            add(Capability.Agents, if (keeper.result == "pass") ConnectionState.Ready else ConnectionState.Unknown,
                if (keeper.result == "pass") "Phone keeper answered its real list protocol."
                else "Phone keeper protocol is unverified; no running jobs are inferred or terminated.",
                checks = listOf(keeper))
        } else add(Capability.Agents, ConnectionState.Disabled, "Phone Linux runtime is off intentionally.")
        // Models: each agent installed on this phone and how its last real run went, and any a builder or the front agent needs that is missing.
        runtime.agents.refresh()
        rows += phoneModelReadings(runtime.agents.installed.map { InstalledAgent(it.bin, it.name) },
            dev.omalauncher.assist.agentNeeds(dev.omalauncher.assist.QuickLane.wantedAgent(context)),
            dev.omalauncher.oma.ChatLog.of(context).tasks, System.currentTimeMillis()) { task ->
            dev.omalauncher.assist.phoneModelOf(task.builder, task.agent)?.let(::prettyModel)
        }
        val machines = readMacMachines(runtime.proot.launcherConfig)
        working = if (machines.isEmpty()) null else 0; queued = if (machines.isEmpty()) null else 0
        val aliases = machines.associate { m -> (m.id.ifEmpty { dev.omalauncher.canvas.COCKPIT_POCKET_HOST }) to URI(m.config.url).host }
        for (m in machines) {
            val sourceHost = URI(m.config.url).host
            val endpoint = ConnectionEndpoint(m.config.url.trimEnd('/') + "/health", m.config.key)
            val reply = connectionGet(endpoint)
            val state = connectionState(reply) { it.optBoolean("ok") && it.optString("runner") == "oma-runner" && it.has("working") }
            val root = "runner:${m.id}"
            val detail = connectionDetail(reply, state, "Authenticated runner health verified. Build toolchain is a separate check.")
            add(Capability.Agents, state, detail, if (state == ConnectionState.Ready) null else root, m.name, sourceHost, listOf(checkEvidence(endpoint.url, reply, state, "runner_identity")))
            // /threads is the authenticated capability check; it never starts a job.
            val threads = if (state == ConnectionState.Ready) connectionGet(ConnectionEndpoint(m.config.url.trimEnd('/') + "/threads", m.config.key)) else reply
            val access = connectionState(threads) { it.optBoolean("ok") && it.optJSONArray("threads") != null }
            if (access == ConnectionState.Ready) {
                for (capability in listOf(Capability.Agents, Capability.Builds)) add(capability, ConnectionState.Ready,
                    "Authenticated runner inventory available again; build checks below remain separate.", "runner:${m.id}", m.name, sourceHost)
                // Android may omit a VPN interface when this app is excluded; an authenticated configured peer is stronger evidence.
                val firstNetwork = rows.indexOfFirst { it.capability == Capability.Network && it.host.isEmpty() }
                if (firstNetwork >= 0) rows[firstNetwork] = rows[firstNetwork].copy(state = ConnectionState.Ready,
                    detail = "Authenticated configured peer is reachable; VPN interface and pairing evidence checked separately.")
            }
            if (threads.code > 0 && tunnel == false) {
                val firstNetwork = rows.indexOfFirst { it.capability == Capability.Network && it.host.isEmpty() }
                if (firstNetwork >= 0) rows[firstNetwork] = rows[firstNetwork].copy(state = ConnectionState.Ready,
                    detail = "Configured peer route responded despite no visible VPN interface. Auth is checked separately.")
            }
            if (threads.code > 0) for (capability in listOf(Capability.Network, Capability.Agents, Capability.Builds)) add(capability,
                ConnectionState.Ready, "Peer route responded; authenticated capability status is checked separately.", "network", m.name, sourceHost)
            add(Capability.Network, access, connectionDetail(threads, access, "Authenticated thread inventory read; pairing and this peer route verified."), if (access == ConnectionState.Ready) "runner:${m.id}" else root, m.name, sourceHost, listOf(checkEvidence(m.config.url.trimEnd('/') + "/threads", threads, access, "authenticated_thread_inventory")))
            if (access == ConnectionState.Ready) {
                val counts = runnerWorkCounts(JSONObject(threads.text))
                working = counts?.let { working?.plus(it.active) }
                queued = counts?.let { queued?.plus(it.queued) }
            } else { working = null; queued = null }
            add(Capability.Builds, if (access == ConnectionState.Ready) ConnectionState.Unknown else access,
                if (access == ConnectionState.Ready) "Runner inventory accessible. Busy/queued is normal. Toolchain, locks and source checks are reported separately; artifact delivery and phone installation require their own receipts."
                else "Build requests depend on this runner. $detail", if (access == ConnectionState.Ready) null else root, m.name, sourceHost, listOf(checkEvidence(m.config.url.trimEnd('/') + "/threads", threads, access, "build_runner_dependency")))
            val health = runCatching { JSONObject(reply.text) }.getOrNull()
            // Older runners advertise soma in /health; newer deployments may expose /services.
            val services = if (state == ConnectionState.Ready) connectionGet(ConnectionEndpoint(m.config.url.trimEnd('/') + "/services", m.config.key)) else null
            services?.let {
                val readings = serviceInventoryReadings(it, m.name, sourceHost, System.currentTimeMillis())
                // The runner's own reading of its build tools takes the place of "not checked yet".
                if (readings.any { r -> r.capability == Capability.Builds }) rows.removeAll { r -> r.capability == Capability.Builds && r.host == m.name &&
                    r.state == ConnectionState.Unknown && r.checks.any { c -> c.name == "build_runner_dependency" } }
                rows += readings
            }
            val advertised = services?.takeIf { it.code == 200 }?.let { runCatching { JSONObject(it.text).optString("soma") }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?: health?.optString("soma")?.takeIf { it.isNotBlank() }
            val service = advertised?.takeIf { runCatching { URI(it).host.equals(URI(m.config.url).host, true) && URI(it).scheme in listOf("http", "https") }.getOrDefault(false) }
            if (service != null) {
                val h = connectionGet(ConnectionEndpoint(service.trimEnd('/') + "/health", m.config.key))
                val s = connectionState(h) { it.optString("service") == "soma-serve" }
                if (s == ConnectionState.Ready) add(Capability.Sync, ConnectionState.Ready,
                    "Authenticated Soma service identity verified again; sync freshness remains separate.", "sync:${m.id}", m.name, sourceHost)
                add(Capability.Sync, s,
                    connectionDetail(h, s, "Soma service identity verified from this phone; the Mac reports its last sync."), if (s == ConnectionState.Ready) null else "sync:${m.id}", m.name, sourceHost, listOf(checkEvidence(service.trimEnd('/') + "/health", h, s, "workspace_service_identity")))
            }
        }
        configuredChecks(rows, aliases)
        for (c in Capability.entries) if (rows.none { it.capability == c }) add(c, if (c == Capability.Optional) ConnectionState.Disabled else ConnectionState.Unknown,
            if (c == Capability.Optional) "No optional monitor configured. Disabled tools are neutral."
            else "No configured or advertised end-to-end check for this capability. Configure connections.json; no healthy result is assumed.")
        val hasRoute = rows.any { it.root == "network" && it.state == ConnectionState.Ready }
        if (tunnel == false && hasRoute) {
            val firstNetwork = rows.indexOfFirst { it.capability == Capability.Network && it.host.isEmpty() }
            if (firstNetwork >= 0) rows[firstNetwork] = rows[firstNetwork].copy(state = ConnectionState.Ready,
                detail = "Configured tailnet peer route responded despite no visible VPN interface. Pairing is checked separately.")
        }
        val groupedRows = if (tunnel == false && !hasRoute) rows.map { r ->
            if (r.sourceHost.isNotEmpty() && r.state == ConnectionState.Failed) r.copy(root = "network") else r
        } else rows
        return ConnectionsSnapshot(groupConnectionFailures(groupedRows, alerts.pending.keys.filter { it.startsWith("host:") }.map { it.removePrefix("host:") }.toSet()), System.currentTimeMillis())
    }

    private fun checkEvidence(url: String, reply: ConnectionReply, state: ConnectionState, name: String): ConnectionCheck {
        val target = runCatching { val u = URI(url); URI(u.scheme, null, u.host, u.port, u.path, null, null).toString() }.getOrDefault("configured endpoint")
        return ConnectionCheck(name, target, reply.code, state.label)
    }
    private fun recordChecks(snapshot: ConnectionsSnapshot, now: Long): JSONObject? {
        var newest: JSONObject? = null
        val problemStates = setOf(ConnectionState.Unknown, ConnectionState.Stale, ConnectionState.Degraded, ConnectionState.Auth, ConnectionState.Failed)
        val grouped = snapshot.readings.filter { it.state in problemStates }.groupBy { it.root ?: "unverified:${it.capability.name}:${it.host}" }
        // Write confirmed outages after missing-evidence records so newest points at the actionable failure.
        for ((root, readings) in grouped.entries.sortedBy { (_, rows) -> if (rows.any { it.state in setOf(ConnectionState.Failed, ConnectionState.Auth) }) 1 else 0 }) {
            val services = readings.map { it.capability.title }.distinct().joinToString(" + ")
            val host = readings.map { it.host }.filter(String::isNotBlank).distinct().joinToString().ifEmpty { "phone" }
            newest = runCatching { incidents.record(services.take(100), host.take(200), "check_${readings.first().state.name.lowercase()}",
                "$services: ${readings.first().state.label}", root,
                readings.flatMap { r -> r.checks.ifEmpty { listOf(ConnectionCheck("capability_evidence", r.sourceHost.ifEmpty { "phone" }, result = r.state.label,
                    fields = mapOf("detail" to r.detail))) } },
                listOf(ConnectionNextStep("agent", if (readings.any { it.state == ConnectionState.Auth }) "verify_pairing_and_provider_auth" else "diagnose_capability", readings.first().detail)), now) }.getOrNull() ?: newest
        }
        return newest
    }

    /** Existing app endpoints plus explicit configuration. Never source shell env files. */
    private fun configuredChecks(rows: MutableList<ConnectionReading>, aliases: Map<String, String>) {
        data class Check(val capability: Capability, val endpoint: ConnectionEndpoint, val schema: String, val name: String)
        val checks = mutableListOf(
            Check(Capability.Voice, ConnectionEndpoint(dev.omalauncher.voice.WhisperMac.base(context) + "/health"), "voice", "Whisper"),
            Check(Capability.Search, ConnectionEndpoint(dev.omalauncher.search.PhotoMac.base(context) + "/health"), "search", "Embeddings"),
            Check(Capability.Activity, ConnectionEndpoint("http://${dev.omalauncher.canvas.COCKPIT_HERD_HOSTS.first()}:8479/v1/herd"), "herd", "Herd"),
            Check(Capability.Files, ConnectionEndpoint(dev.omalauncher.herd.POCKET_URL), "pocket", "Tailshare")
        )
        val home = runtime.proot.home
        val env = File(home, ".config/soma-canvas/env")
        val canvas = runCatching { env.takeIf { it.length() <= 16_384 }?.readLines()?.firstNotNullOfOrNull { line ->
            Regex("^(?:export\\s+)?CANVAS_URL=(.+)$").find(line.trim())?.groupValues?.get(1)?.trim()?.trim('\'', '"')
        } }.getOrNull()
        if (canvas != null && runCatching { URI(canvas).host != null && URI(canvas).scheme in listOf("http", "https") }.getOrDefault(false)) {
            val token = File(home, ".config/soma-canvas/token").let { f -> runCatching { if (f.length() in 1..4096) f.readText().trim() else "" }.getOrDefault("") }
            checks += Check(Capability.Canvas, ConnectionEndpoint(canvas.trimEnd('/') + "/api/apps", token, "x-canvas-token"), "canvas", "Configured Design Canvas")
        }
        val config = File(runtime.proot.launcherConfig, "connections.json")
        val custom = runCatching { if (config.length() in 1..65_536) JSONObject(config.readText()).optJSONArray("checks") else null }.getOrNull()
        if (custom != null) for (i in 0 until minOf(custom.length(), 32)) {
            val c = custom.optJSONObject(i) ?: continue
            val capability = Capability.entries.firstOrNull { it.name.equals(c.optString("capability"), true) } ?: continue
            val intentional = when (c.optString("intentional_state")) {
                "paused" -> ConnectionState.Paused
                "sleeping" -> ConnectionState.Sleeping
                else -> null
            }
            if (intentional != null && c.optLong("until") > System.currentTimeMillis()) {
                rows.removeAll { it.capability == capability }
                rows += ConnectionReading(capability, intentional, "Owner-configured ${intentional.label.lowercase()} until ${java.util.Date(c.getLong("until"))}; no outage inferred.", System.currentTimeMillis())
                checks.removeAll { it.capability == capability }; continue
            }
            if (!c.optBoolean("enabled", true)) {
                rows.removeAll { it.capability == capability }
                rows += ConnectionReading(capability, ConnectionState.Disabled, "Explicitly disabled in Connections configuration.", System.currentTimeMillis())
                checks.removeAll { it.capability == capability }; continue
            }
            val url = c.optString("url")
            if (!runCatching { URI(url).scheme in listOf("http", "https") && URI(url).host != null && URI(url).userInfo == null }.getOrDefault(false)) continue
            checks.removeAll { it.capability == capability }
            checks += Check(capability, ConnectionEndpoint(url, c.optString("key")), c.optString("schema"), c.optString("name", capability.title).take(80))
        }
        // Bound concurrency separately from the coordinator; no UI blocking and no unbounded one-thread-per-endpoint pool.
        val results = checks.map { check -> check to readers.submit<ConnectionReply> { connectionGet(check.endpoint) } }
        for ((check, future) in results) {
            val endpointHost = URI(check.endpoint.url).host
            val sourceHost = aliases[endpointHost] ?: endpointHost
            val reply = runCatching { future.get(12, TimeUnit.SECONDS) }.getOrElse { future.cancel(true); ConnectionReply(-1, "") }
            val verified = connectionState(reply) { json -> when (check.schema) {
                "voice" -> json.optString("status") == "ok" || json.optString("status") == "ready"
                "search" -> json.optBoolean("ok") && json.optInt("dim") > 0 && json.optString("model").isNotBlank()
                "herd" -> json.optJSONArray("machines") != null
                "pocket" -> json.optJSONArray("items") != null
                "canvas" -> json.optJSONArray("apps")?.length()?.let { it > 0 } == true
                // Custom health requires the standard explicit {ok:true}; unknown schemas never pass.
                "ok" -> json.optBoolean("ok")
                else -> false
            } }
            val limited = verified == ConnectionState.Ready && check.capability in setOf(Capability.Files, Capability.Activity, Capability.Canvas, Capability.Messages, Capability.Search, Capability.Sync, Capability.Builds)
            val state = if (limited) ConnectionState.Unknown else verified
            val detail = connectionDetail(reply, verified, when (check.capability) {
                Capability.Files -> "Authenticated pocket listing read. Receive worker and delivery acknowledgment freshness are not exposed by this endpoint."
                Capability.Activity -> "Herd inventory read; reporter age and intentional host sleep are not proved by HTTP availability."
                Capability.Canvas -> "Actual Design Canvas board list loaded. Board contents and workspace-sync freshness still need verification."
                Capability.Search -> "Embedding model health verified. Phone index freshness is not verified."
                Capability.Messages -> "Transport health answered. Mac permissions and message freshness need verification."
                else -> "Service returned validated capability health."
            })
            if (reply.code > 0) rows += ConnectionReading(check.capability, ConnectionState.Ready,
                "Service route responded; capability status is checked separately.", System.currentTimeMillis(), "network", check.name, sourceHost)
            val root = "service:${check.capability.name}:${sourceHost}"
            // Healthy transport observation retained for honest incident recovery, separately from missing end-to-end evidence.
            if (limited) rows += ConnectionReading(check.capability, ConnectionState.Ready, "Previously failing service endpoint passed its schema check.", System.currentTimeMillis(), root, check.name, sourceHost)
            rows += ConnectionReading(check.capability, state, detail, System.currentTimeMillis(), if (limited) null else root, check.name, sourceHost, listOf(checkEvidence(check.endpoint.url, reply, verified, check.schema)))
        }
    }
}
