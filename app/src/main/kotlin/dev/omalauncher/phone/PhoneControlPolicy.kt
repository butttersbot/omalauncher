package dev.omalauncher.phone

/** Standing Accessibility consent enables automatic access; user pause and lock always win. */
object PhoneControlPolicy {
    fun requireAllowed(connected: Boolean, paused: Boolean, locked: Boolean, awake: Boolean, background: Boolean = false) {
        check(connected) { "Enable Accessibility access in Android settings." }
        check(!paused) { "Phone control was paused. Resume access from the phone control screen." }
        check(background || !locked) { "Phone is locked. Unlock it yourself." }
        check(background || awake) { "Screen is off." }
    }

    /** What an agent may still do while its owner has an approval to answer: look, never touch. */
    val whilePending = setOf("read", "screen", "apps", "screenshot")

    /** An agent that could tap could answer its own request, in the notification shade or on the approval screen. */
    fun requireNoPendingApproval(pending: Int, command: String) {
        check(pending == 0 || command in whilePending) {
            "A human approval is pending. Phone actions cannot approve or dismiss it."
        }
    }
}

/**
 * Where phone control stands with Android: bound and working, switched off, or switched on in Accessibility
 * and not bound. The last is what an update can leave behind: Android starts binding the new build, stops the
 * package once more, and never binds again until the switch is turned off and on or the phone restarts.
 */
enum class PhoneLink(val id: String) { On("on"), Off("off"), Disconnected("disconnected") }

/** How long after the app starts Android is given to bind the service before it counts as disconnected. */
const val PHONE_BIND_GRACE_MILLIS = 20_000L

/** What the owner and the agent are told while phone control is switched on and not bound. */
const val PHONE_DISCONNECTED = "Phone control is disconnected. Turn it off and on in Accessibility settings, or restart the phone."

/**
 * [bound]: the service is running. [switchedOn]: Android's settings list it. [upMillis]: how long this process has
 * run. [healGaveUp]: PhoneControlHeal has spent its tries on a service he wants on, so off is not his choice and
 * reads as disconnected, with the remedy.
 */
fun phoneLink(bound: Boolean, switchedOn: Boolean, upMillis: Long, healGaveUp: Boolean = false): PhoneLink =
    when {
        bound -> PhoneLink.On
        !switchedOn -> if (healGaveUp) PhoneLink.Disconnected else PhoneLink.Off
        // Still within the time Android takes to bind it after a start.
        upMillis < PHONE_BIND_GRACE_MILLIS -> PhoneLink.On
        else -> PhoneLink.Disconnected
    }

/** The numbers the bridge's touches and reads go by, kept apart from the service so they can be checked without a phone. */
object PhoneTouch {
    /** How long a swipe may take: at most long enough to hold at a screen edge, and well inside what the bridge waits for an answer. */
    val SWIPE_MILLIS = 100L..5000L

    /**
     * The most the screen is given to show what an action did before it is read: a screen sliding in is over by then.
     * A screen that goes quiet sooner is read sooner ([phoneSettleWait]).
     */
    const val SETTLE_MILLIS = 350L

    /** How long `screen --until` waits for words to show, and how often it looks: inside what the bridge waits for an answer. */
    const val UNTIL_MILLIS = 5000L
    const val UNTIL_STEP_MILLIS = 100L

    /**
     * Where a finger lands for a control with these bounds on a screen [width] by [height]: the middle of the
     * part of it that is on screen. Null when none of it is, so nothing else is tapped in its name.
     */
    fun middle(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): Pair<Float, Float>? {
        val l = maxOf(left, 0); val t = maxOf(top, 0); val r = minOf(right, width); val b = minOf(bottom, height)
        if (l >= r || t >= b) return null
        return (l + r) / 2f to (t + b) / 2f
    }
}
