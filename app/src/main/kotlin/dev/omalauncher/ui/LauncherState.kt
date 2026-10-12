package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers

/** App list position (over home): 0 = closed, 1 = open. Follows the finger while dragging. */
class LauncherState {
    val drawer = Animatable(0f)
    var styleOpen by mutableStateOf(false)

    /** The community themes, from the home menu's theme strip or over the style screen. */
    var themeBrowserOpen by mutableStateOf(false)
    var agentsOpen by mutableStateOf(false)
    var storeOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var packagesOpen by mutableStateOf(false)
    var licensesOpen by mutableStateOf(false)

    /** The Linux sheet: install, progress, remove. Opened by anything that needs Linux. */
    var linuxOpen by mutableStateOf(false)

    /** The widget whose share panel is open: a QR code and link, or the store. */
    var sharing by mutableStateOf<dev.omalauncher.widgets.WidgetSpec?>(null)

    /** A widget link that was opened (scanned from a QR code), waiting for the user to read it and add it. */
    var shared by mutableStateOf<String?>(null)

    /** The menu from long-pressing empty home space. */
    var homeMenuOpen by mutableStateOf(false)

    /** The open drawer or a full-screen panel hides the home screen; animated widgets stop ticking under it. */
    val homeCovered: Boolean
        get() = drawer.value > 0.99f || overviewOpen || settingsOpen || styleOpen || themeBrowserOpen || agentsOpen || storeOpen || packagesOpen || linuxOpen || licensesOpen || shared != null || sharing != null

    /** Closes every overlay screen (settings and what it opens). */
    fun closeScreens() {
        styleOpen = false
        themeBrowserOpen = false
        agentsOpen = false
        heldPrompt = null
        storeOpen = false
        packagesOpen = false
        linuxOpen = false
        licensesOpen = false
        settingsOpen = false
        homeMenuOpen = false
        overviewOpen = false
        stopDrawing()
        shared = null
        sharing = null
    }

    /** Key of the home tile in edit mode (handles and action bar shown), if any. */
    var editing by mutableStateOf<String?>(null)

    /** How far the home pages are scrolled, 0 (first) to 1 (last); the background slides along. */
    var wallpaperOffset by mutableFloatStateOf(0.5f)

    /**
     * The dock band's height in px with its gaps above and below, as last laid out over home (0 until then), under
     * the name it had as the prompt bar's. A large system font makes it taller than [PromptBarHeight]; home ends its
     * grid above what it really takes.
     */
    var barHeight by mutableIntStateOf(0)

    /**
     * The id of the home page last touched: the one showing, or of two side by side the one a finger was last on
     * (the left one until then). The drawer pins apps there, and folding the phone shows it.
     */
    var currentPage by mutableStateOf("p1")

    /** What the hinge reports while the window lies across it; null on a closed foldable, and on what doesn't fold. */
    var hinge by mutableStateOf<dev.omalauncher.widgets.layout.Hinge?>(null)

    /** The overview of all pages is over home; see [Overview]. */
    var overviewOpen by mutableStateOf(false)
        private set

    /**
     * Opens the overview of all pages: the page name on home was tapped, or a pinch settled into it. A tile being
     * edited is let go, and so is the pen.
     */
    fun openOverview() {
        editing = null
        homeMenuOpen = false
        stopDrawing()
        overviewOpen = true
    }

    fun closeOverview() {
        overviewOpen = false
    }

    /**
     * One finger draws on the page instead of turning it or lifting a tile; two still pinch (PRD AC-22). On from
     * [startDrawing] until [stopDrawing]: the tool strip's "done", Back, the overview, or leaving home.
     */
    var drawing by mutableStateOf(false)
        private set

    /** The tool in hand, the stroke under the finger and the note or sticker that is selected. */
    val marks = Marks()

    /** One finger draws from now on. A tile or a note being edited is let go, and the menu this came from closes. */
    fun startDrawing() {
        editing = null
        homeMenuOpen = false
        marks.selected = null
        marks.editing = null
        drawing = true
    }

    /**
     * One finger is home's again: it turns pages, opens tiles and lifts them, as it did. A stroke still under
     * the finger is kept, on the page it was drawn on.
     */
    fun stopDrawing() {
        drawing = false
        marks.finish()
    }

    private var typed by mutableStateOf("")

    /** What's in the app list's search field: an app to find, or something to take to the agent. */
    var query: String
        get() = typed
        set(value) {
            if (value != typed) picked = null
            typed = value
        }

    /** What was said to the agent button while no agent was installed, kept while the agents screen is open. */
    var heldPrompt by mutableStateOf<String?>(null)

    /** Closes the agents screen and hands back what was waiting for an agent, once. */
    fun closeAgents(): String? {
        agentsOpen = false
        return heldPrompt.also { heldPrompt = null }
    }

    /** The result chosen with Tab or the arrow keys (-1 = ask the agent); null follows the ranking. Reset by typing. */
    var picked by mutableStateOf<Int?>(null)

    /** Home page to scroll to, by its position from 0 (e.g. from Super+number); consumed by the pager. */
    var requestedPage by mutableStateOf<Int?>(null)

    /** A composition scope: animations need its frame clock. Null until the UI is composed. */
    private var composeScope: CoroutineScope? = null
    private val scope get() = composeScope ?: CoroutineScope(Dispatchers.Main)

    @Composable
    fun bindScope() {
        val s = rememberCoroutineScope()
        SideEffect { composeScope = s }
    }

    /** Animates when composed; before that (e.g. Home pressed during startup) just jumps. */
    private fun animate(
        target: Float,
        stiffness: Float,
    ) = composeScope?.launch { drawer.animateTo(target, spring(stiffness = stiffness)) }
        ?: scope.launch { drawer.snapTo(target) }

    fun open() = animate(1f, Spring.StiffnessMediumLow)

    fun close() = animate(0f, Spring.StiffnessMedium)

    /**
     * Closes without animating. Used when leaving the launcher: animations pause
     * while it's in the background and could leave an invisible drawer catching touches.
     */
    fun closeNow() = scope.launch { drawer.snapTo(0f) }

    fun toggle() = if (drawer.targetValue > 0.5f) close() else open()

    /** Direction of the most recent drag step; a fallback when no fling velocity is reported. */
    private var lastDragUp = false

    fun dragBy(fraction: Float) = scope.launch {
        if (fraction != 0f) lastDragUp = fraction > 0
        drawer.snapTo((drawer.value + fraction).coerceIn(0f, 1f)) }

    /** Settles after a drag; [velocity] is in px/s, negative = upwards. */
    fun settle(velocity: Float) {
        when {
            velocity < -800f -> open()
            velocity > 800f -> close()
            // Compose sometimes reports 0 velocity for the first gesture after resuming.
            velocity == 0f && drawer.value > 0.05f -> if (lastDragUp) open() else close()
            drawer.value > 0.2f -> open()
            else -> close()
        }
    }
}
