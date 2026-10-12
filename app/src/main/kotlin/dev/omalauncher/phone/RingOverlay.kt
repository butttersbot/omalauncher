package dev.omalauncher.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import dev.omalauncher.oma.ring.RingView

// The ring over other apps: a window the accessibility service draws the same ring in. The owner approved
// it (2026-10-12), so it is on unless the one stored setting says otherwise; nothing in the app writes that
// setting yet and no screen offers it.

private const val RING_PREFS = "ring"

/** The setting: false switches the overlay off. Read by [ringOverlayAllowed] and by nothing else; written by nothing. */
private const val RING_OVERLAY_KEY = "ring_overlay"

/** Set once the owner has been told the ring stays on home. */
private const val RING_TOLD_KEY = "told_home_only"

/** The file the setting lives in. */
fun ringPrefs(context: Context): SharedPreferences = context.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE)

/**
 * Whether the overlay may show at all: yes unless the stored setting is false. Not a boolean or unreadable
 * mean off. This is the only place the setting is read.
 */
fun ringOverlayAllowed(prefs: SharedPreferences): Boolean = runCatching { prefs.getBoolean(RING_OVERLAY_KEY, true) }.getOrDefault(false)

/** Everything that decides whether the overlay is up at this moment. */
data class OverlayFacts(
    /** [ringOverlayAllowed]. */
    val allowed: Boolean = false,
    /** The accessibility service is bound. */
    val connected: Boolean = false,
    /** The owner paused phone control. */
    val paused: Boolean = false,
    /** The keyguard is up. */
    val locked: Boolean = true,
    /** The screen is on. */
    val awake: Boolean = false,
    /** The service is taking a screenshot for the agent. */
    val capturing: Boolean = false,
    /** The assistant window is up or about to be, and Android takes a screenshot for it. */
    val assistantUp: Boolean = false,
    /** One of this app's own screens is in front; home draws its own ring. */
    val ownScreen: Boolean = false,
    /** The ring is in a state other than off. */
    val lit: Boolean = false,
)

/** Whether to look again soon: the overlay is up, or would be but for the lock or the screen in front. Never while it isn't allowed. */
fun ringOverlayWatching(facts: OverlayFacts): Boolean = facts.allowed && facts.connected && facts.lit

/** The overlay is up only when every one of these holds; anything else takes it down. */
fun ringOverlayWanted(facts: OverlayFacts): Boolean =
    facts.allowed && facts.connected && !facts.paused && !facts.locked && facts.awake &&
        !facts.capturing && !facts.assistantUp && !facts.ownScreen && facts.lit

/** How long a removed overlay is given to leave the screen before a screenshot is taken. */
const val CAPTURE_SETTLE_MILLIS = 120L

/** How often the overlay looks again at the lock, the screen in front and the ring while it could be showing. */
const val OVERLAY_CHECK_MILLIS = 500L

/**
 * Whether to say, once, that the ring can't follow into other apps (PRD UP-11): only when the overlay is
 * allowed, so it would have followed, and the accessibility service is off or paused.
 */
fun ringStaysHome(allowed: Boolean, serviceActive: Boolean, lit: Boolean, told: Boolean): Boolean = allowed && !serviceActive && lit && !told

/** What the rail says that once; short enough for its one line on the cover screen. */
const val RING_STAYS_HOME = "Ring can't follow into apps: phone control is off"

/** The once-only line, for whoever shows it on home. */
object RingFollow {
    @Volatile private var telling = false

    /** Whether to show [RING_STAYS_HOME] now. It starts at most once ever, and then lasts until the ring next goes dark. */
    fun homeOnly(context: Context, lit: Boolean): Boolean {
        if (!lit) {
            telling = false
            return false
        }
        if (telling) return true
        val prefs = ringPrefs(context)
        if (!ringStaysHome(ringOverlayAllowed(prefs), PhoneAccess.active, lit, prefs.getBoolean(RING_TOLD_KEY, false))) return false
        prefs.edit().putBoolean(RING_TOLD_KEY, true).apply()
        telling = true
        return true
    }
}

