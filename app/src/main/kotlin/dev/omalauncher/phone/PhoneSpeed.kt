package dev.omalauncher.phone

/** Public Settings activities only. Unknown destinations never fall through to a different screen. */
object PhoneSettings {
    private val actions = mapOf(
        "settings" to "android.settings.SETTINGS",
        "settings:display" to "android.settings.DISPLAY_SETTINGS",
        "settings:sound" to "android.settings.SOUND_SETTINGS",
        "settings:wifi" to "android.settings.WIFI_SETTINGS",
        "settings:bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "settings:battery" to "android.settings.BATTERY_SAVER_SETTINGS",
        "settings:storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "settings:apps" to "android.settings.MANAGE_APPLICATIONS_SETTINGS",
    )
    /** Android's own Settings app; Samsung's too. */
    const val SETTINGS = "com.android.settings"

    /** Samsung's My Files, whose "Manage storage" is the phone's storage page there. */
    const val MY_FILES = "com.sec.android.app.myfiles"

    /**
     * The app to open a Settings destination in, of those that answer it, never Android's chooser. Storage on a Samsung
     * phone opens in My Files: its Settings answers storage too but hands it on to My Files through the chooser again,
     * which was still there after "Settings" was picked (the Fold, launcher 153, 2026-10-24). Anything else opens in
     * Settings itself before any other app.
     */
    fun handler(candidates: List<String>, action: String = ""): String? =
        candidates.firstOrNull { it == MY_FILES && action == actions["settings:storage"] }
            ?: candidates.firstOrNull { it == SETTINGS } ?: candidates.firstOrNull { it != PhoneGuard.CHOOSER && "settings" in it }
            ?: candidates.firstOrNull { it != PhoneGuard.CHOOSER }

    /**
     * Apps a Settings page may hand over to and still be that page: Samsung sends Battery on to Device Care (lool).
     * The destination's words are still checked in them.
     */
    fun handsOverTo(handler: String): Set<String> = if (handler == SETTINGS) setOf("com.samsung.android.lool", MY_FILES) else emptySet()

    /**
     * What to pick when Android asks which app should open a Settings destination: Samsung's Settings hands storage on
     * to My Files through Android's "Complete action using", and "My Files", "Just once" is what he'd tap (the Fold,
     * launcher 155, 2026-10-25). Nothing for any other destination: there the chooser is reported, not answered.
     */
    fun chooserAnswer(action: String?): List<String> = if (action != null && action == actions["settings:storage"]) listOf("My Files", "Just once") else emptyList()

    fun action(name: String): String? {
        require(!name.startsWith("settings:") || name in actions) {
            "Unknown Settings destination. Use settings:display, sound, wifi, bluetooth, battery, storage or apps. Nothing was opened."
        }
        return actions[name]
    }
}

/**
 * Stage 3 of docs/PHONE-CONTROL-SPEED-PLAN.md: reads only when they can tell something new. A wait reads the screen
 * again when Android said it changed since the last read, and otherwise no oftener than [FALLBACK_MILLIS], in case a
 * change came without an event. A read asked about a snapshot the reader already has, with nothing changed since, is
 * answered "unchanged" instead of the whole screen again.
 */
object PhoneWait {
    /** The status bar, navigation and shade: the clock and battery there change all the time, and are not what is read. */
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * Whether an accessibility event of [type] from [pkg] says the screen being read may have changed (stage 3,
     * subtree-filtered events): System UI's own redraws (its clock ticking, the battery, a notification icon) do not,
     * so waits don't read again for them; a window of its coming or going (the shade, a dialog) still does.
     */
    fun counts(pkg: String?, type: Int): Boolean =
        pkg != SYSTEM_UI || type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED

    const val FALLBACK_MILLIS = 400L

    fun readAgain(now: Long, lastRead: Long?, lastChange: Long?): Boolean =
        lastRead == null || (lastChange != null && lastChange >= lastRead) || now - lastRead >= FALLBACK_MILLIS

    /** Whether snapshot [asked] still stands: it is the current one, read at [readAt], and nothing changed after. */
    fun unchanged(asked: String, current: String, readAt: Long?, lastChange: Long?): Boolean =
        asked.isNotEmpty() && asked == current && readAt != null && (lastChange == null || lastChange < readAt)
}

/** Per-search, memory-only progress. One fingerprint per direction; no screen contents are logged or saved. */
class PhoneSearchProgress {
    private val forward = mutableSetOf<List<String>>()
    private val backward = mutableSetOf<List<String>>()
    fun repeated(nodes: List<SeenNode>, ahead: Boolean): Boolean {
        // Path ids alone do not identify a page: recycled lists reuse them for different labels.
        val page = nodes.filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}\u0000${it.description}\u0000${it.clickable}\u0000${it.editable}" }
        return page.isNotEmpty() && !(if (ahead) forward else backward).add(page)
    }
}
