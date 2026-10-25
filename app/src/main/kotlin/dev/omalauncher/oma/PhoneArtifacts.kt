package dev.omalauncher.oma

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.builderId
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/**
 * The phone's own agents' artifacts (features/artifact-panel/PLAN.md, slice 3): what its builders make with the guest's
 * `soma-artifact`, read from the store in the guest ([PhoneArtifactStore]) and told to the chat as the Mac's artifact
 * events are: a step under the builder's entry ("made x · v1") and the live state the panel follows. Read when a builder's
 * tool call ends and while the chat is open (its tick); where each thread's events were read to is kept, so a restart
 * neither repeats nor misses any.
 */
object PhoneArtifacts {
    private const val PREFS = "phone-artifacts"
    private val book = ArtifactLiveBook()
    private val offsets = HashMap<String, Long>()
    private val sizes = HashMap<String, Long>()
    private var loaded = false
    private var seq = 0L

    /** The guest's `soma-artifact`, installed with the other guest tools. */
    fun guestTools(context: Context, rootfs: File): List<Managed> =
        listOf(Managed(File(rootfs, "usr/local/bin/soma-artifact"), Policy.Owned, executable = true) { context.assets.open("soma-artifact.sh").use { it.readBytes() } })

    /** What a builder is told, with its other rules, so asking it for a mockup puts one beside the chat. */
    const val INSTRUCTIONS = "To show Boss a mockup, page, chart, diagram or anything visual, write ONE self-contained file (HTML with inline " +
        "CSS and JS: it is shown offline, so no CDN scripts, web fonts or network calls) and run `soma-artifact put FILE --title \"Short title\" " +
        "--note \"what this version is\"`: it opens beside the chat in Soma. Every change is a new put of the same file name. Before you start " +
        "changing one, run `soma-artifact editing NAME`. `soma-artifact get NAME --version N` prints an older version."

    private fun store(context: Context) = PhoneArtifactStore(File((context.applicationContext as App).runtime.proot.home, ".local/share/soma/artifacts"))

    private fun threads() = BuilderId.entries.map { it.thread }

    private fun eventsFile(context: Context, thread: String) = File((context.applicationContext as App).runtime.proot.home, ".local/share/soma/artifacts/$thread/events.jsonl")

