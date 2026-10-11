package dev.omalauncher.phone

/** Standing Accessibility consent enables automatic access; user pause and lock always win. */
object PhoneControlPolicy {
    fun requireAllowed(connected: Boolean, paused: Boolean, locked: Boolean, awake: Boolean) {
        check(connected) { "Enable Accessibility access in Android settings." }
        check(!paused) { "Phone control was paused. Resume access from the phone control screen." }
        check(!locked) { "Phone is locked. Unlock it yourself." }
        check(awake) { "Screen is off." }
    }
}
