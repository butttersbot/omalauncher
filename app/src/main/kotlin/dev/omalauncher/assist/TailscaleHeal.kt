package dev.omalauncher.assist

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/*
 * Turns Tailscale back on when it is why the Mac cannot be reached (Boss, 2026-10-14: after every reboot Tailscale
 * on this phone stays off, the runner's Tailscale address goes dark, and he had to open the Tailscale app himself).
 * Tailscale's own receiver takes CONNECT_VPN with no screen, which was seen to bring the Mac back in three seconds.
 * Only that case is healed: once Tailscale has been seen up since the phone booted, its being off later is his
 * choice (Boss, 2026-10-14: "yes, respect the manual off"), and nothing is sent until the next boot.
 * When to ask is pure and at the top; TailscaleHeal at the end only reads the facts it needs and sends.
 */

/** Tailscale's own receiver and the one action sent to it. DISCONNECT_VPN is never sent from here. */
data class TailscaleTarget(val action: String, val packageName: String, val className: String)

val TAILSCALE_CONNECT = TailscaleTarget("com.tailscale.ipn.CONNECT_VPN", "com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")

/** What the last contact with the Mac came to, as far as the heal cares. */
enum class MacLink {
    /** It answered, even with a refusal: the network to it works. */
    Answered,

    /** No answer at all: the one case Tailscale being off can explain. */
    NoAnswer,

    /** No Mac is set up, so there is nothing to reach. */
    NotSetUp,
}

fun macLink(health: MacHealthResult): MacLink = when (health) {
    is MacHealthResult.Up, is MacHealthResult.Refused -> MacLink.Answered
    MacHealthResult.Unreachable -> MacLink.NoAnswer
    MacHealthResult.NotConfigured -> MacLink.NotSetUp
}

/**
 * [tried] is when Tailscale was last asked to connect, [tries] how often since the Mac last answered, and [off]
 * whether the last look found the Mac silent with no VPN up and Tailscale being turned back on; [chosen] whether
 * it found the same after Tailscale had been up since boot, so off by his choice. Those two are what the board
 * and `oma status` tell.
 */
data class HealState(val tried: Long? = null, val tries: Int = 0, val off: Boolean = false, val chosen: Boolean = false)

/**
 * When Tailscale was last seen up: [elapsed] by the clock that counts from boot (SystemClock.elapsedRealtime), so
 * a launcher restart keeps it and a reboot does not, and [boot] the phone's boot count then, null where unreadable.
 */
data class TailscaleSeen(val elapsed: Long, val boot: Int? = null)

/**
 * Whether [seen] was since the phone last booted, with [elapsed] and [boot] read now. A boot clock behind the one
 * kept means a reboot came between; the boot count also tells a reboot whose clock has since run past it.
 */
fun seenThisBoot(seen: TailscaleSeen?, elapsed: Long, boot: Int?): Boolean =
    seen != null && elapsed >= seen.elapsed && (seen.boot == null || boot == null || seen.boot == boot)

/**
 * Whether [host], an address of a VPN's link, is one Tailscale gives: its IPv4 range 100.64.0.0/10 or its IPv6
 * prefix fd7a:115c:a1e0::/48. Other VPNs use neither, which is what tells Tailscale's apart.
 */
fun tailscaleAddress(host: String): Boolean {
    val v4 = host.split('.').mapNotNull { it.toIntOrNull() }
    if (v4.size == 4) return v4[0] == 100 && v4[1] in 64..127
    return host.lowercase().startsWith("fd7a:115c:a1e0:")
}

/**
 * Whether one look shows Tailscale up: a VPN with a Tailscale address on it ([tailscaleVpn]), or the Mac, which is
 * reached at its Tailscale address, answering while a VPN is up ([vpnUp]), for a phone that hides the VPN's addresses.
 */
fun tailscaleSeenUp(link: MacLink, vpnUp: Boolean, tailscaleVpn: Boolean): Boolean = tailscaleVpn || (link == MacLink.Answered && vpnUp)

/** What one look decided: whether to ask Tailscale to connect now, and the state after. */
data class HealStep(val send: Boolean, val state: HealState)

/**
 * How long after the [tries]th ask in a row the next may be sent: 60 s, 2 min, 5 min, then every 10 min. Never
 * sooner than a minute, so the many health checks a busy chat makes cannot become many broadcasts.
 */
fun healWait(tries: Int): Long = when {
    tries <= 1 -> 60_000L
    tries == 2 -> 120_000L
    tries == 3 -> 300_000L
    else -> 600_000L
}

/**
 * One look at [now]. Only a Mac that gave no answer, with Tailscale [installed] and no VPN of any kind up
 * ([vpnUp]), is put down to Tailscale being off. Another VPN being up rules it out as well: Android runs one VPN
 * at a time, and connecting Tailscale would push that one aside. When Tailscale has been [upThisBoot], he turned it
 * off himself and nothing is sent; only one that never came up after a reboot is turned on. A Mac that answers
 * starts the count again.
 */
