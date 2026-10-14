package dev.omalauncher.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/*
 * Puts phone control back when something other than Boss switched it off (Boss, 2026-10-14: "why did phone control
 * switch off? Cause we had it on. So we need that to be self-healing also."). Two installs found it gone from
 * Android's list after it had been on, with a force-close by the Mac's backup in between; Android drops a package's
 * accessibility services from that list when the package is force-stopped, and nothing else records who did it.
 * Like the Tailscale heal, it never goes against his own choice: switched off from Android's settings or the quick
 * panel while the launcher runs, or paused from the app, it stays off until he turns it on again himself.
 * The launcher holds WRITE_SECURE_SETTINGS (Boss granted it over adb); without it nothing here does anything.
 * When to write is pure and at the top; PhoneControlHeal at the end only reads the facts it needs and writes.
 */

/** How many heals one boot gets before the tile says it is disconnected and how to mend it. */
const val PHONE_HEAL_TRIES = 3

/** How long after one heal the next may be tried. */
const val PHONE_HEAL_WAIT_MILLIS = 5 * 60_000L

/**
 * The screens he switches an accessibility service off from: Android's settings, Samsung's own accessibility
 * settings, and the quick panel, which can carry a tile for it. An unbind with one of these in front is his doing.
 */
val PHONE_OWNER_SCREENS = setOf("com.android.settings", "com.samsung.accessibility", "com.android.systemui")

/** Whether an unbind, with [front] the package of the last window to come to the front, was him switching it off. */
fun ownerTurnedOff(front: String?): Boolean = front != null && front in PHONE_OWNER_SCREENS

/**
 * One look's facts. [granted]: the launcher holds WRITE_SECURE_SETTINGS. [wanted]: it connected and he has not
 * switched it off since. [offByOwner]: he switched it off or paused it. [listed]: Android's list of enabled services
 * has it. [bound]: the service runs. [upMillis]: how long this process has run. [tries]: heals this boot since it
 * last connected. [sinceTry]: how long ago the last of them was, null with none.
 */
data class PhoneHealFacts(
    val granted: Boolean,
    val wanted: Boolean,
    val offByOwner: Boolean,
    val listed: Boolean,
    val bound: Boolean,
    val upMillis: Long,
    val tries: Int,
    val sinceTry: Long?,
)

enum class PhoneHealAction {
    /** Nothing to do now. */
    Leave,

    /** Not in the list: it is added at the end. */
    Add,

    /** In the list and not bound: taken out and put back, which is what makes Android bind it, as oma-install does. */
    Rebind,

    /** The tries are spent: the tile says it is disconnected. */
    GiveUp,
}

fun phoneHeal(f: PhoneHealFacts): PhoneHealAction = when {
    !f.granted || !f.wanted || f.offByOwner || f.bound -> PhoneHealAction.Leave
    // Still within the time Android takes to bind it after a start.
    f.listed && f.upMillis < PHONE_BIND_GRACE_MILLIS -> PhoneHealAction.Leave
    f.tries >= PHONE_HEAL_TRIES -> PhoneHealAction.GiveUp
    f.sinceTry != null && f.sinceTry < PHONE_HEAL_WAIT_MILLIS -> PhoneHealAction.Leave
    f.listed -> PhoneHealAction.Rebind
    else -> PhoneHealAction.Add
}

/** Whether a list [entry] names [mine], a flattened component, in the long form or in Android's short one, whose class begins with a dot. */
fun sameService(entry: String, mine: String): Boolean {
    fun long(name: String): String {
        val pkg = name.substringBefore('/')
        val cls = name.substringAfter('/', "")
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else name
    }
    return long(entry.trim()) == long(mine)
}

/** The other services in [list], exactly as written and in their order. */
fun otherServices(list: String?, mine: String): List<String> =
    list.orEmpty().split(':').filter { it.isNotBlank() && !sameService(it, mine) }

/** [list] with [mine] once, at the end if it was not there; every other service is left as it was. */
fun servicesWith(list: String?, mine: String): String {
    val entries = list.orEmpty().split(':').filter { it.isNotBlank() }
    if (entries.count { sameService(it, mine) } == 1) return entries.joinToString(":")
    return (otherServices(list, mine) + mine).joinToString(":")
}

/** [list] without [mine]; null when nothing is left, so the setting is cleared rather than set empty. */
fun servicesWithout(list: String?, mine: String): String? = otherServices(list, mine).joinToString(":").ifEmpty { null }

