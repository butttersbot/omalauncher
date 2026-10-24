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

    /**
     * The app to open a Settings destination in, of those that answer it: Settings itself before any other (Samsung's My
     * Files also answers storage, and Android would ask which; the phone walk 2026-10-24), never Android's chooser.
     */
    fun handler(candidates: List<String>): String? =
        candidates.firstOrNull { it == SETTINGS } ?: candidates.firstOrNull { it != PhoneGuard.CHOOSER && "settings" in it }
            ?: candidates.firstOrNull { it != PhoneGuard.CHOOSER }

    /**
     * Apps a Settings page may hand over to and still be that page: Samsung sends Battery on to Device Care (lool).
     * The destination's words are still checked in them.
     */
    fun handsOverTo(handler: String): Set<String> = if (handler == SETTINGS) setOf("com.samsung.android.lool") else emptySet()

    fun action(name: String): String? {
        require(!name.startsWith("settings:") || name in actions) {
            "Unknown Settings destination. Use settings:display, sound, wifi, bluetooth, battery, storage or apps. Nothing was opened."
        }
        return actions[name]
    }
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
