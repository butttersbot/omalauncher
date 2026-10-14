package dev.omalauncher.assist

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/*
 * Turns Tailscale back on when it is why the Mac cannot be reached (Boss, 2026-10-14: after every reboot Tailscale
 * on this phone stays off, the runner's Tailscale address goes dark, and he had to open the Tailscale app himself).
 * Tailscale's own receiver takes CONNECT_VPN with no screen, which was seen to bring the Mac back in three seconds.
 * When to ask is pure and at the top; TailscaleHeal at the end only reads the two facts it needs and sends.
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
 * whether the last look found the Mac silent with no VPN up, which is what the board and `oma status` tell.
 */
data class HealState(val tried: Long? = null, val tries: Int = 0, val off: Boolean = false)

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
 * at a time, and connecting Tailscale would push that one aside. A Mac that answers starts the count again.
 */
fun healStep(link: MacLink, vpnUp: Boolean, installed: Boolean, now: Long, state: HealState): HealStep = when {
    link == MacLink.Answered -> HealStep(false, HealState())
    link == MacLink.NotSetUp || !installed || vpnUp -> HealStep(false, state.copy(off = false))
    else -> {
        val last = state.tried
        // A clock set back is taken as time enough: otherwise the heal would wait out however far it went back.
        val due = last == null || now < last || now - last >= healWait(state.tries)
        if (due) HealStep(true, HealState(now, state.tries + 1, off = true)) else HealStep(false, state.copy(off = true))
    }
}

/** When the next ask may go, by the same clock as [HealState.tried]; null before the first. */
fun healNext(state: HealState): Long? = state.tried?.let { it + healWait(state.tries) }

/** What the board and the status file add to an unreachable Mac at [now] while Tailscale is the cause; null otherwise. */
fun healNote(state: HealState, now: Long): String? {
    val tried = state.tried?.takeIf { state.off } ?: return null
    val seconds = (now - tried).coerceAtLeast(0) / 1000
    val ago = if (seconds < 60) "$seconds s" else if (seconds < 7200) "${seconds / 60} min" else "${seconds / 3600} hours"
    return "Tailscale was off on this phone; reconnecting (tried $ago ago)"
}

/**
 * Carries out [healStep] after each contact with the Mac. Called on the threads that ask the Mac, never the main
 * one. The facts are read only for a Mac that gave no answer, so one that answers costs nothing.
 */
object TailscaleHeal {
    @Volatile var state = HealState(); private set

    private const val TAG = "tailscale-heal"

    /** The note for the board and the status file at [now], or null. */
    fun note(now: Long): String? = healNote(state, now)

    fun after(context: Context, link: MacLink) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val silent = link == MacLink.NoAnswer
        val installed = silent && installed(app)
        val vpn = silent && installed && vpnUp(app)
        val step = synchronized(this) { healStep(link, vpn, installed, now, state).also { state = it.state } }
        if (!step.send) return
        val sent = runCatching { app.sendBroadcast(Intent(TAILSCALE_CONNECT.action).setClassName(TAILSCALE_CONNECT.packageName, TAILSCALE_CONNECT.className)) }
        val next = (healNext(step.state) ?: now) - now
        Log.i(TAG, "Mac gave no answer and no VPN is up: asked Tailscale to connect, try ${step.state.tries}, next no sooner than ${next / 1000} s" +
            sent.exceptionOrNull()?.let { "; the broadcast failed: ${it.javaClass.simpleName}" }.orEmpty())
    }

    private fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo(TAILSCALE_CONNECT.packageName, 0); true }.getOrDefault(false)

    /**
     * Whether any network on the phone is a VPN. Every network is looked at, not only this app's own, because
     * Boss may leave the launcher out of Tailscale's VPN. One that cannot be read counts as up, so a doubt never
     * sends anything.
     */
    private fun vpnUp(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // allNetworks is deprecated for callback-based tracking, but a one-off look at every network needs it.
        @Suppress("DEPRECATION")
        connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(true)
}
