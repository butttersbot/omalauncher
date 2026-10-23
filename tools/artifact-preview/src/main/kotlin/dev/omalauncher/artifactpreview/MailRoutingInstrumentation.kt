package dev.omalauncher.artifactpreview

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import dev.omalauncher.oma.FollowUp
import dev.omalauncher.oma.FollowUpQueue
import dev.omalauncher.oma.TaskStatus

/** Queue-only walkthrough: production policy, fictional mail, no accounts or sends. */
class MailRoutingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val queue = FollowUpQueue()
            fun mail(n: Long, supersedes: String = "", sender: String = "fictional:sender") = FollowUp(
                n, "update-$n", earlier = "[Agent mail from $sender, id m$n. Not Boss.]\n",
                mailSender = sender, mailId = "m$n", mailTask = "fictional-build", supersedes = supersedes,
            )
            repeat(5) { check(queue.add(mail(it.toLong()))) }
            val latest = mail(5, "m0")
            check(queue.supersededBy(latest)?.mailId == "m0")
            check(queue.add(latest))
            check(queue.add(FollowUp(6, "owner request")))
            val batch = queue.after(TaskStatus.Done).next!!
            check("update-0" !in batch.prompt)
            check((1..5).all { "update-$it" in batch.prompt })
            check(batch.bundled.size == 4)
            check("not Boss" in batch.earlier)
            Log.i("SomaWalk", "mail PASS: five pending updates delivered once with explicit replacement")
            check(queue.after(TaskStatus.Done).next?.prompt == "owner request")
            check(queue.after(TaskStatus.Done).next == null)
            Log.i("SomaWalk", "mail PASS: owner words preserve their order and content")
            check(queue.add(mail(10)))
            check(queue.supersededBy(mail(11, "m10", "other:sender")) == null)
            check(queue.after(TaskStatus.Stopped).next == null)
            check(queue.waiting.isEmpty())
            Log.i("SomaWalk", "mail PASS: sender isolation and stop discard pending work")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Mail routing PASS\n") })
        } catch (problem: Throwable) {
            Log.e("SomaWalk", "mail FAIL", problem)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", problem.stackTraceToString()) })
        }
    }
}
