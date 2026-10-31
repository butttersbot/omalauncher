package dev.omalauncher.phone

/*
 * What an agent's touches are held to (builder A's handoff, 2026-10-21: a URL went into Boss's own chat composer
 * because `fill ""` took whatever field was in front, and a stray `back` sent later taps onto home). The app an agent
 * opened is the app its touches are for, until it says otherwise; Soma's own chat box is never typed into unless it
 * is named; and a link is opened as a link, never typed into an address bar. Plain rules, checked without a phone;
 * PhoneService keeps the state.
 */
object PhoneGuard {
    /**
     * What agents' links tell the browser they come from: Chromium browsers open every link with one such id in the one
     * tab that id already has (Boss, 2026-10-30: "there were 355 tabs open"). All agents share it, and his own links carry none.
     */
    const val AGENT_BROWSER_ID = "dev.omalauncher.agents"

    /** Longer than this is not a link anyone means to open. */
    const val MAX_URL = 2000

    /** The commands that touch the screen: each is held to the app the agent opened, and `--in`/`--any` apply to it. */
    val touching = setOf("press", "fill", "click", "type", "scroll", "submit", "tap", "swipe", "back")

    /**
     * The commands that only look and never change the screen. Every other command marks the next seconds as an
     * agent's, so nothing Soma's chat sends then is signed as Boss's (BossTouch); an unknown command marks too.
     */
    val reading = setOf("status", "screen", "read", "screenshot", "apps", "expect", "find", "plan-receipt", "plan-id", "pages", "board", "stickers", "looks")

    /** The commands that put words into a field, which never go into Soma's own chat box unless it is named. */
    val typing = setOf("fill", "type", "submit")

    /**
     * Why a touch of [command] is refused, or null to let it through. [front] is the app in front now ("" when nothing
     * can be read), [asked] the app named with `--in` ("" for none), [any] whether `--any` lifts the hold to [opened],
     * the app the agent last opened (null for none), [own] Soma's own package, and [chat] whether Soma's chat is what
     * is in front of Soma.
     */
    fun refusal(command: String, front: String, asked: String, any: Boolean, opened: String?, own: String, chat: Boolean): String? {
        if (command !in touching) return null
        if (asked.isNotEmpty() && asked != front) return "Expected $asked in front, but ${front.ifEmpty { "nothing readable" }} is. Nothing was done."
        if (asked.isEmpty() && !any && opened != null && !sameApp(front, opened)) {
            return "You opened $opened, but ${front.ifEmpty { "nothing readable" }} is in front now (he may have switched apps, or a step left it). " +
                "Nothing was done. Run oma-phone screen, then name the app you mean with --in PACKAGE, or add --any."
        }
        if (command in typing && front == own && chat && asked != own) {
            return "That would type into Soma's own chat box, where Boss writes. Nothing was done. " +
                "If that is truly what was asked, name it: --in $own."
        }
        return null
    }

    /**
     * Whether [front] is the app [opened] or one of its own parts: Settings' search is com.android.settings.intelligence
     * (seen on the Fold, 2026-10-21), and is still Settings to the agent that opened it.
     */
    fun sameApp(front: String, opened: String): Boolean = front == opened || front.startsWith("$opened.")

    /** The app an `open` or `url` leaves an agent's touches held to: none when Android asks which app ([CHOOSER]) or found none. */
    fun opened(handler: String?): String? = handler?.takeUnless { it.isBlank() || it == CHOOSER }

    /** The package of Android's own "open with" chooser, which is no app an agent meant. */
    const val CHOOSER = "android"

    /** [url] as a web link to open, or why it isn't one: only http and https, whole, on one line. */
    fun webUrl(url: String): Result<String> {
        val link = url.trim()
        return when {
            link.isEmpty() -> Result.failure(IllegalArgumentException("A URL is required: oma-phone url https://..."))
            link.length > MAX_URL -> Result.failure(IllegalArgumentException("That URL is longer than $MAX_URL characters."))
            link.any { it.isWhitespace() || it.isISOControl() } -> Result.failure(IllegalArgumentException("A URL has no spaces or line breaks in it."))
            !Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE).containsMatchIn(link) ->
                Result.failure(IllegalArgumentException("Only http:// and https:// links are opened this way. Nothing was opened."))
            else -> Result.success(link)
        }
    }
}
