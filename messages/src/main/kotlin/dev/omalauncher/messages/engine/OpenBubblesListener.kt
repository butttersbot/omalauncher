package dev.omalauncher.messages.engine

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads OpenBubbles' message notifications. Each one is a conversation in Android's messaging style: its recent
 * messages, its title, and a Reply action. Nothing else on the phone is read, and nothing is dismissed or changed.
 */
class OpenBubblesListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Messages.listening.value = true
        runCatching { activeNotifications }.getOrNull()?.forEach(::take)
    }

    override fun onListenerDisconnected() {
        Messages.listening.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::take)
    }

    private fun take(sbn: StatusBarNotification) {
        if (sbn.packageName != OPENBUBBLES) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val seen = read(n, sbn.key) ?: return
        Messages.seen(applicationContext, seen, handles(n))
    }

    companion object {
        /** A conversation from a messaging-style notification; null for anything else (OpenBubbles' "Ready for messages"). */
        fun read(
            n: Notification,
            key: String,
        ): SeenConversation? {
            val extras = n.extras
            val me = person(extras, Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
            val messages = (bundles(extras, Notification.EXTRA_HISTORIC_MESSAGES) + bundles(extras, Notification.EXTRA_MESSAGES)).mapNotNull { message(it, me) }
            if (messages.isEmpty()) return null
            val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            val id = n.shortcutId ?: n.channelId ?: key
            return SeenConversation(id, title, extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION), messages)
        }

        private fun message(
            b: Bundle,
            me: String?,
        ): SeenMessage? {
            val text = b.getCharSequence("text")?.toString() ?: return null
            val sender = (person(b, "sender_person")?.name ?: b.getCharSequence("sender"))?.toString()
            // A message with no sender is the owner's own, as is one sent under the owner's own name.
            return SeenMessage(text, b.getLong("time"), sender?.takeIf { it.isNotBlank() && it != me })
        }

        @Suppress("DEPRECATION")
        private fun bundles(
            extras: Bundle,
            key: String,
        ): List<Bundle> {
            val array: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) extras.getParcelableArray(key, Parcelable::class.java) else extras.getParcelableArray(key)
            return array.orEmpty().filterIsInstance<Bundle>()
        }

        @Suppress("DEPRECATION")
        private fun person(
            b: Bundle,
            key: String,
        ): Person? = if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, Person::class.java) else b.getParcelable(key)

        /** The notification's Reply and Mark read actions and its tap. */
        fun handles(n: Notification): Handles {
            val actions = n.actions.orEmpty()
            val reply =
                actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
                    ?: actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            val markRead = actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            return Handles(reply?.actionIntent, reply?.remoteInputs, markRead?.actionIntent, n.contentIntent)
        }
    }
}
