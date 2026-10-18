package dev.omalauncher.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Keeps Wireless debugging on (Boss, 2026-10-17: "anything that basically we want to keep on all the time"). The
 * phone's own adb over loopback is how oma-install, oma drop and the agents install and reach the phone, and
 * Android turns Wireless debugging off at every restart and whenever Wi-Fi drops; after the restart that day the
 * install of a new build waited on him turning it back on by hand. Unlike the phone control heal there is no
 * "his choice" here: he asked for it always on. Android itself still decides whether to start it on a network it
 * has not been told to trust; this only asks again. The launcher holds WRITE_SECURE_SETTINGS (Boss granted it over
 * adb); without it, or with Developer options off, nothing here does anything.
 * When to write is pure and at the top; AdbWifiHeal at the end only reads the facts it needs and writes.
 */

/** Android's setting for Wireless debugging (Settings.Global.ADB_WIFI_ENABLED, hidden from apps). */
const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

/** How long after Wi-Fi comes up the heal looks: Android turns Wireless debugging off as the network changes. */
const val ADB_WIFI_SETTLE_MILLIS = 5_000L

/**
 * Whether Wireless debugging is to be turned on: the launcher may write the setting ([granted]), Developer options
 * are on ([devOptions], without them Android ignores it), the phone is on Wi-Fi ([onWifi], it needs it), and the
 * setting is off ([on] is its value, null when unset).
 */
fun adbWifiWanted(granted: Boolean, devOptions: Boolean, onWifi: Boolean, on: Int?): Boolean = granted && devOptions && onWifi && on != 1

object AdbWifiHeal {
    private const val TAG = "adb-wifi-heal"
    private val watching = AtomicBoolean()

    /** At process start: a look now, and one each time Wi-Fi comes up from then on. */
    fun atStart(context: Context) {
        val app = context.applicationContext
        look(app)
        if (!watching.compareAndSet(false, true)) return
        val main = Handler(Looper.getMainLooper())
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                wifi,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        main.postDelayed({ look(app) }, ADB_WIFI_SETTLE_MILLIS)
                    }
                },
            )
        }.onFailure { watching.set(false); Log.w(TAG, "Can't watch Wi-Fi: ${it.message}") }
    }

    /** The launcher came to the front, or Wi-Fi came up. */
    fun look(context: Context) {
        val resolver = context.contentResolver
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val devOptions = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val on = runCatching { Settings.Global.getInt(resolver, ADB_WIFI_ENABLED) }.getOrNull()
        if (!adbWifiWanted(granted, devOptions, onWifi(context), on)) return
        runCatching { Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1) }
            .onSuccess { Log.i(TAG, "Wireless debugging was off on Wi-Fi: turned it back on") }
            .onFailure { Log.w(TAG, "Wireless debugging heal failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    // Any Wi-Fi network, not only the default one: with Tailscale up the default network is the VPN.
    @Suppress("DEPRECATION")
    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
}
