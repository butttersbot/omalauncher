package dev.omalauncher.oma

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import dev.omalauncher.App
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.QuickLane
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.applySystemBars

/**
 * The Oma app: one thread of requests to Oma, with a field for typed messages and image attachments. There is
 * one Oma to talk to and no lane to pick (Boss, 2026-10-13): [QuickLane], the front agent, hears a request
 * first and answers it or hands it to the background agent or to a project thread on the Mac ([MacThreads]),
 * and the thread shows which only by what comes back and, for the Mac, by a receipt naming the project. Send
 * never waits for either of them: what he sends is in the thread at once, and an entry that waits says where. Opened by [ACTION] from the dock, Super+A, the agent's notifications and the tiles; text passed as
 * `Intent.EXTRA_TEXT` is put in the field and not sent. It never attaches the screen: only the assistant
 * gesture over another app does (see ScreenContext.kt).
 *
 * That stream is the Oma tab. Each thread, the phone's builder and every project thread on the Mac, has a
 * tab of its own beside it (Boss, 2026-10-13; ChatTabs.kt): the thread's entries alone, and a field that
 * talks straight to it. A home tile of a thread opens its tab by [EXTRA_THREAD]; every other way in opens Oma.
 * ＋ at the end of the tabs starts a new chat, which is always for the Mac: what its first message hands on goes
 * to a new thread there, and the chat is named after it;
 * a long press on a tab renames, pins, stops, restarts or cleans up after, copies from or archives it (ChatTabs.kt, TabBook.kt).
 */
class OmaChatActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** Text another screen asked to put in the field; taken once. */
    private var prefill by mutableStateOf<String?>(null)

    /** The key of the thread whose tab was asked for, by a tile or a tap in here; null is the Oma tab. See [openTab]. */
    private var wanted by mutableStateOf<String?>(null)
    private var wantedQuestion by mutableStateOf<String?>(null)

    // Whose finger sent what he sends here: only his own touch lets Soma sign it as his (BossReceipt).
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        noteTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.start()
        AgentTasks.restore(this)
        QuickLane.restore(this)
        // Asked on a thread of its own: the chat never waits for the Mac, and shows what was last known until it answers.
        MacThreads.open(this)
        if (savedInstanceState == null) prefill = textOf(intent)
        wanted = if (savedInstanceState == null) opened(intent) else savedInstanceState.getString(STATE_TAB)
        wantedQuestion = if (savedInstanceState == null) intent.getStringExtra(EXTRA_QUESTION) else savedInstanceState.getString(STATE_QUESTION)
        applySystemBars(this, Pack.isLight)
        val host = AndroidChatHost(this, runtime)
        setContent {
            LaunchedEffect(Pack.isLight) { applySystemBars(this@OmaChatActivity, Pack.isLight) }
            // The update pill before [ artifacts · N ] (cards soma-chat/Updates), as on the desktop.
            androidx.compose.runtime.CompositionLocalProvider(LocalTopBarExtra provides { dev.omalauncher.updates.PhoneUpdatePill(runtime.updates.model) }) {
                OmaChatScreen(host, prefill, onPrefilled = { prefill = null }, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } }, wantedQuestion = wantedQuestion, onQuestionOpened = { wantedQuestion = null; intent.removeExtra(EXTRA_QUESTION) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the intent the chat was opened by, as one that started it is.
        setIntent(intent)
        textOf(intent)?.let { prefill = it }
        // A tile, or a thread named from outside while the chat is open, opens that thread's tab as a start would.
        wanted = opened(intent)
        wantedQuestion = intent.getStringExtra(EXTRA_QUESTION)
    }

    /**
     * The tab [intent] opens: the thread it names, else the one the last turn was in ([lastTurnTab]), so his next words
     * go on where the work is (Boss, 2026-10-20), else Soma.
     */
    private fun opened(intent: Intent?): String? =
        askedTab(threadOf(intent), TabBook.of(this)) ?: lastTurnTab(ChatLog.of(this).tasks, TabBook.of(this))

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, wanted)
        outState.putString(STATE_QUESTION, wantedQuestion)
    }

    private fun threadOf(intent: Intent?) = intent?.getStringExtra(EXTRA_THREAD)

    private fun textOf(intent: Intent?) = intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(ChatHistory.MAX_PROMPT)?.takeIf { it.isNotBlank() }

    companion object {
        const val ACTION = "dev.omalauncher.action.OMA_CHAT"

        /** The key of the thread whose tab to open ([AgentThread.key]); a key that is no thread's opens Oma. */
        const val EXTRA_QUESTION = "oma_question_nonce"
        const val EXTRA_THREAD = dev.omalauncher.ui.OMA_CHAT_THREAD
        private const val STATE_TAB = "tab"
        private const val STATE_QUESTION = "question"

        fun intent(context: android.content.Context): Intent = Intent(context, OmaChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
