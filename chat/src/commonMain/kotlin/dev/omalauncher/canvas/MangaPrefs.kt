package dev.omalauncher.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * The Manga look's one preference (piece D): whether art in a panel is printed in ink (grayscale and screentone, as the
 * mockup's "art in ink") or in its own colors. There is no settings screen: the Layouts module's "Art: Ink / Color" is
 * where he changes it. Compose state, so art drawn with it changes at his tap.
 */
object MangaPrefs {
    /** What he picked; null until he picks. */
    private var chosen by mutableStateOf<Boolean?>(null)

    /** The look's own default (the pack's `art_print`), set by whoever draws the Manga look; null for none. */
    var lookDefault by mutableStateOf<Boolean?>(null)

    private var keep: (Boolean) -> Unit = {}

    /** Whether art is printed in ink: his pick, else the look's, else ink (Boss liked the screentone). */
    var artInk: Boolean
        get() = chosen ?: lookDefault ?: true
        set(ink) {
            chosen = ink
            keep(ink)
        }

    /** What he picked before ([saved], null for nothing yet), and where a new pick is kept. */
    fun open(saved: Boolean?, keep: (Boolean) -> Unit) {
        chosen = saved
        this.keep = keep
    }
}