/**
 * Whether accessibility_enabled is set to 1 along with the heal: only when it is off and no other service is in
 * [list], so its being off was down to this one being missing. Off with others listed is someone's choice.
 */
fun turnAccessibilityOn(enabled: Int?, list: String?, mine: String): Boolean = enabled != 1 && otherServices(list, mine).isEmpty()

/** What the board says when he switched it off himself. */
const val PHONE_OFF_BY_HIM = "Phone control is off (you turned it off)."

/** How long the board still says a heal happened. */
const val PHONE_HEALED_SHOWN_MILLIS = 60 * 60_000L

/**
 * The board's line about phone control at [now], or null when there is nothing to say. [healed] is when it was last
 * written back, by the same clock as [now]; [gaveUp] that the tries are spent.
 */
fun phoneHealNote(offByOwner: Boolean, bound: Boolean, healed: Long?, gaveUp: Boolean, now: Long): String? {
    if (bound) {
        val ago = healed?.let { now - it }?.takeIf { it in 0 until PHONE_HEALED_SHOWN_MILLIS } ?: return null
        return "Phone control was off; turned back on (${if (ago < 60_000) "just now" else "${ago / 60_000} min ago"})."
    }
    return when {
        offByOwner -> PHONE_OFF_BY_HIM
        gaveUp -> "Phone control was off; turning it back on did not work. $PHONE_DISCONNECTED"
        healed != null && now - healed in 0 until PHONE_HEAL_WAIT_MILLIS -> "Phone control was off; turning it back on."
        else -> null
    }
}

/**
 * Remembers whether he wants phone control on and carries out [phoneHeal]. The writes and their wait are on a
 * thread of their own, never the main one.
 */
object PhoneControlHeal {
    private const val TAG = "phone-heal"
    private const val PREFS = "phone-control-heal"
    /** Read by oma-install, which leaves a missing service to the launcher when this key is there. */
    private const val WANTED = "wanted-since"
    private const val OFF_BY_OWNER = "off-by-owner"
    private const val HEALED = "healed"
    private const val TRIED = "tried-elapsed"
    private const val TRIES = "tries"
    private const val BOOT = "boot"
    /** How long the service is left out of the list before it is put back, as oma-install waits. */
    private const val REBIND_GAP_MILLIS = 2_000L

    private val busy = AtomicBoolean()
    /** Whether giving up has been logged and shown in this process, so each look does not say it again. */
    @Volatile private var toldGaveUp = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The service connected: he wants it on, unless it came up still paused from the app. Main thread. */
    fun connected(context: Context, paused: Boolean) {
        val edit = prefs(context).edit().remove(TRIED).remove(TRIES).remove(BOOT)
        if (!paused) edit.putLong(WANTED, System.currentTimeMillis()).remove(OFF_BY_OWNER)
        edit.apply()
        toldGaveUp = false
    }

    /** Android unbound the service while this process lives; [front] is the last window that came to the front. */
    fun unbound(context: Context, front: String?) {
        if (ownerTurnedOff(front)) {
            ownerOff(context)
            Log.i(TAG, "Phone control was switched off with $front in front: taken as his choice, not turning it back on")
        } else {
            Log.i(TAG, "Phone control was unbound with ${front ?: "nothing seen"} in front: not taken as his choice")
        }
    }

    /** He paused phone control from the app, or asked an agent to stop it: his choice, as a switch-off is. */
    fun paused(context: Context) = ownerOff(context)

    /** He resumed it from the app while it is connected: he wants it on again. */
    fun resumed(context: Context) {
        if (PhoneService.instance != null) connected(context, paused = false)
    }

    // Committed at once: the process may not outlive the unbind by much.
    private fun ownerOff(context: Context) {
        prefs(context).edit().remove(WANTED).putBoolean(OFF_BY_OWNER, true).commit()
    }

    /** At process start: a look now, and one once Android has had its time to bind the service. */
    fun atStart(context: Context) = look(context, again = true)

    /** The launcher came to the front. */
    fun look(context: Context) = look(context, again = false)

