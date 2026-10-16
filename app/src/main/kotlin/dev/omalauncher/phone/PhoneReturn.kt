package dev.omalauncher.phone

/*
 * Back where he was (Boss, 2026-10-16: "at the end of phone use … it brings me back to the screen that I was on when
 * the agent's done using the phone"). When an agent first moves the screen, the phone remembers what was in front:
 * the Oma chat, a page of home, or another app. When the agent's work ends that comes back, unless he took the phone
 * himself in the meantime, or the agent said the screen it ended on is for him (`oma-phone stay`, for "open Spotify").
 * What is remembered and what is done at the end are plain functions here, unit tested; PhoneService keeps the state.
 */

/** What he had in front: the chat, home (on the page [page], its id), or the app [pkg]. */
sealed interface PhoneOrigin {
    data object Chat : PhoneOrigin

    data class Home(val page: String?) : PhoneOrigin

    data class App(val pkg: String) : PhoneOrigin
}

/** The system's own windows: the shade, the lock screen, the recents list. Nothing to come back to. */
private val SYSTEM = setOf("com.android.systemui", "android", "com.samsung.android.app.cocktailbarservice")

/** The launcher's own passing screens: an approval, the listening screen. Under them is what he was on. */
private val PASSING = listOf("AgentApprovalActivity", "AssistantPromptActivity", "VoiceInteraction", "Dialog", "Toast")

/**
 * What a window coming to the front says he is on, as [pkg] and [className] tell it, the launcher being [own]: null
 * for a window that is no place to come back to (the system's, one of the launcher's passing screens, a nameless one).
 * [page] is the page home shows, asked only when it is home.
 */
fun phoneOrigin(pkg: String?, className: String?, own: String, page: () -> String?): PhoneOrigin? {
    if (pkg.isNullOrBlank() || pkg in SYSTEM) return null
    val name = className.orEmpty()
    if (pkg != own) return PhoneOrigin.App(pkg)
    if (PASSING.any { it in name }) return null
    return when {
        name.endsWith("OmaChatActivity") -> PhoneOrigin.Chat
        name.endsWith("MainActivity") -> PhoneOrigin.Home(page())
        else -> null
    }
}

/** How long after the agent's last move a window changing is still taken for that move's doing, not his. */
const val PHONE_OWN_MOVE_MILLIS = 3_000L

/**
 * Whether a window of [pkg] coming to the front at [now], with the agent's last move at [acted], is him taking the
 * phone. The launcher's own windows never are (he answers an approval there), nor the system's (a shade, a dialog).
 */
fun phoneTakenOver(pkg: String?, own: String, now: Long, acted: Long?): Boolean =
    acted != null && now - acted > PHONE_OWN_MOVE_MILLIS && !pkg.isNullOrBlank() && pkg != own && pkg !in SYSTEM

/** What happens to the screen when the agent's work ends. */
sealed interface PhoneBack {
    /** Nothing is moved; [why] is for the log. */
    data class Leave(val why: String) : PhoneBack

    data class Bring(val origin: PhoneOrigin) : PhoneBack
}

/**
 * At the end of the agent's work: what he had in front ([origin], null if the agent never moved the screen) comes
 * back, unless the agent said to [stay], he [took] the phone himself meanwhile, it is [locked], or he is already
 * there ([here], what is in front now as [phoneOrigin] reads it).
 */
fun phoneBack(origin: PhoneOrigin?, here: PhoneOrigin?, stay: Boolean, took: Boolean, locked: Boolean): PhoneBack = when {
    origin == null -> PhoneBack.Leave("the screen was never moved")
    stay -> PhoneBack.Leave("the agent left this screen up for him")
    took -> PhoneBack.Leave("he took the phone himself")
    locked -> PhoneBack.Leave("the phone is locked")
    here == origin -> PhoneBack.Leave("he is already there")
    else -> PhoneBack.Bring(origin)
}

/**
 * How long a read waits after one of the agent's own moves: until the screen has said nothing for [PHONE_QUIET_MILLIS],
 * at least [PHONE_SETTLE_MIN_MILLIS] after the move and never more than [PhoneTouch.SETTLE_MILLIS], the flat wait it
 * replaces. 0 is read at once: no move, or long enough ago. [changed] is when the screen last said it changed.
 */
fun phoneSettleWait(now: Long, acted: Long?, changed: Long?): Long {
    if (acted == null) return 0L
    val latest = acted + PhoneTouch.SETTLE_MILLIS
    if (now >= latest) return 0L
    val quietAt = maxOf(acted + PHONE_SETTLE_MIN_MILLIS, (changed ?: acted) + PHONE_QUIET_MILLIS)
    return (minOf(quietAt, latest) - now).coerceIn(0L, PhoneTouch.SETTLE_MILLIS)
}

/** How long the screen has to say nothing about a change before it is taken to have shown what a move did. */
const val PHONE_QUIET_MILLIS = 120L

/** The least a read waits after a move, so a screen that only starts changing a beat later is not read too soon. */
const val PHONE_SETTLE_MIN_MILLIS = 150L

/** How often a wait for the screen to settle looks again. */
const val PHONE_SETTLE_STEP_MILLIS = 30L

/** How many times `press` and `fill` scroll on to find a label that is not on the screen, each way. */
const val PHONE_FIND_SCROLLS = 6
