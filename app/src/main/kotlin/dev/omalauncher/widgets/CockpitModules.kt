package dev.omalauncher.widgets

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.Runtime
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.canvas.cockpitFeeds
import dev.omalauncher.oma.AndroidChatHost
import dev.omalauncher.oma.ChatHost

/** How long the slower feeds (herd, the pocket, handoffs, the registry) rest between reads while modules are on home. */
const val MODULE_FEEDS_EVERY_MS = 30_000L

/**
 * The cockpit modules' one source on the phone (Soma Canvas slice 4): the chat's own host, so a module reads the very
 * threads and approvals the chat shows and answers them through the chat's own guarded paths, and the slower feeds read
 * on a timer. [tick] changes whenever there may be news, which is what makes a drawn module look again.
 */
object CockpitModules {
    @Volatile private var made: Pair<ChatHost, ModuleSource>? = null

    /** Changes when the modules may show something new: home draws them reading it. */
    var tick by mutableLongStateOf(0L)
        private set

    /** When the slower feeds were last read; 0 for never. */
    @Volatile private var fed = 0L

    /** Whether a read of the slower feeds is under way: one at a time, however long a read takes. */
    private val feeding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether a read of the slower feeds is under way now. */
    val feedingNow: Boolean get() = feeding.get()

    /** Whether the slower feeds are due a read at [now]: rested, and none under way. */
    fun due(now: Long): Boolean = !feeding.get() && now - fed >= MODULE_FEEDS_EVERY_MS

    private fun of(context: Context, runtime: Runtime): Pair<ChatHost, ModuleSource> =
        made ?: synchronized(this) {
            made ?: run {
                val app = context.applicationContext
                val host = AndroidChatHost(app, runtime)
                host to chatHostModules(host, cockpitFeeds(), handoffs = { MacThreads.handoffs(app, it) }, registry = { MacThreads.projects(app) })
            }.also { made = it }
        }

    fun source(context: Context, runtime: Runtime): ModuleSource = of(context, runtime).second

    fun host(context: Context, runtime: Runtime): ChatHost = of(context, runtime).first

    /** A look at the live state (threads, approvals, the chat's entries): cheap, read from what the app holds. Main thread. */
    fun look(now: Long) {
        tick = now
    }

    /**
     * Reads the slower feeds once they have rested [MODULE_FEEDS_EVERY_MS], for the [projects] the modules on home show.
     * Blocking, so off the main thread; true when it read. Nothing is read with no module on home.
     */
    fun feed(context: Context, runtime: Runtime, projects: Collection<String>, now: Long, force: Boolean = false): Boolean {
        if (!force && now - fed < MODULE_FEEDS_EVERY_MS) return false
        if (!feeding.compareAndSet(false, true)) return false
        try {
            source(context, runtime).refresh(projects)
        } finally {
            fed = System.currentTimeMillis()
            feeding.set(false)
        }
        return true
    }
}