fun healStep(link: MacLink, vpnUp: Boolean, installed: Boolean, upThisBoot: Boolean, now: Long, state: HealState): HealStep = when {
    link == MacLink.Answered -> HealStep(false, HealState())
    link == MacLink.NotSetUp || !installed || vpnUp -> HealStep(false, state.copy(off = false, chosen = false))
    upThisBoot -> HealStep(false, state.copy(off = false, chosen = true))
    else -> {
        val last = state.tried
        // A clock set back is taken as time enough: otherwise the heal would wait out however far it went back.
        val due = last == null || now < last || now - last >= healWait(state.tries)
        if (due) HealStep(true, HealState(now, state.tries + 1, off = true)) else HealStep(false, state.copy(off = true, chosen = false))
    }
}

/** When the next ask may go, by the same clock as [HealState.tried]; null before the first. */
fun healNext(state: HealState): Long? = state.tried?.let { it + healWait(state.tries) }

/** What the board and the status file say when Tailscale is off by his choice, so the front agent can tell him. */
const val TAILSCALE_OFF_BY_HIM = "Tailscale is off on this phone (turned off by you); the Mac can't be reached until it is on."

/** What the board and the status file add to an unreachable Mac at [now] while Tailscale is the cause; null otherwise. */
fun healNote(state: HealState, now: Long): String? {
    if (state.chosen) return TAILSCALE_OFF_BY_HIM
    val tried = state.tried?.takeIf { state.off } ?: return null
    val seconds = (now - tried).coerceAtLeast(0) / 1000
    val ago = if (seconds < 60) "$seconds s" else if (seconds < 7200) "${seconds / 60} min" else "${seconds / 3600} hours"
    return "Tailscale was off on this phone; reconnecting (tried $ago ago)"
}

/**
 * Carries out [healStep] after each contact with the Mac. Called on the threads that ask the Mac, never the main
 * one. The facts are read for a Mac that gave no answer, and for one that answers only until Tailscale has been
 * seen up since boot, so once it has, an answering Mac costs nothing.
 */
object TailscaleHeal {
    @Volatile var state = HealState(); private set
    /** Kept in the app's preferences, so a launcher restart without a reboot still knows Tailscale was up. */
    @Volatile private var seen: TailscaleSeen? = null
    @Volatile private var loaded = false

    private const val TAG = "tailscale-heal"
    private const val PREFS = "tailscale-heal"

    /** The note for the board and the status file at [now], or null. */
    fun note(now: Long): String? = healNote(state, now)

    fun after(context: Context, link: MacLink) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(app)
        val before = seenThisBoot(seen(app), elapsed, boot)
        val looks = link == MacLink.NoAnswer || (link == MacLink.Answered && !before)
        val installed = looks && installed(app)
        val vpn = if (installed) vpnLook(app) else VpnLook(up = false, tailscale = false)
        val upNow = installed && tailscaleSeenUp(link, vpn.up, vpn.tailscale)
        if (upNow) remember(app, TailscaleSeen(elapsed, boot))
        val (was, step) = synchronized(this) { state to healStep(link, vpn.up, installed, before || upNow, now, state).also { state = it.state } }
        if (step.state.chosen && !was.chosen) Log.i(TAG, "Mac gave no answer and Tailscale is off after being up since boot: taken as turned off on purpose, not reconnecting")
        if (!step.send) return
        val sent = runCatching { app.sendBroadcast(Intent(TAILSCALE_CONNECT.action).setClassName(TAILSCALE_CONNECT.packageName, TAILSCALE_CONNECT.className)) }
        val next = (healNext(step.state) ?: now) - now
        Log.i(TAG, "Mac gave no answer and no VPN is up: asked Tailscale to connect, try ${step.state.tries}, next no sooner than ${next / 1000} s" +
            sent.exceptionOrNull()?.let { "; the broadcast failed: ${it.javaClass.simpleName}" }.orEmpty())
    }

    private fun seen(context: Context): TailscaleSeen? {
        if (!loaded) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            seen = if (prefs.contains("seen-elapsed")) TailscaleSeen(prefs.getLong("seen-elapsed", 0L), prefs.getInt("seen-boot", -1).takeIf { it >= 0 }) else null
            loaded = true
        }
        return seen
    }

    private fun remember(context: Context, now: TailscaleSeen) {
        seen = now
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen-elapsed", now.elapsed).putInt("seen-boot", now.boot ?: -1).apply()
    }

    /** How many times the phone has booted, which a reboot always changes; null where it cannot be read. */
    private fun bootCount(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    private fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo(TAILSCALE_CONNECT.packageName, 0); true }.getOrDefault(false)

    /** Whether any VPN is [up], and whether one of them is Tailscale's by its addresses ([tailscaleAddress]). */
    private data class VpnLook(val up: Boolean, val tailscale: Boolean)

    /**
     * Every network on the phone is looked at, not only this app's own, because Boss may leave the launcher out
     * of Tailscale's VPN. One that cannot be read counts as a VPN up and not Tailscale's, so a doubt never sends
     * anything and never takes his choice for granted.
     */
    private fun vpnLook(context: Context): VpnLook = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // allNetworks is deprecated for callback-based tracking, but a one-off look at every network needs it.
        @Suppress("DEPRECATION")
        val vpns = connectivity.allNetworks.filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val tailscale = vpns.any { network ->
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().any { tailscaleAddress(it.address.hostAddress.orEmpty()) }
        }
        VpnLook(vpns.isNotEmpty(), tailscale)
    }.getOrDefault(VpnLook(up = true, tailscale = false))
}
