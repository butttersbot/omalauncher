package dev.omalauncher.messages.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.omalauncher.messages.MainActivity

/**
 * Soma Messages' own notifications, once linked to the Mac (Boss, 2026-10-23: so OpenBubbles isn't needed for them).
 * One per conversation, in Android's messaging style, with its unread messages, Reply from the shade, and Mark read.
 * Nothing shows for the owner's own messages, for history, or for the conversation he is looking at.
 */
object Alerts {
    const val MESSAGES = "messages"
    const val LINK = "link"
    const val EXTRA_CHAT = "dev.omalauncher.messages.CHAT"
    const val REPLY = "dev.omalauncher.messages.REPLY"
    const val READ = "dev.omalauncher.messages.READ"
    private const val KEY_TEXT = "text"

    /** The conversation on screen, whose messages need no notification while the app is in front ([front]). */
    @Volatile
    var looking: String? = null

    @Volatile
    var front = false

    fun allowed(context: Context) =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New iMessages" })
        nm.createNotificationChannel(
            NotificationChannel(LINK, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Soma Messages keeps in touch with your Mac"
                setShowBadge(false)
            },
        )
    }

    private fun tag(id: String) = "chat:$id"

    private fun code(id: String) = id.hashCode()

    /** Opens [id] in the app. */
    fun openIntent(
        context: Context,
        id: String?,
    ): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        id?.let { i.putExtra(EXTRA_CHAT, it) }
        return PendingIntent.getActivity(context, id?.let(::code) ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** [c]'s notification, with its unread messages from others, newest last; nothing when there are none. */
    fun show(
        context: Context,
        c: Conversation,
    ) {
        if (!allowed(context) || c.id in ChatChoices.state.value.muted || front && c.id == looking) return
        val unread = c.messages.takeLast(maxOf(c.unread, 1)).filter { !it.fromMe }.takeLast(6)
        if (unread.isEmpty() || c.unread == 0) return
        val me = Person.Builder().setName("You").build()
        val style = Notification.MessagingStyle(me).setConversationTitle(if (c.isGroup) c.title else null).setGroupConversation(c.isGroup)
        unread.forEach { m -> style.addMessage(m.text, m.time, Person.Builder().setName(m.sender.ifEmpty { c.title }).setKey(m.sender).build()) }
        val reply =
            Notification.Action.Builder(
                null,
                "Reply",
                PendingIntent.getBroadcast(
                    context,
                    code(c.id),
                    Intent(context, AlertReceiver::class.java).setAction(REPLY).putExtra(EXTRA_CHAT, c.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            ).addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("iMessage").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(true)
                .build()
        val read =
            Notification.Action.Builder(
                null,
                "Mark read",
                PendingIntent.getBroadcast(
                    context,
                    code(c.id),
                    Intent(context, AlertReceiver::class.java).setAction(READ).putExtra(EXTRA_CHAT, c.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
        val n =
            Notification.Builder(context, MESSAGES)
                .setSmallIcon(dev.omalauncher.messages.R.drawable.ic_notify)
                .setStyle(style)
                .setContentIntent(openIntent(context, c.id))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(unread.last().time)
                .addAction(reply)
                .addAction(read)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(tag(c.id), 1, n)
    }

    fun clear(
        context: Context,
        id: String,
    ) = context.getSystemService(NotificationManager::class.java).cancel(tag(id), 1)

    /** The text a Reply from the shade carried. */
    fun replyText(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()?.ifEmpty { null }
}

/** Reply and Mark read from a notification. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getStringExtra(Alerts.EXTRA_CHAT) ?: return
        Messages.init(context.applicationContext)
        when (intent.action) {
            Alerts.REPLY -> {
                val text = Alerts.replyText(intent) ?: return
                Messages.send(context.applicationContext, id, text)
                // Answered: the conversation is read, and its notification goes.
                Messages.read(id)
            }
            Alerts.READ -> Messages.read(id)
        }
        Alerts.clear(context, id)
    }
}