    private fun look(context: Context, again: Boolean) {
        val app = context.applicationContext
        if (!granted(app) || !busy.compareAndSet(false, true)) return
        thread(name = "phone-heal", isDaemon = true) {
            try {
                runCatching { step(app) }.onFailure { Log.w(TAG, "Phone control heal failed: ${it.javaClass.simpleName}: ${it.message}") }
                if (again) {
                    Thread.sleep(PHONE_BIND_GRACE_MILLIS + 1_000)
                    runCatching { step(app) }.onFailure { Log.w(TAG, "Phone control heal failed: ${it.javaClass.simpleName}: ${it.message}") }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun granted(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun mine(context: Context) = ComponentName(context, PhoneService::class.java).flattenToString()

    private fun list(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private fun listed(context: Context) = list(context).orEmpty().split(':').any { sameService(it, mine(context)) }

    private fun bootCount(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    /** Heals since it last connected, counted only within this boot. */
    private fun tries(context: Context): Pair<Int, Long?> {
        val prefs = prefs(context)
        val boot = bootCount(context)
        val tried = prefs.getLong(TRIED, -1L).takeIf { it >= 0 } ?: return 0 to null
        val elapsed = SystemClock.elapsedRealtime()
        val kept = prefs.getInt(BOOT, -1).takeIf { it >= 0 }
        // A boot clock behind the try, or another boot count, means the phone has restarted since.
        if (elapsed < tried || (kept != null && boot != null && kept != boot)) return 0 to null
        return prefs.getInt(TRIES, 0) to elapsed - tried
    }

    /** Whether this boot's tries are spent with phone control still off, for the tile and `oma-phone status`. */
    fun gaveUp(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.contains(WANTED) && !prefs.getBoolean(OFF_BY_OWNER, false) && tries(context).first >= PHONE_HEAL_TRIES
    }

    private fun step(context: Context) {
        val prefs = prefs(context)
        val (tries, sinceTry) = tries(context)
        val facts = PhoneHealFacts(
            granted = granted(context),
            wanted = prefs.contains(WANTED),
            offByOwner = prefs.getBoolean(OFF_BY_OWNER, false),
            listed = listed(context),
            bound = PhoneService.instance != null,
            upMillis = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(),
            tries = tries,
            sinceTry = sinceTry,
        )
        when (phoneHeal(facts)) {
            PhoneHealAction.Leave -> return
            PhoneHealAction.GiveUp -> {
                if (toldGaveUp) return
                toldGaveUp = true
                Log.i(TAG, "Phone control is still off after $PHONE_HEAL_TRIES heals this boot: left to Boss, tile says disconnected")
                // The status file is written where everything else writes it from.
                Handler(Looper.getMainLooper()).post { dev.omalauncher.assist.AgentTasks.phoneChanged(context) }
                return
            }
            PhoneHealAction.Add -> add(context)
            PhoneHealAction.Rebind -> {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesWithout(list(context), mine(context)))
                Thread.sleep(REBIND_GAP_MILLIS)
                add(context)
            }
        }
        prefs.edit().putLong(TRIED, SystemClock.elapsedRealtime()).putInt(TRIES, tries + 1).putInt(BOOT, bootCount(context) ?: -1)
            .putLong(HEALED, System.currentTimeMillis()).apply()
        Log.i(TAG, "Phone control was ${if (facts.listed) "switched on and not bound" else "missing from the enabled services"}: " +
            "${if (facts.listed) "took it out and put it back" else "added it back"}, try ${tries + 1} of $PHONE_HEAL_TRIES this boot")
    }

    /** Puts the service at the end of the list as it is now, and accessibility on if only its absence had it off. */
    private fun add(context: Context) {
        val resolver = context.contentResolver
        val before = list(context)
        val enabled = runCatching { Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED) }.getOrNull()
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesWith(before, mine(context)))
        if (turnAccessibilityOn(enabled, before, mine(context))) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** The board's line at [now], wall clock: see [phoneHealNote]. */
    fun note(context: Context, now: Long): String? {
        val prefs = prefs(context)
        return phoneHealNote(prefs.getBoolean(OFF_BY_OWNER, false), PhoneService.instance != null,
            prefs.getLong(HEALED, -1L).takeIf { it >= 0 }, gaveUp(context), now)
    }

    /** `oma-phone status`: when it was last healed, in Unix seconds, and whether he switched it off. */
    fun status(context: Context, reply: org.json.JSONObject) {
        val prefs = prefs(context)
        prefs.getLong(HEALED, -1L).takeIf { it >= 0 }?.let { reply.put("healed", it / 1000) }
        if (prefs.getBoolean(OFF_BY_OWNER, false)) reply.put("off_by_owner", true)
    }
}
