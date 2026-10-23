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

    /**
     * Answers [id] through OpenBubbles' Reply action. Read receipts are OpenBubbles' business; nothing here marks read.
     * A [quote] is kept with the message here only: the reply action carries words, not iMessage's threaded reply.
     */
    @Synchronized
    fun send(
        context: Context,
        id: String,
        text: String,
        quote: String? = null,
    ): Sent {
        if (samples.value) {
            _book.value = _book.value.sent(id, text, System.currentTimeMillis(), quote)
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
            publish(real.sent(id, text, System.currentTimeMillis(), quote))
            Sent.Sent
        } catch (e: PendingIntent.CanceledException) {
            handles.remove(id)
            Sent.NoReplyPath
        }
    }

    /**
     * Hands [photos] and [text] to OpenBubbles' own conversation [id], where they wait to be sent: its Reply action
     * carries words only, so photos can't go out from here. Copies are made first, since a picked photo may be read
     * only by this app. Call it off the main thread; it returns the Intent to start, or null without OpenBubbles.
     */
    fun share(
        context: Context,
        id: String,
        photos: List<android.net.Uri>,
        text: String,
        beside: Boolean = false,
    ): Intent? {
        if (!openBubblesInstalled(context)) return null
        // Each hand-off gets its own folder, so a second one can't delete photos OpenBubbles hasn't read yet; folders
        // over a day old go.
        val shared = File(context.cacheDir, "shared")
        shared.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.deleteRecursively() }
        val folder = File(shared, System.currentTimeMillis().toString()).apply { mkdirs() }
        val resolver = context.contentResolver
        val types = mutableListOf<String>()
        val copies =
            photos.mapIndexedNotNull { i, uri ->
                runCatching {
                    val type = resolver.getType(uri) ?: "image/jpeg"
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
                    val copy = File(folder, "photo-${i + 1}.$ext")
                    resolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    types += type
                    androidx.core.content.FileProvider.getUriForFile(context, "dev.omalauncher.messages.photos", copy)
                }.onFailure { Log.w(TAG, "copying a photo", it) }.getOrNull()
            }
        val kinds = types.map { it.substringBefore('/') }.distinct()
        val intent =
            Intent(if (copies.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                .setPackage(OPENBUBBLES)
                .setType(if (kinds.size == 1) "${kinds[0]}/*" else "*/*")
                .putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Unfolded, OpenBubbles opens beside this app in split screen, so the conversation stays in view.
        if (beside) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (copies.size == 1) {
            intent.putExtra(Intent.EXTRA_STREAM, copies[0])
        } else if (copies.isNotEmpty()) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(copies))
        }
        if (copies.isNotEmpty()) {
            intent.clipData = android.content.ClipData.newUri(resolver, "Photos", copies[0]).apply { copies.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        }
        return intent
    }

    /** Notes in [id] what was handed to OpenBubbles to send, so the conversation shows it waiting there. */
    @Synchronized
    fun handedOff(
        id: String,
        texts: List<String>,
        quote: String?,
    ) {
        val now = System.currentTimeMillis()
        var next = if (samples.value) _book.value else real
        texts.forEachIndexed { i, t -> next = next.handedOff(id, t, now + i, if (i == 0) quote else null) }
        if (samples.value) _book.value = next else publish(next)
    }

    /** Takes back a handed-off message the owner didn't send. */
    @Synchronized
    fun drop(
        id: String,
        message: Message,
    ) {
        if (samples.value) _book.value = _book.value.drop(id, message) else publish(real.drop(id, message))
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
