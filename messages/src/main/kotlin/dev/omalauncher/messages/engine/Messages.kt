package dev.omalauncher.messages.engine

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The package that does the iMessage work. */
const val OPENBUBBLES = "com.openbubbles.messaging"

/** What a conversation's latest OpenBubbles notification offers: its Reply action, Mark read, and the tap that opens it. */
class Handles(
    val reply: PendingIntent?,
    val inputs: Array<RemoteInput>?,
    val markRead: PendingIntent?,
    val open: PendingIntent?,
)

/** How sending went. */
enum class Sent {
    /** Handed to OpenBubbles through its Reply action. */
    Sent,

    /** No reply action is known for the conversation (nothing has arrived since this app started): open OpenBubbles. */
    NoReplyPath,
}

/**
 * The conversations, the way to answer them, and whether Android lets this app read OpenBubbles' notifications.
 * One per process; the listener feeds it and the screens read it.
 */
object Messages {
    private const val TAG = "SomaMessages"

    private val _book = MutableStateFlow(ConversationBook())
    val book: StateFlow<ConversationBook> = _book

    /** True while Android has the listener bound. */
    val listening = MutableStateFlow(false)

    /** True while example conversations stand in for real ones (for looking at the screens). */
    val samples = MutableStateFlow(false)

    private val handles = ConcurrentHashMap<String, Handles>()
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var real = ConversationBook()

    /** Loads what was kept, once per process. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "conversations.json")
        file = f
        real = if (f.isFile) ConversationBook.fromJson(f.readText()) else ConversationBook()
        if (!samples.value) _book.value = real
    }

    private fun publish(next: ConversationBook) {
        real = next
        if (!samples.value) _book.value = next
        val f = file ?: return
        val text = next.toJson()
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }.onFailure { Log.w(TAG, "saving conversations", it) }
        }
    }

    /** What the listener read from one notification. */
    @Synchronized
    fun seen(
        context: Context,
        conversation: SeenConversation,
        with: Handles,
    ) {
        init(context)
        handles[conversation.id] = with
        publish(real.take(conversation))
    }

    @Synchronized
    fun read(id: String) {
        if (samples.value) {
            _book.value = _book.value.read(id)
            return
        }
        publish(real.read(id))
    }

    /** Answers [id] through OpenBubbles' Reply action. Read receipts are OpenBubbles' business; nothing here marks read. */
    @Synchronized
    fun send(
        context: Context,
        id: String,
        text: String,
    ): Sent {
        if (samples.value) {
            _book.value = _book.value.sent(id, text, System.currentTimeMillis())
            return Sent.Sent
        }
        val h = handles[id]
        val reply = h?.reply
        val inputs = h?.inputs
        if (reply == null || inputs.isNullOrEmpty()) return Sent.NoReplyPath
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            reply.send(context, 0, intent)
            publish(real.sent(id, text, System.currentTimeMillis()))
            Sent.Sent
        } catch (e: PendingIntent.CanceledException) {
            handles.remove(id)
            Sent.NoReplyPath
        }
    }

    /** True when a reply can go from here right now. */
    fun canReply(id: String) = samples.value || handles[id]?.reply != null

    /** Opens [id] in OpenBubbles: its own notification's tap when one is known, else OpenBubbles itself. */
    fun openInOpenBubbles(
        context: Context,
        id: String?,
    ) {
        val open = id?.let { handles[it]?.open }
        if (open != null) {
            try {
                // Android 14 and later start another app's activity from a PendingIntent only when the sender, visible here, says so.
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle() else null
                open.send(context, 0, null, null, null, null, options)
                return
            } catch (e: PendingIntent.CanceledException) {
                handles.remove(id)
            }
        }
        context.packageManager.getLaunchIntentForPackage(OPENBUBBLES)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openBubblesInstalled(context: Context) = context.packageManager.getLaunchIntentForPackage(OPENBUBBLES) != null

    private fun listener(context: Context) = ComponentName(context, OpenBubblesListener::class.java)

    /** Whether the owner has let this app read notifications. */
    fun granted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener(context))

    /** Android's page where the owner turns that on: this app's own switch where Android has one. */
    fun accessSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener(context).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    /** Example conversations in place of real ones, or back to the real ones. */
    @Synchronized
    fun showSamples(on: Boolean) {
        samples.value = on
        _book.value = if (on) Samples.book(System.currentTimeMillis()) else real
    }
}
