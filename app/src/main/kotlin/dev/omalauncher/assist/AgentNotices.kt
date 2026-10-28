package dev.omalauncher.assist

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.omalauncher.R

/** The agent's notifications: one for the task, one per approval. What they say is decided in Approvals.kt. */
internal object AgentNotices {
    private const val TASK = 41
    private const val BRIEF = 42
    /** The front agent's answer to a spoken request, where no card can show it: apart from the task's, which may be running. */
    const val FRONT = 43
    /** The answer of a project thread on the Mac, apart from the phone builder's task, which may be running beside it. */
    const val MAC = 44
    /** The answer of builder B, apart from builder A's task notification, which may be running beside it. */
    private const val BUILDER_B = 45
    /** What canvas sync couldn't do or was told (slice 8b): quiet, replaced each time it changes, gone when there's nothing. */
    private const val CANVAS_SYNC = 46
    /** The answer of builder C, apart from A's task notification and B's answer. */
    private const val BUILDER_C = 47
    private const val TASK_CHANNEL = "agent-task-updates"
    private const val APPROVAL_CHANNEL = "agent-approvals"
    private const val ANSWER_CHANNEL = "agent-answers"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(TASK_CHANNEL, "Agent progress and results", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(APPROVAL_CHANNEL, "Agent approvals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(ANSWER_CHANNEL, "Agent answers that pop up", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    /** Canvas sync's [notes] in the shade, or none when the last round had nothing to say. */
    fun canvasSync(context: Context, notes: List<String>) {
        val nm = manager(context)
        if (notes.isEmpty()) return nm.cancel(CANVAS_SYNC)
        nm.notify(CANVAS_SYNC, Notification.Builder(context, TASK_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Home's canvas sync").setContentText(notes.first())
            .setStyle(Notification.BigTextStyle().bigText(notes.joinToString("\n"))).setOnlyAlertOnce(true).setAutoCancel(true).build())
    }

    /** Tapping the task notification opens the Oma app, where the thread is. */
    private fun open(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, dev.omalauncher.oma.OmaChatActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)

    /** Which notification a finished task's answer comes up as: builder A's is the task's own, as it always was. */
    fun answerId(builder: BuilderId) = when (builder) {
        BuilderId.A -> TASK
        BuilderId.B -> BUILDER_B
        BuilderId.C -> BUILDER_C
        BuilderId.D -> 48
    }

    /** The ongoing step while a task runs, replaced by its result when it ends. Its stop stops [about], the builder it is about. */
    fun task(context: Context, working: Boolean, label: String, answer: String, pending: Int, about: BuilderId = BuilderId.A) {
        val nm = manager(context)
        val notice = taskNotice(working, label, answer, pending) ?: return nm.cancel(TASK)
        val builder = Notification.Builder(context, TASK_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title).setContentText(notice.text)
            .setOnlyAlertOnce(notice.ongoing).setOngoing(notice.ongoing).setAutoCancel(!notice.ongoing)
        if (about != BuilderId.D) builder.setContentIntent(open(context))
        notice.expanded?.let { builder.setStyle(Notification.BigTextStyle().bigText(it)) }
        if (notice.ongoing) {
            // One request code per builder, so the stop of one is never the other's; A's is the one there always was.
            val intent = Intent(context, StopAgentTask::class.java).apply { if (about != BuilderId.A) putExtra(StopAgentTask.EXTRA_BUILDER, about.letter) }
            val stop = PendingIntent.getBroadcast(context, about.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Stop", stop).build())
            // Asks for a Live Update; whether One UI shows it as one is for the phone to say.
            if (requestsLiveUpdate(Build.VERSION.SDK_INT, Build.VERSION_CODES_FULL.BAKLAVA_1) { Build.VERSION.SDK_INT_FULL }) builder.setRequestPromotedOngoing(true)
        }
        nm.notify(TASK, builder.build())
    }

    /**
     * A task just ended with [answer]: it comes up over whatever is in front as a card in the active look,
     * drawn by the accessibility service, and the task's quiet notification stays in the shade. Where the card
     * can't show (phone control off or paused, the phone locked or asleep) that notification pops up instead.
     * Its text stays off the lock screen either way: notifications here are private. [id] is which notification
     * that is: the task's own, unless the answer is not a task's.
     */
    fun answered(context: Context, label: String, answer: String, id: Int = TASK) {
        val notice = taskNotice(false, label, answer, 0) ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val carded = dev.omalauncher.phone.PhoneService.instance?.showAnswer(dev.omalauncher.phone.answerCardCaption(label), answer) == true
            if (carded) return@post
            manager(context).notify(id, Notification.Builder(context, ANSWER_CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notice.title).setContentText(notice.text).setContentIntent(open(context)).setAutoCancel(true)
                .apply { notice.expanded?.let { setStyle(Notification.BigTextStyle().bigText(it)) } }.build())
        }
    }

    fun surface(context: Context): ApprovalSurface {
        val nm = manager(context)
        return approvalSurface(nm.areNotificationsEnabled(), nm.getNotificationChannel(APPROVAL_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
    }

    /** Opens the approval screen on exactly this approval. */
    fun reviewIntent(context: Context, entry: PendingApproval): Intent =
        Intent(context, AgentApprovalActivity::class.java).setData(Uri.parse(entry.ticket(ApprovalVerb.Review).encode())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shows a new approval of [builder]'s: its own notification, or the approval screen if notifications are blocked. */
    fun request(context: Context, entry: PendingApproval, builder: BuilderId) {
        if (surface(context) == ApprovalSurface.Screen) {
            // Android may refuse an activity started from the background; the tile, the rail and the prompt still reach it.
            runCatching { context.startActivity(reviewIntent(context, entry)) }
            return
        }
        val approval = entry.approval
        val title = if (approval.tool == "AskUserQuestion") "${builder.title} has questions" else builderApprovalTitle(approval.tool, builder)
        val review = PendingIntent.getActivity(context, ApprovalIds.requestCode(entry.slot, ApprovalVerb.Review), reviewIntent(context, entry), FLAGS)
        fun action(verb: ApprovalVerb, label: String): Notification.Action {
            val code = ApprovalIds.requestCode(entry.slot, verb)
            val pending = if (actionDecidesDirectly(Build.VERSION.SDK_INT)) {
                val intent = Intent(context, ApprovalActionReceiver::class.java).setData(Uri.parse(entry.ticket(verb).encode()))
                PendingIntent.getBroadcast(context, code, intent, FLAGS)
            } else PendingIntent.getActivity(context, code, reviewIntent(context, entry), FLAGS)
            val builder = Notification.Action.Builder(null, label, pending)
            // A locked phone has to be unlocked before the button does anything.
            if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
            return builder.build()
        }
        val locked = Notification.Builder(context, APPROVAL_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText("Unlock to review").build()
        val detail = listOf(approval.explanation.take(300), approval.detail.take(1200), if (approval.tool == "AskUserQuestion") "Choose your answers and press Send answers. Opening the form sends nothing." else "Approve once allows this action only.")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        manager(context).notify(ApprovalIds.tag(entry.id), entry.notificationId, Notification.Builder(context, APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(approval.detail.take(180))
            .setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(review)
            .setCategory(Notification.CATEGORY_STATUS).setOngoing(true).setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(locked)
            .apply {
                if (approval.tool == "AskUserQuestion") addAction(Notification.Action.Builder(null, "Answer questions", review).build())
                else { addAction(action(ApprovalVerb.Approve, "Approve once")); addAction(action(ApprovalVerb.Deny, "Deny")) }
            }.build())
    }

    /** Takes down one approval's notification and no other. */
    fun withdraw(context: Context, id: String, slot: Int) {
        if (ApprovalIds.safe(id) && ApprovalIds.validSlot(slot)) manager(context).cancel(ApprovalIds.tag(id), ApprovalIds.notificationId(slot))
    }

    fun withdraw(context: Context, entry: PendingApproval) = withdraw(context, entry.id, entry.slot)

    private fun brief(context: Context, text: String) {
        manager(context).notify(BRIEF, Notification.Builder(context, TASK_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text).setAutoCancel(true).setTimeoutAfter(15_000).build())
    }

    fun expired(context: Context) = brief(context, "That request has expired")

    /** A line about something he just tapped, gone again in a moment. */
    fun say(context: Context, text: String) = brief(context, text)

    /** Opens the approval screen on exactly this request of a thread on the Mac. */
    fun macReviewIntent(context: Context, ticket: String, slot: Int): Intent =
        Intent(context, AgentApprovalActivity::class.java).setData(Uri.parse(MacApprovalTicket(ApprovalVerb.Review, slot, ticket).encode())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Shows a request of a thread on the Mac as the phone builder's are shown, naming the thread: its own
     * notification, or the approval screen if notifications are blocked. The buttons carry the phone's ticket
     * for it and never the Mac's nonce. One with no slot left is answered from the chat.
     */
    fun macRequest(context: Context, entry: MacPending) {
        val slot = entry.slot ?: return
        val review = macReviewIntent(context, entry.ticket, slot)
        if (surface(context) == ApprovalSurface.Screen) {
            runCatching { context.startActivity(review) }
            return
        }
        val title = "Allow ${entry.request.tool.take(60)} on ${entry.threadName.take(40)}?"
        val detail = macApprovalDetail(entry.request)
        fun action(verb: ApprovalVerb, label: String): Notification.Action {
            val code = MacApprovalIds.requestCode(slot, verb)
            val pending = if (actionDecidesDirectly(Build.VERSION.SDK_INT)) {
                val intent = Intent(context, MacApprovalReceiver::class.java).setData(Uri.parse(MacApprovalTicket(verb, slot, entry.ticket).encode()))
                PendingIntent.getBroadcast(context, code, intent, FLAGS)
            } else PendingIntent.getActivity(context, code, review, FLAGS)
            val builder = Notification.Action.Builder(null, label, pending)
            // A locked phone has to be unlocked before the button does anything.
            if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
            return builder.build()
        }
        val locked = Notification.Builder(context, APPROVAL_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText("Unlock to review").build()
        val expanded = listOf(entry.request.explanation.take(300), detail.take(1200), "Approve once allows this action only.")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        manager(context).notify(MacApprovalIds.tag(entry.ticket), MacApprovalIds.notificationId(slot), Notification.Builder(context, APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(detail.take(180))
            .setStyle(Notification.BigTextStyle().bigText(expanded))
            .setContentIntent(PendingIntent.getActivity(context, MacApprovalIds.requestCode(slot, ApprovalVerb.Review), review, FLAGS))
            .setCategory(Notification.CATEGORY_STATUS).setOngoing(true).setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(locked)
            .addAction(action(ApprovalVerb.Approve, "Approve once")).addAction(action(ApprovalVerb.Deny, "Deny")).build())
    }

    /** Takes down the notification of one request of the Mac's and no other. */
    fun macWithdraw(context: Context, ticket: String, slot: Int) {
        if (ApprovalIds.validSlot(slot)) manager(context).cancel(MacApprovalIds.tag(ticket), MacApprovalIds.notificationId(slot))
    }

    fun macWithdraw(context: Context, entry: MacPending) {
        entry.slot?.let { macWithdraw(context, entry.ticket, it) }
    }

    /** After a restart no card of the Mac's from before can be answered: they are taken down, and drawn again from what the Mac says waits. */
    fun clearStaleMac(context: Context) {
        val nm = manager(context)
        for (shown in runCatching { nm.activeNotifications }.getOrNull().orEmpty()) {
            if (shown.tag?.startsWith(MacApprovalIds.TAG_PREFIX) == true) nm.cancel(shown.tag, shown.id)
        }
    }

    fun locked(context: Context) = brief(context, "Unlock your phone to answer that request")

    /** After a restart no earlier approval can be answered and no task is running: take down what the old process left. */
    fun clearStale(context: Context) {
        val nm = manager(context)
        for (shown in runCatching { nm.activeNotifications }.getOrNull().orEmpty()) {
            if (shown.tag?.startsWith(ApprovalIds.TAG_PREFIX) == true) nm.cancel(shown.tag, shown.id)
            else if (shown.tag == null && shown.id == TASK && shown.isOngoing) nm.cancel(TASK)
        }
    }

    fun deviceLocked(context: Context) = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
}

/**
 * Approve once / Deny on the notification of a request from a thread on the Mac. Not exported; the ticket in
 * the intent names the one request it answers, and the answer goes the one way every answer to the Mac does.
 */
class MacApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticket = MacApprovalTicket.parse(intent.dataString)?.takeIf { it.verb != ApprovalVerb.Review } ?: return AgentNotices.expired(context)
        val app = context.applicationContext
        MacThreads.answer(app, ticket.ticket, allow = ticket.verb == ApprovalVerb.Approve) { outcome ->
            // The notification that was tapped goes when nothing waits behind it any more.
            if (outcome == MacApprovalOutcome.Expired) AgentNotices.macWithdraw(app, ticket.ticket, ticket.slot)
            macApprovalNotice(outcome)?.let { AgentNotices.say(app, it) }
        }
    }
}

/** Approve once / Deny on an approval notification. Not exported; the ticket in the intent names the one approval it answers. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticket = ApprovalTicket.parse(intent.dataString)?.takeIf { it.verb != ApprovalVerb.Review } ?: return AgentNotices.expired(context)
        val entry = AgentTasks.pending.firstOrNull { it.id == ticket.id && it.nonce == ticket.nonce }
        if (entry?.approval?.tool == "AskUserQuestion") {
            // Old notification intents must open the form, never deny the question or allow it without answers.
            context.startActivity(AgentNotices.reviewIntent(context, entry))
            return
        }
        when (AgentTasks.answerApproval(context, ticket.id, ticket.nonce, allow = ticket.verb == ApprovalVerb.Approve)) {
            ApprovalOutcome.Decided -> Unit
            ApprovalOutcome.Locked -> AgentNotices.locked(context)
            ApprovalOutcome.Expired -> {
                // The notification that was tapped goes; one that now belongs to a live approval stays.
                if (AgentTasks.pending.none { it.id == ticket.id }) AgentNotices.withdraw(context, ticket.id, ticket.slot)
                AgentNotices.expired(context)
            }
        }
    }
}
