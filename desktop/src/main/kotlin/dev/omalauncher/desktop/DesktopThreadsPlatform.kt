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
    /** The runners set up: the Keychain's, or a test's own. */
    private val runners: () -> List<MacMachine> = RunnerKeys::machines,
) : ThreadsPlatform {
    private val log = ChatStore(SomaHome.chat).also { it.load() }
    @Volatile private var meta: TabMeta = tabMetaFrom(runCatching { SomaHome.book.readText() }.getOrNull())
    private val notes: JSONObject = SomaHome.read(SomaHome.notes)

    override fun machines(): List<MacMachine> = runners()

    override fun chatLog(): ChatStore = log

    override fun book(): TabMeta = meta

    @Synchronized override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta {
        val next = change(meta)
        if (next != meta) {
            meta = next
            SomaHome.book.writeText(tabMetaText(next))
        }
        return next
    }

    @Synchronized override fun prefString(key: String): String? = notes.optString(key).takeIf { notes.has(key) }

    @Synchronized override fun prefLong(key: String): Long = notes.optLong(key, 0L)

    @Synchronized override fun putPref(key: String, value: String) { notes.put(key, value); SomaHome.write(SomaHome.notes, notes) }

    @Synchronized override fun putPref(key: String, value: Long) { notes.put(key, value); SomaHome.write(SomaHome.notes, notes) }

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
