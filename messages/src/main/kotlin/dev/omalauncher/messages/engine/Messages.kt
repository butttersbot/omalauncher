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

    /** The Mac the conversations come from, once linked; null while they come through OpenBubbles. */
    val mac = MutableStateFlow<MacAddress?>(null)

    /** True once linked to the Mac: conversations, replies and photos go through it, OpenBubbles isn't used. */
    val linked get() = mac.value != null

    /** A conversation to open, asked for by a tapped notification. */
    val toOpen = MutableStateFlow<String?>(null)

    /** True while example conversations stand in for real ones (for looking at the screens). */
    val samples = MutableStateFlow(false)

    private val handles = ConcurrentHashMap<String, Handles>()
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var app: Context? = null
    private var real = ConversationBook()
    private data class ReadIntent(val before: Int, val target: Int, var arrivals: Int = 0)
    private val readIntents = mutableMapOf<String, ReadIntent>()
    fun changingRead(id: String) = synchronized(this) { id in readIntents }

    @Synchronized fun setUnread(id: String, unread: Boolean) {
        if (id in readIntents) return
        val book = if (samples.value) _book.value else real
        val c = book[id] ?: return
        val target = if (unread) maxOf(1, c.unread) else 0
        if (samples.value || !linked) {
            val next = book.unreadCount(id, target)
            if (samples.value) _book.value = next else publish(next)
            return
        }
        clearNotice(id)
        val intent = ReadIntent(c.unread, target)
        readIntents[id] = intent
        publish(real.unreadCount(id, target))
        app?.let { Alerts.clear(it, id) }
        MacLink.setRead(id, !unread) { failure -> finishRead(id, intent, failure) }
    }

    @Synchronized private fun finishRead(id: String, intent: ReadIntent, failure: String?) {
        if (readIntents[id] !== intent) return // Opening the chat superseded the action.
        readIntents.remove(id)
        publish(real.unreadCount(id, (if (failure == null) intent.target else intent.before) + intent.arrivals))
        if (failure != null) warn(id, "Couldn't mark ${real[id]?.title ?: "conversation"} ${if (intent.target == 0) "read" else "unread"}: $failure")
    }

    /** Loads what was kept, once per process. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        app = context.applicationContext
        ChatChoices.init(context)
        val f = File(context.filesDir, "conversations.json")
        file = f
        real = if (f.isFile) ConversationBook.fromJson(f.readText()) else ConversationBook()
        if (!samples.value) _book.value = real
        val saved = MacLink.file(context)
        if (saved.isFile) MacAddress.fromJson(saved.readText())?.let { at ->
            mac.value = at
            // What OpenBubbles' notifications added after linking goes: the Mac has those conversations itself.
            val kept = real.fromMacOnly()
            if (kept !== real) publish(kept)
            MacLink.start(context, at)
        }
    }

    /**
     * Links to the Mac at [at]: kept in the app's own files, and from then on the conversations come from there. What
     * came through OpenBubbles is left behind, since the Mac has all of it under its own ids.
     */
    @Synchronized
    fun link(
        context: Context,
        at: MacAddress,
    ) {
        init(context)
        val f = MacLink.file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(at.toJson())
        tmp.renameTo(f)
        if (mac.value == null) {
            handles.clear()
            publish(ConversationBook())
        }
        mac.value = at
        MacLink.start(context, at)
    }

    /** The conversation the owner picked and has open (the inbox sets it); null on the inbox itself. */
    @Volatile
    var picked: String? = null

    /** A message arriving in the conversation he picked and is looking at is read as it comes: no dot for what he saw. */
    private fun readIfLooking(id: String) {
        if (Alerts.front && Alerts.looking == id && picked == id && (real[id]?.unread ?: 0) > 0) read(id)
    }

    /** What the Mac says about conversation [id] (see [ConversationBook.fromMac]). */
    @Synchronized
    fun fromMac(
        id: String,
        title: String?,
        isGroup: Boolean?,
        incoming: List<Message>,
        unread: Int? = null,
        counting: Boolean = true,
        notify: Boolean = false,
    ) {
        val before = real[id]?.messages?.size ?: 0
        val known = real[id]?.messages.orEmpty().mapNotNull { it.id }.toSet()
        var next = real.fromMac(id, title, isGroup, incoming, unread, counting)
        readIntents[id]?.let { intent ->
            if (counting) intent.arrivals += incoming.filter { !it.fromMe && it.id !in known }.map { it.id }.distinct().size
            next = next.unreadCount(id, intent.target + intent.arrivals)
        }
        publish(next)
        readIfLooking(id)
        // A new message from someone else, live: its conversation's notification, unless it is open on screen.
        val c = real[id] ?: return
        if (notify && c.messages.size > before && incoming.any { !it.fromMe }) app?.let { Alerts.show(it, c) }
    }

    /** The Mac took back messages [ids] in [id]. */
    @Synchronized
    fun removed(
        id: String,
        ids: Collection<String>,
    ) = publish(real.removed(id, ids))

    /**
     * The message each conversation's waiting quote is of, by the Mac's id, so a reply through the Mac is a real
     * threaded reply. Set with the quote; used and forgotten by the next send.
     */
    val quoting = ConcurrentHashMap<String, String>()

    private fun markEntrance(id: String, clientId: String) {
        if (Alerts.front && Alerts.looking == id && picked == id) SendMotion.mark(clientId)
    }

    @Synchronized
    private fun accepted(id: String, clientId: String) = publish(real.accepted(id, clientId))

    private fun publish(next: ConversationBook) {
        real = next
        if (!samples.value) _book.value = next
        val f = file ?: return
        io.execute {
            val text = next.toJson()
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
        // Linked to the Mac, it has every conversation: OpenBubbles' notifications would only add a second copy of each
        // (Boss, 2026-10-23: "im seeing duplicates of the same messages").
        if (linked) return
        handles[conversation.id] = with
        publish(real.take(conversation))
        readIfLooking(conversation.id)
    }

    @Synchronized
    fun read(id: String) {
        readIntents.remove(id)
        if (samples.value) {
            _book.value = _book.value.read(id)
            return
        }
        publish(real.read(id))
        if (linked) MacLink.opened(id)
        app?.let { Alerts.clear(it, id) }
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
        hiddenLinks: Set<String> = emptySet(),
    ): Sent {
        if (samples.value) {
            _book.value = _book.value.sent(id, text, System.currentTimeMillis(), quote)
            return Sent.Sent
        }
        if (linked) {
            // A new try: the last one's error is old news.
            clearNotice(id)
            val clientId = java.util.UUID.randomUUID().toString()
            markEntrance(id, clientId)
            publish(real.waiting(id, clientId, text, System.currentTimeMillis(), quote, Parts.links(text).mapNotNull { MacLink.previews.value[Parts.openable(it)]?.takeUnless { r -> r.url in hiddenLinks } }, hiddenLinks))
            val replyTo = quoting.remove(id)?.takeIf { quote != null }?.removeSuffix("#a")
            MacLink.send(id, text, replyTo, clientId, onAccepted = { accepted(id, clientId) }) { why ->
                failed(id, clientId)
                warn(id, "Not sent: $why")
            }
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
        // Not the Mac's attachments, kept there under "mac" (a video's file and its frame went with them).
        shared.listFiles()?.filter { it.name != "mac" && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.deleteRecursively() }
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
        if (beside) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /**
     * Sends [photos], then [text], through the Mac. Each photo's [summaries] entry ("1 Photo", "1 Video") stands for it
     * until the Mac shows it back: the Mac sends each photo as a message of its own.
     */
    @Synchronized
    fun sendPhotos(
        context: Context,
        id: String,
        photos: List<android.net.Uri>,
        text: String,
        summaries: List<String>,
        quote: String?,
    ) {
        val now = System.currentTimeMillis()
        quoting.remove(id)
        clearNotice(id)
        // Each photo, and the words, go on their own: one the Mac can't take doesn't stop the rest. Until the Mac shows a
        // photo back, the picked one stands for it.
        photos.forEachIndexed { i, uri ->
            val clientId = java.util.UUID.randomUUID().toString()
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            markEntrance(id, clientId)
            val waiting = real.waiting(id, clientId, summaries.getOrElse(i) { "1 Photo" }, now + i, if (i == 0) quote else null)
            publish(waiting.withFiles(id, "c:$clientId", listOf("local:$uri|$type")))
            MacLink.sendFile(context, id, uri, clientId, onAccepted = { accepted(id, clientId) }) { why ->
                failed(id, clientId)
                warn(id, "Photo not sent: $why")
            }
        }
        if (text.isNotBlank()) {
            val clientId = java.util.UUID.randomUUID().toString()
            markEntrance(id, clientId)
            publish(real.waiting(id, clientId, text, now + photos.size))
            MacLink.send(id, text, null, clientId, onAccepted = { accepted(id, clientId) }) { why ->
                failed(id, clientId)
                warn(id, "Not sent: $why")
            }
        }
    }

    /**
     * A voice message recorded here ([ogg], the recording) to [id]: rewrapped as an iPhone's (Opus in CAF) and sent
     * through the Mac. Until the Mac shows it back, the recording stands for it and plays from here. False when it
     * couldn't be rewrapped, and nothing went.
     */
    fun sendVoice(
        context: Context,
        id: String,
        ogg: java.io.File,
        quote: String?,
    ): Boolean {
        val dir = java.io.File(context.filesDir, "voice").apply { mkdirs() }
        val clientId = java.util.UUID.randomUUID().toString()
        val kept = java.io.File(dir, "$clientId.ogg")
        if (!ogg.renameTo(kept)) runCatching { ogg.copyTo(kept, overwrite = true); ogg.delete() }
        val caf = CafOpus.fromOgg(kept.readBytes())?.let { bytes -> java.io.File(dir, "$clientId.caf").also { it.writeBytes(bytes) } }
        if (caf == null) {
            kept.delete()
            warn(id, "That voice message couldn't be made ready to send.")
            return false
        }
        quoting.remove(id)
        clearNotice(id)
        sendVoiceFile(id, clientId, kept, caf, quote)
        return true
    }

    private fun sendVoiceFile(
        id: String,
        clientId: String,
        ogg: java.io.File,
        caf: java.io.File,
        quote: String?,
    ) {
        synchronized(this) {
            markEntrance(id, clientId)
            val waiting = real.waiting(id, clientId, VOICE, System.currentTimeMillis(), quote)
            publish(waiting.withFiles(id, "c:$clientId", listOf("local:${ogg.path}|$VOICE_MIME")))
        }
        MacLink.sendVoice(id, caf, clientId, onAccepted = { accepted(id, clientId) }) { why ->
            failed(id, clientId)
            warn(id, "Voice message not sent: $why")
        }
    }

    /** How a voice message is summed up, as the Mac does: Parts knows it as an attachment. */
    const val VOICE = "1 Voice message"

    /** Apple's voice messages, and ours once rewrapped. */
    const val VOICE_MIME = "audio/x-caf"

    @Synchronized
    private fun failed(
        id: String,
        clientId: String,
    ) = publish(real.failed(id, clientId))

    /**
     * What went wrong last, said once on screen in the conversation it happened in ([noticeFor]), until the next try
     * there, a few seconds pass, or he leaves it (Boss, 2026-10-23: errors stayed on, under later messages).
     */
    val notice = MutableStateFlow("")

    @Volatile
    var noticeFor: String? = null
        private set

    fun warn(
        id: String,
        text: String,
    ) {
        noticeFor = id
        notice.value = text
    }

    /** Takes the notice away; with [id], only one said in that conversation. */
    fun clearNotice(id: String? = null) {
        if (id == null || noticeFor == id) {
            notice.value = ""
            noticeFor = null
        }
    }

    /**
     * The owner's tapback [kind] on [message] in [id], through the Mac: shown at once, and off again when it was
     * already his. The Mac's own copy of the message, with the tapback, replaces it when it comes.
     */
    @Synchronized
    fun tapback(
        id: String,
        message: Message,
        kind: String,
    ) {
        val macId = message.id?.takeIf { linked && !it.startsWith("c:") }?.removeSuffix("#a") ?: return
        val mine = "me:$kind"
        val remove = mine in message.tapbacks
        val changed = message.copy(tapbacks = message.tapbacks.filterNot { it.startsWith("me:") } + listOfNotNull(mine.takeIf { !remove }),
            reactions = message.reactions.filterNot { it.fromMe } + if (remove) emptyList() else listOf(Reaction(kind, "", true, "You")))
        publish(real.fromMac(id, null, null, listOf(changed), counting = false))
        MacLink.tapback(id, macId, kind, remove) { why -> warn(id, "Tapback not sent: $why") }
    }

    private val loadingEarlier = ConcurrentHashMap.newKeySet<String>()
    private val allLoaded = ConcurrentHashMap.newKeySet<String>()

    /** The page of [id]'s history from the Mac before its oldest message here; nothing once it has all of it. */
    fun loadEarlier(id: String) {
        if (!linked || samples.value || id in allLoaded || !loadingEarlier.add(id)) return
        val oldest = real[id]?.messages?.firstOrNull { it.id != null && !it.id.startsWith("c:") }?.time
        if (oldest == null) {
            loadingEarlier.remove(id)
            return
        }
        MacLink.earlier(id, oldest) { more ->
            if (!more) allLoaded.add(id)
            loadingEarlier.remove(id)
        }
    }

    /** Tries a failed [message] again: its words, or the photo picked here. */
    @Synchronized
    fun retry(
        context: Context,
        id: String,
        message: Message,
    ) {
        publish(real.without(id, message))
        val local = message.files.firstOrNull { it.startsWith("local:") }
        // A voice message recorded here: the same recording, rewrapped again.
        if (local != null && local.endsWith("|$VOICE_MIME")) {
            val ogg = java.io.File(local.removePrefix("local:").substringBeforeLast('|'))
            val caf = java.io.File(ogg.path.removeSuffix(".ogg") + ".caf")
            if (ogg.isFile && caf.isFile) {
                val clientId = java.util.UUID.randomUUID().toString()
                val o = java.io.File(ogg.parentFile, "$clientId.ogg").also { ogg.renameTo(it) }
                val c = java.io.File(ogg.parentFile, "$clientId.caf").also { caf.renameTo(it) }
                sendVoiceFile(id, clientId, o, c, message.quote)
            }
            return
        }
        if (local != null) {
            val uri = android.net.Uri.parse(local.removePrefix("local:").substringBeforeLast('|'))
            sendPhotos(context, id, listOf(uri), "", listOf(message.text), message.quote)
            return
        }
        send(context, id, message.text, message.quote)
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
    fun canReply(id: String) = samples.value || linked || handles[id]?.reply != null

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