/** The overlay's window as plain numbers, so its rules are tested without Android. */
data class OverlayWindow(
    val type: Int,
    val flags: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val cutoutMode: Int,
    val title: String,
)

/**
 * The only window the overlay is ever added with. An accessibility overlay needs no permission of its own.
 * It can't be touched or focused, so every touch and key goes to the app underneath; it asks for nothing on
 * the lock screen, keeps nothing awake, and isn't marked secure, which would black out the agent's screenshot.
 */
fun ringOverlayWindow(sdk: Int = Build.VERSION.SDK_INT) = OverlayWindow(
    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    width = WindowManager.LayoutParams.MATCH_PARENT,
    height = WindowManager.LayoutParams.MATCH_PARENT,
    format = PixelFormat.TRANSLUCENT,
    // The light runs along the real edge, past a camera cutout.
    cutoutMode = if (sdk >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
    title = "Oma ring",
)

private fun OverlayWindow.params() = WindowManager.LayoutParams(width, height, type, flags, format).also {
    it.layoutInDisplayCutoutMode = cutoutMode
    it.title = title
    // Over the whole display, under no system bar's inset.
    if (Build.VERSION.SDK_INT >= 30) it.fitInsetsTypes = 0
}

/** Where the overlay's window goes up and comes down. */
interface RingWindow {
    /** False if Android refused the window. */
    fun add(): Boolean

    /** False if the window could not be taken down and is still on the screen. */
    fun remove(): Boolean
}

/** What [RingOverlay.beforeCapture] answers when the window is still up: no screenshot may be taken. */
const val CAPTURE_REFUSED = -1L

/**
 * Keeps the overlay's window matching [facts]. All of when it shows is here and in [ringOverlayWanted]:
 * the window goes up only from [sync], and only when the facts want it. Main thread only.
 */
class RingOverlay(private val window: RingWindow, private val facts: () -> OverlayFacts) {
    var shown = false
        private set
    private var captures = 0

    /** Puts the window up or takes it down, whichever the facts now ask for. True if it should be looked at again soon. */
    fun sync(): Boolean {
        val now = facts()
        val want = captures == 0 && ringOverlayWanted(now)
        if (want && !shown) shown = window.add() else if (!want && shown) hide()
        return ringOverlayWatching(now)
    }

    /** The fold or the rotation changed: the window is taken down and put up again at the new size. */
    fun relayout(): Boolean {
        hide()
        return sync()
    }

    /**
     * Before the service takes a screenshot for the agent: the window comes down and stays down until
     * [afterCapture]. Returns how long to wait for it to leave the screen; 0 if it wasn't up. If it couldn't
     * be taken down it answers [CAPTURE_REFUSED]: the screenshot must not be taken, and no [afterCapture] follows.
     */
    fun beforeCapture(): Long {
        if (!shown) {
            captures++
            return 0L
        }
        hide()
        if (shown) return CAPTURE_REFUSED
        captures++
        return CAPTURE_SETTLE_MILLIS
    }

    /** The screenshot was taken or failed: the window may come back. */
    fun afterCapture(): Boolean {
        captures = maxOf(0, captures - 1)
        return sync()
    }

    /** The service is paused, unbound or going away. */
    fun close() = hide()

    /** A window that won't come down still counts as up, so nothing is captured with it and the next look tries again. */
    private fun hide() {
        if (!shown) return
        shown = !window.remove()
    }
}

/** The real window: one [RingView], which draws the ring and nothing else, added by the accessibility service. */
class ServiceRingWindow(private val service: AccessibilityService) : RingWindow {
    private var view: RingView? = null

    override fun add(): Boolean = runCatching {
        val ring = RingView(service)
        service.getSystemService(WindowManager::class.java).addView(ring, ringOverlayWindow().params())
        view = ring
    }.isSuccess

    override fun remove(): Boolean {
        val ring = view ?: return true
        try {
            // One that never got attached, or already lost its window, isn't on the screen.
            if (ring.isAttachedToWindow) service.getSystemService(WindowManager::class.java).removeViewImmediate(ring)
        } catch (e: Exception) {
            Log.w("ring", "The ring overlay could not be removed", e)
            if (ring.isAttachedToWindow) return false
        }
        view = null
        return true
    }
}
