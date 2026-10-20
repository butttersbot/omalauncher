package dev.omalauncher.art

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.omalauncher.phone.PhoneService

/**
 * Puts imported art into a manga panel (features/manga-look/CONTRACT.md, piece C). [page] and [panel] say where it was
 * asked for (a tap on that empty panel); both null when it came from the share sheet. Called on the main thread.
 */
interface ArtPlacer {
    fun place(
        artId: String,
        page: String?,
        panel: Int?,
    )
}

/** A manga page as the default placer sees it: its id, its name for what is said, and for each panel whether it is empty. */
data class PanelPage(
    val id: String,
    val name: String,
    val empty: List<Boolean>,
)

/** Home's panel pages, for [DefaultArtPlacer]. Home's panel mode (piece B) can give this instead of a placer of its own. */
interface PanelHome {
    /** The panel pages in page order; pages without panels are left out. */
    fun panelPages(): List<PanelPage>

    /** Hangs art [artId] in [panel] of [page]; false if it couldn't. */
    fun put(
        artId: String,
        page: String,
        panel: Int,
    ): Boolean
}

/**
 * Until home places art itself: the panel asked for if it is empty, else the first empty panel (the asked-for page
 * first, then the panel pages in order), else nowhere, and it says so. The file stays until a sweep finds it unused.
 */
class DefaultArtPlacer(
    private val home: () -> PanelHome?,
    private val say: (String) -> Unit,
) : ArtPlacer {
    override fun place(
        artId: String,
        page: String?,
        panel: Int?,
    ) {
        val h = home()
        val pages = h?.panelPages().orEmpty()
        if (h == null || pages.isEmpty()) {
            say("There's no manga page to hang it on yet.")
            return
        }
        val spot = spot(pages, page, panel)
        if (spot == null) {
            say("Every panel is taken. Empty one first.")
            return
        }
        val (to, index) = spot
        if (!h.put(artId, to.id, index)) {
            say("It couldn't be hung.")
            return
        }
        if (to.id != page || index != panel) say("Hung on ${to.name}.")
    }

    companion object {
        /** Where art asked for at ([page], [panel]) goes: that panel if empty, else the first empty one, asked-for page first. */
        fun spot(
            pages: List<PanelPage>,
            page: String?,
            panel: Int?,
        ): Pair<PanelPage, Int>? {
            val asked = pages.firstOrNull { it.id == page }
            if (asked != null && panel != null && asked.empty.getOrNull(panel) == true) return asked to panel
            val order = listOfNotNull(asked) + pages.filter { it !== asked }
            for (p in order) {
                val i = p.empty.indexOf(true)
                if (i >= 0) return p to i
            }
            return null
        }
    }
}

/** Who places art now. Home's panel mode sets [current] (or gives [home] to the default). */
object ArtPlacers {
    /** Home's panel pages for the default placer; null until home has panel pages to offer. */
    @Volatile var home: PanelHome? = null

    @Volatile private var app: Context? = null

    @Volatile var current: ArtPlacer = DefaultArtPlacer({ home }) { text -> app?.let { say(it, text) } }

    /** Places [artId] through [current], from one of the art activities; [context] lets the default speak. */
    fun placeFrom(
        context: Context,
        artId: String,
        page: String?,
        panel: Int?,
    ) {
        app = context.applicationContext
        current.place(artId, page, panel)
    }

    /** Says [text] in the look's card, or as a toast without one. Any thread. */
    fun say(
        context: Context,
        text: String,
    ) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            if (PhoneService.instance?.showToast("Art", text) != true) Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }
}