    /** Where each thread was read to, from before. A thread never read before starts at the end of what it has: nothing old is told again. */
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (thread in threads()) {
            offsets[thread] = if (prefs.contains(thread)) prefs.getLong(thread, 0L) else eventsFile(context, thread).let { if (it.isFile) it.length() else 0L }
        }
    }

    /** The entry an event of [thread] belongs to: [taskTime] when a builder's run says so, else that builder's running entry or its newest. */
    private fun entryOf(context: Context, thread: String, taskTime: Long?): Long? {
        val tasks = ChatLog.of(context).tasks
        if (taskTime != null && tasks.any { it.time == taskTime }) return taskTime
        val builder = BuilderId.entries.firstOrNull { it.thread == thread } ?: return null
        val own = tasks.filter { it.build && it.builderId == builder && it.chat.isEmpty() }
        return (own.lastOrNull { it.status == TaskStatus.Running } ?: own.lastOrNull())?.time
    }

    private fun step(context: Context, thread: String, taskTime: Long?, name: String, version: Int, state: String, settled: Boolean) =
        steps(context, thread, taskTime, listOf(Triple(name, version, state to settled)))

    /** Several artifact steps onto one entry, written once (a catch-up of many events is one save, not one each). */
    private fun steps(context: Context, thread: String, taskTime: Long?, changes: List<Triple<String, Int, Pair<String, Boolean>>>) {
        if (changes.isEmpty()) return
        val time = entryOf(context, thread, taskTime) ?: return
        ChatLog.of(context).update(time, save = true) { task ->
            task.copy(steps = changes.fold(task.steps) { steps, (name, version, how) -> withArtifactStep(steps, name, version, how.first, how.second) }.takeLast(ChatHistory.MAX_STEPS))
        }
    }

    /** New events of [builder]'s thread (every builder's when null) into the chat. [agent] says who ("Claude") when known. */
    @Synchronized fun poll(context: Context, builder: BuilderId? = null, taskTime: Long? = null, agent: String = "") {
        load(context)
        val store = store(context)
        var moved = false
        for (thread in builder?.let { listOf(it.thread) } ?: threads()) {
            // Most ticks nothing changed: only the file's length is looked at (a part line waits for its length to change).
            val file = eventsFile(context, thread)
            val length = if (file.isFile) file.length() else 0L
            if (taskTime == null && sizes[thread] == length) continue
            sizes[thread] = length
            val (events, next) = store.events(thread, offsets[thread] ?: 0L)
            val changes = ArrayList<Triple<String, Int, Pair<String, Boolean>>>()
            val versions = HashMap<String, Int>()
            for (event in events) {
                // A version no file stands for (a line written by hand) is not taken: the card would name one that isn't there.
                val latest = versions.getOrPut(event.name) { store.versions(thread, event.name)?.latest ?: 0 }
                if (event.state == "ready" && event.version > latest) continue
                book.heard(thread, event.name, event.version, event.state, false, event.title, event.note, agent.ifBlank { "Claude" }, "this phone", event.time, ++seq)
                changes += Triple(event.name, event.version, event.state to false)
            }
            steps(context, thread, taskTime, changes)
            if (next != offsets[thread]) { offsets[thread] = next; moved = true }
        }
        if (moved) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { offsets.forEach { (key, value) -> putLong(key, value) } }.apply()
    }

    /**
     * A builder is about to work on a file that is one of its artifacts (a Write or Edit of it, or a command naming it):
     * the panel shows it as being edited at once, as `soma-artifact editing` would. The next put, or the run's end, ends it.
     */
    @Synchronized fun touched(context: Context, builder: BuilderId, taskTime: Long, tool: String, filePath: String, command: String, agent: String = "") {
        val thread = builder.thread
        val store = store(context)
        val name = runCatching { store.touched(thread, tool, filePath, command) }.getOrNull() ?: return
        val latest = store.versions(thread, name)?.latest?.takeIf { it > 0 } ?: return
        if (book.snapshot().any { it.thread == thread && it.name == name && it.editing != null }) return
        book.heard(thread, name, latest + 1, "editing", false, "", "", agent.ifBlank { "Claude" }, "this phone", System.currentTimeMillis() / 1000, ++seq)
        step(context, thread, taskTime, name, latest + 1, "editing", false)
    }

    /** A builder's run ended: what it put is read, and an edit no version followed is over (the strip goes, the line too). */
    @Synchronized fun settle(context: Context, builder: BuilderId, taskTime: Long) {
        poll(context, builder, taskTime)
        val thread = builder.thread
        for (open in book.snapshot().filter { it.thread == thread && it.editing != null }) {
            book.heard(thread, open.name, open.version, "ready", true, "", "", "", "", System.currentTimeMillis() / 1000, ++seq)
            step(context, thread, taskTime, open.name, open.version, "ready", true)
        }
    }

    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "phone-artifacts").apply { isDaemon = true } }
    private val reading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** How long an edit no version followed keeps showing when no builder run will end it. */
    private const val EDITING_MOST_S = 10 * 60L

    /**
     * The live state the panel follows. Asked on the chat's tick, on the main thread: what is new is read on a thread of
     * its own (one at a time), and the tick after shows it.
     */
    fun live(context: Context): List<ArtifactLive> {
        if (reading.compareAndSet(false, true)) reader.execute {
            try {
                runCatching { poll(context) }
                expire()
            } finally { reading.set(false) }
        }
        return book.snapshot()
    }

    /** An edit left showing long after it began, with no builder working that would end it, stops showing. */
    @Synchronized private fun expire() {
        val now = System.currentTimeMillis() / 1000
        val working = dev.omalauncher.assist.AgentTasks.builders.filter { it.working }.map { it.id.thread }.toSet()
        for (open in book.snapshot().filter { it.editing != null && it.thread !in working && now - it.time > EDITING_MOST_S })
            book.heard(open.thread, open.name, open.version, "ready", true, "", "", "", "", now, ++seq)
    }

    fun read(context: Context, thread: String, path: String): ArtifactRead =
        if (!phoneArtifactThread(thread)) ArtifactRead.Failed("Files from the phone's agents open here when they show them with soma-artifact put.")
        else store(context).read(thread, path)

    fun versions(context: Context, thread: String, name: String): ArtifactVersions? =
        if (phoneArtifactThread(thread)) store(context).versions(thread, name) else null
}
