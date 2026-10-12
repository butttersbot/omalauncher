package dev.omalauncher.phone

/** Standing Accessibility consent enables automatic access; user pause and lock always win. */
object PhoneControlPolicy {
    fun requireAllowed(connected: Boolean, paused: Boolean, locked: Boolean, awake: Boolean) {
        check(connected) { "Enable Accessibility access in Android settings." }
        check(!paused) { "Phone control was paused. Resume access from the phone control screen." }
        check(!locked) { "Phone is locked. Unlock it yourself." }
        check(awake) { "Screen is off." }
    }

    /** What an agent may still do while its owner has an approval to answer: look, never touch. */
    val whilePending = setOf("read", "apps", "screenshot")

    /** An agent that could tap could answer its own request, in the notification shade or on the approval screen. */
    fun requireNoPendingApproval(pending: Int, command: String) {
        check(pending == 0 || command in whilePending) {
            "A human approval is pending. Phone actions cannot approve or dismiss it."
        }
    }
}

/** The numbers the bridge's touches and reads go by, kept apart from the service so they can be checked without a phone. */
object PhoneTouch {
    /** How long a swipe may take: at most long enough to hold at a screen edge, and well inside what the bridge waits for an answer. */
    val SWIPE_MILLIS = 100L..5000L

    /** How long the screen is given to show what an action did before it is read: a screen sliding in is over by then. */
    const val SETTLE_MILLIS = 350L

    /** How much longer a read at [now] waits, when the screen was last acted on at [acted] (null: never); 0 is read at once. */
    fun settleWait(now: Long, acted: Long?): Long = if (acted == null) 0L else (acted + SETTLE_MILLIS - now).coerceIn(0L, SETTLE_MILLIS)

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
