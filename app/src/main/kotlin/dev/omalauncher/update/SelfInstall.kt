package dev.omalauncher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Updates installed by Oma itself, through Android's own package installer, so they need no Wi-Fi (Boss, 2026-10-22:
 * "is there any route we can build so updates dont rely on wifi? i am about to go to a place without wifi but i will
 * have 5g"). The adb route needs Wireless debugging, which Android turns off off Wi-Fi.
 *
 * Linux asks through the phone bridge (`oma-install` falls back to it) with an APK it has staged in its own files. Only
 * Oma or Soma Messages, signed with this app's own key, at a newer version, are taken. The first time, Android asks
 * Boss to let Oma install apps and to confirm the update; once Oma is the app that installed them, it asks no more.
 * What came of it is written to Linux's `~/.cache/oma-update/state.json`, which `oma-install --status` reads.
 */
object SelfInstall {
    const val ACTION = "dev.omalauncher.update.INSTALLED"
    private const val MESSAGES = "dev.omalauncher.messages"

    fun stateFile(context: Context) = File(context.filesDir, "arch/root/.cache/oma-update/state.json")

    private fun write(
        context: Context,
        state: JSONObject,
    ) {
        val f = stateFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(state.put("at", System.currentTimeMillis() / 1000).toString())
        tmp.renameTo(f)
    }

    private fun state(context: Context) = runCatching { JSONObject(stateFile(context).readText()) }.getOrNull()

    private fun certs(info: PackageInfo?): Set<String> {
        val signing = info?.signingInfo ?: return emptySet()
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signers.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Checks [apk] and hands it to Android's installer; the answer says what was done, or why not. Off the main thread. */
    fun install(
        context: Context,
        apk: File,
    ): JSONObject {
        val pm = context.packageManager
        val fail = { why: String -> JSONObject().put("ok", false).put("error", why) }
        if (!apk.isFile) return fail("No APK at that path.")
        if (!pm.canRequestPackageInstalls()) {
            // His to allow, once: Android's own page for it is opened, nothing is switched here.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return fail("Oma may not install apps yet. Android's page to allow it was opened on the phone: Boss turns on \"Allow from this source\", then retry.")
        }
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return fail("Not an APK Android can read.")
        val pkg = archive.packageName
        if (pkg != context.packageName && pkg != MESSAGES) return fail("Only Oma (${context.packageName}) or Soma Messages are installed this way, not $pkg.")
        val own = certs(runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull())
        val theirs = certs(archive)
        if (own.isEmpty() || theirs.isEmpty() || theirs.none { it in own }) return fail("Not signed with this phone's working key.")
        val installed = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        if (installed != null && version(archive) <= version(installed)) return fail("Version ${version(archive)} is not newer than the installed ${version(installed)}.")
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                write(context, JSONObject().put("package", pkg).put("version", version(archive)).put("status", "committed").put("session", id))
                val done = Intent(context, SelfInstallReceiver::class.java).setAction(ACTION)
                val pending = PendingIntent.getBroadcast(context, id, done, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            write(context, JSONObject().put("package", pkg).put("version", version(archive)).put("status", "failed").put("message", e.message))
            return fail("Android's installer refused it: ${e.message}")
        }
        return JSONObject().put("ok", true).put("package", pkg).put("version", version(archive))
            .put("note", if (pkg == context.packageName) "Committed. Oma restarts as it updates; Linux, login and settings stay." else "Committed.")
    }

    /** At start: an update of Oma itself that was committed and is now running is written down as done. */
    fun afterStart(context: Context) {
        val s = state(context) ?: return
        if (s.optString("package") != context.packageName || s.optString("status") !in setOf("committed", "confirming")) return
        val now = runCatching { version(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrNull() ?: return
        if (now >= s.optLong("version")) write(context, s.put("status", "installed"))
    }

    fun answered(
        context: Context,
        intent: Intent,
    ) {
        val s = state(context) ?: JSONObject()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The first time (Oma did not install what is there), Android asks Boss himself.
                @Suppress("DEPRECATION")
                val ask = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                ask?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                write(context, s.put("status", "confirming"))
            }
            PackageInstaller.STATUS_SUCCESS -> write(context, s.put("status", "installed"))
            else -> write(context, s.put("status", "failed").put("code", status).put("message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}

class SelfInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == SelfInstall.ACTION) SelfInstall.answered(context, intent)
    }
}
