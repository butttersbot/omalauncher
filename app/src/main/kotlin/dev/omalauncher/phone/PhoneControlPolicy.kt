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
