package dev.omalauncher.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import dev.omalauncher.phone.PhoneService
import dev.omalauncher.runner.PhoneSessions
import dev.omalauncher.updates.AppOffer
import dev.omalauncher.updates.AutoUpdated
import dev.omalauncher.updates.DesignLine
import dev.omalauncher.updates.LauncherState
import dev.omalauncher.updates.PhoneUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feeds the update pill (cards soma-chat/Updates). Offers are builds that passed the gate (`oma-offer` writes them only
 * when the build is taken and signed for this phone, its walks PASSed and the design gate shows no unwaived drift) into
 * Linux's ~/.cache/oma-update/offers: OFFER.json beside its APK. Here they are checked again (sha256, this phone's key,
 * a newer version: SelfInstall) and then:
 *  - Soma itself only on Boss's tap, and only once no phone agent is at work (it restarts Soma and stops them);
 *  - Hunch, Soma Notes and Soma Messages by themselves, never while the app is in front, through a session that needs
 *    no confirmation. If Android still asks, or it fails, it waits in the sheet with the reason and its own [ update ].
 * Nothing here is reachable from the phone bridge: no agent can start an install through it. Play Protect is not touched.
 */
class PhoneUpdater(private val context: Context) {
    val model = PhoneUpdates()
    private val root = File(context.filesDir, "arch/root/.cache/oma-update")
    private val offers = File(root, "offers")
    private val historyFile = File(root, "history.json")
    private val failuresFile = File(root, "failures.json")
    private val pendingFile = File(root, "launcher-pending.json")
    private val updatedFile = File(root, "updated.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waiting: Job? = null
    @Volatile private var inFlight: String? = null
    private var blindRounds = mutableMapOf<String, Int>()
    private val checkedSha = mutableMapOf<String, Pair<Long, Boolean>>()

    fun start() {
        model.update = { offer -> scope.launch { tapped(offer) } }
        model.cancel = { waiting?.cancel(); model.launcher = LauncherState.Idle }
        model.seen = { model.launcher = LauncherState.Idle; updatedFile.delete() }
        SelfInstall.listener = { pkg, status, message -> scope.launch { answered(pkg, status, message) } }
        scope.launch {
            afterRestart()
            while (true) {
                runCatching { refresh() }
                runCatching { autoUpdate() }
                delay(30_000)
            }
        }
    }

    private fun json(f: File) = runCatching { JSONObject(f.readText()) }.getOrNull()
    private fun info(pkg: String): PackageInfo? = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
    @Suppress("DEPRECATION")
    private fun code(i: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    private fun failures() = json(failuresFile) ?: JSONObject()
    private fun saveFailure(pkg: String, why: String?) {
        val f = failures().apply { if (why == null) remove(pkg) else put(pkg, why) }
        root.mkdirs(); failuresFile.writeText(f.toString())
    }

    private fun sha(f: File): Boolean? {
        val cached = checkedSha[f.path]
        if (cached != null && cached.first == f.lastModified()) return cached.second
        return null
    }

    /** The offers on disk, as the sheet shows them; stale ones (installed already) are removed. */
    private suspend fun refresh() {
        val list = mutableListOf<AppOffer>()
        val fails = failures()
        for (f in offers.listFiles { it -> it.name.endsWith(".json") }.orEmpty()) {
            val o = json(f) ?: continue
            val pkg = o.optString("package")
            val apk = File(offers, o.optString("apk"))
            val want = o.optLong("versionCode")
            val installed = info(pkg)
            if (installed != null && code(installed) >= want) { f.delete(); apk.delete(); saveFailure(pkg, null); continue }
            if (!apk.isFile) continue
            val ok = sha(apk) ?: run {
                val digest = apk.inputStream().use { s -> MessageDigest.getInstance("SHA-256").also { d -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; d.update(b, 0, n) } }.digest() }
                val good = digest.joinToString("") { "%02x".format(it) } == o.optString("sha256")
                checkedSha[apk.path] = apk.lastModified() to good
                good
            }
            if (!ok) continue
            val launcher = pkg == context.packageName
            val d = o.optJSONObject("design")
            list += AppOffer(
                pkg = pkg, name = o.optString("name", pkg), versionCode = want,
                from = installed?.let { if (launcher) code(it).toString() else it.versionName.orEmpty() } ?: "not installed",
                to = if (launcher) want.toString() else o.optString("versionName", want.toString()),
                built = o.optString("built"), notes = o.optJSONArray("notes").strings(),
                walksPassed = o.optJSONObject("walks")?.optInt("passed") ?: 0, walksTotal = o.optJSONObject("walks")?.optInt("total") ?: 0,
                design = DesignLine(d?.optInt("match") ?: 0, d?.optInt("unapproved") ?: 0, d?.optInt("unchecked") ?: 0, d?.optBoolean("checked") == true),
                launcher = launcher, failure = if (launcher) null else fails.optString(pkg).ifEmpty { null },
                phoneSteps = o.optJSONArray("phoneSteps").strings(),
            )
        }
        val history = json(historyFile)?.optJSONArray("updated")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .map { AutoUpdated(it.optString("name"), it.optString("from"), it.optString("to"), it.optString("at")) }
        withContext(Dispatchers.Main) { model.offers = list; model.history = history }
    }

    private fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) }.orEmpty()

    /** The other apps, one at a time, each only when it isn't the app in front. */
    private fun autoUpdate() {
        if (inFlight != null) return
        val offer = model.offers.firstOrNull { !it.launcher && it.failure == null } ?: return
        val front = runCatching { PhoneService.instance?.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (front == null) {
            // Without phone control Soma can't tell whether the app is open: after five minutes it asks him instead.
            val n = (blindRounds[offer.pkg] ?: 0) + 1
            blindRounds[offer.pkg] = n
            if (n >= 10) saveFailure(offer.pkg, "Soma couldn’t tell whether it was open (phone control is off)")
            return
        }
        blindRounds.remove(offer.pkg)
        if (front == offer.pkg) return
        install(offer, auto = true)
    }

    private fun apkOf(offer: AppOffer): File? = offers.listFiles { it -> it.name.endsWith(".json") }.orEmpty()
        .mapNotNull { json(it) }.firstOrNull { it.optString("package") == offer.pkg }?.let { File(offers, it.optString("apk")) }

    private fun install(offer: AppOffer, auto: Boolean): JSONObject {
        val apk = apkOf(offer) ?: return JSONObject().put("ok", false).put("error", "The build is no longer here.")
        inFlight = offer.pkg
        val r = runCatching { SelfInstall.install(context, apk, auto) }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
        if (!r.optBoolean("ok")) { inFlight = null; if (!offer.launcher) saveFailure(offer.pkg, r.optString("error")) }
        return r
    }

    /** His tap on [ update … ]. Soma's waits while any phone agent is at work, naming who; the others go at once. */
    private suspend fun tapped(offer: AppOffer) {
        if (!offer.launcher) {
            saveFailure(offer.pkg, null)
            install(offer, auto = false)
            refresh(); return
        }
        waiting?.cancel()
        waiting = scope.launch {
            while (true) {
                val busy = busyAgents()
                if (busy.isEmpty()) break
                withContext(Dispatchers.Main) { model.launcher = LauncherState.Waiting(busy) }
                delay(3_000)
            }
            val kept = info(context.packageName)?.let { code(it).toString() } ?: "?"
            withContext(Dispatchers.Main) { model.launcher = LauncherState.Updating(0) }
            pendingFile.parentFile?.mkdirs()
            pendingFile.writeText(JSONObject().put("version", offer.versionCode).put("phoneSteps", JSONArray(offer.phoneSteps)).toString())
            withContext(Dispatchers.Main) { model.launcher = LauncherState.Updating(1) }
            val r = install(offer, auto = false)
            if (!r.optBoolean("ok")) {
                pendingFile.delete()
                withContext(Dispatchers.Main) { model.launcher = LauncherState.Failed(kept, r.optString("error"), r.toString(2)) }
            } else withContext(Dispatchers.Main) { model.launcher = LauncherState.Updating(2) }
        }
    }

    /** Phone agents at work or with turns queued (mail from a Mac lands as a turn): Soma's restart would stop them. */
    private fun busyAgents(): List<String> = runCatching {
        PhoneSessions(context).list().filter { it.optString("status") == "working" || it.optInt("waiting_turns") > 0 }.map {
            val what = it.optString("summary").lineSequence().firstOrNull().orEmpty().take(40)
            it.optString("name") + if (what.isNotBlank()) " (“$what”)" else ""
        }
    }.getOrDefault(emptyList())

    /** Android's answer for an app session. Soma's own success is seen at its next start instead ([afterRestart]). */
    private suspend fun answered(pkg: String, status: String, message: String?) {
        inFlight = null
        val offer = model.offers.firstOrNull { it.pkg == pkg }
        when (status) {
            "installed" -> if (offer != null && !offer.launcher) {
                val h = json(historyFile)?.optJSONArray("updated") ?: JSONArray()
                val entry = JSONObject().put("name", offer.name).put("from", offer.from).put("to", offer.to)
                    .put("at", SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                val kept = JSONArray().put(entry).also { for (i in 0 until minOf(h.length(), 9)) it.put(h.get(i)) }
                root.mkdirs(); historyFile.writeText(JSONObject().put("updated", kept).toString())
                saveFailure(pkg, null)
            }
            "needs-confirmation" -> saveFailure(pkg, "Android asked for a confirmation")
            "failed" -> if (offer?.launcher == true) {
                pendingFile.delete()
                withContext(Dispatchers.Main) { model.launcher = LauncherState.Failed(offer.from, message ?: "Android's installer refused it.", message.orEmpty()) }
            } else saveFailure(pkg, message ?: "Android's installer refused it")
        }
        refresh()
    }

    /** After Soma reopens from its own update: "updated", and the [phone] checks left for the agents in updated.json. */
    private suspend fun afterRestart() {
        val p = json(pendingFile) ?: run {
            json(updatedFile)?.let { u -> withContext(Dispatchers.Main) { model.launcher = LauncherState.Updated(u.optString("version"), u.optJSONArray("phoneSteps").strings().size) } }
            return
        }
        val now = info(context.packageName)?.let(::code) ?: return
        if (now < p.optLong("version")) return
        pendingFile.delete()
        val steps = p.optJSONArray("phoneSteps") ?: JSONArray()
        updatedFile.writeText(JSONObject().put("version", now).put("phoneSteps", steps).put("at", System.currentTimeMillis() / 1000).toString())
        withContext(Dispatchers.Main) { model.launcher = LauncherState.Updated(now.toString(), steps.length()) }
    }
}
