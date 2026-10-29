package dev.omalauncher.desktop

import dev.omalauncher.assist.MacLink
import dev.omalauncher.assist.MacMachine
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.ThreadsPlatform
import dev.omalauncher.oma.ChatStore
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.tabMetaFrom
import dev.omalauncher.oma.tabMetaText
import org.json.JSONObject
import javax.swing.SwingUtilities

/** The desktop's part of following the threads (ThreadsPlatform): files under ~/.config/soma, the tray's notices. */
class DesktopThreadsPlatform(
    private val notices: Notices,
    /** Where its files are: Soma's own folder, or a test's second Soma's (two Somas sharing one runner's archive). */
    private val home: () -> java.io.File = { SomaHome.dir },
    /** The runners set up: the Keychain's as last read off the window's thread ([RunnerKeys.known]), or a test's own. */
    private val runners: () -> List<MacMachine> = { RunnerKeys.known },
) : ThreadsPlatform {
    private val bookFile get() = java.io.File(home(), SomaHome.book.name)
    private val notesFile get() = java.io.File(home(), SomaHome.notes.name)
    private val log = ChatStore(java.io.File(home(), SomaHome.chat.name)).also { it.load() }
    @Volatile private var meta: TabMeta = tabMetaFrom(runCatching { bookFile.readText() }.getOrNull())
    private val notes: JSONObject = SomaHome.read(notesFile)

    override fun machines(): List<MacMachine> = runners()

    override fun chatLog(): ChatStore = log

    override fun book(): TabMeta = meta

    @Synchronized override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta {
        val next = change(meta)
        if (next != meta) {
            meta = next
            bookFile.writeText(tabMetaText(next))
        }
        return next
    }

    @Synchronized override fun prefString(key: String): String? = notes.optString(key).takeIf { notes.has(key) }

    @Synchronized override fun prefLong(key: String): Long = notes.optLong(key, 0L)

    @Synchronized override fun putPref(key: String, value: String) { notes.put(key, value); SomaHome.write(notesFile, notes) }

    @Synchronized override fun putPref(key: String, value: Long) { notes.put(key, value); SomaHome.write(notesFile, notes) }

    override val stampLock: Any = Any()

    override fun clearStaleCards() {}

    override fun requestCard(entry: MacPending) = notices.say("${entry.threadName} asks", "Allow ${entry.request.tool}? Open Soma to answer.")

    override fun withdrawCard(entry: MacPending) {}

    override fun answeredNotice(label: String, answer: String, front: Boolean) = notices.say(label, answer)

    override fun locked(): Boolean = false

    override fun linkNote(now: Long): String? = null

    override fun linkAfter(link: MacLink) {}

    override fun statusChanged() {}

    override fun threadsChanged() {}

    override fun routingCorrected(request: String, was: String) {}

    override fun post(block: () -> Unit) = SwingUtilities.invokeLater(block)
}
