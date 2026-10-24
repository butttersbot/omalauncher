package dev.omalauncher.artifactpreview

import android.app.Activity
import android.app.Instrumentation
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Emulator-only: same-UID bridge calls; no accounts, real sends or setting-value changes. */
class PhonePlanInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun call(body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray()
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("dev.omalauncher.boss.phone", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 35_000
            socket.outputStream.write(("POST /phone HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            socket.outputStream.write(bytes);socket.outputStream.flush()
            return JSONObject(socket.inputStream.readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n"))
        }
    }
    private fun screen(text: String) = JSONObject().put("command", "screen").put("in", "com.android.settings").put("until", text)
    private fun open(name: String) = JSONObject().put("command", "open").put("package", name)
    private fun plan(vararg steps: JSONObject) = JSONObject().put("command", "plan")
        .put("id", "${System.currentTimeMillis()}-${UUID.randomUUID()}").put("steps", JSONArray(steps.toList())).put("timing", true)
    override fun onStart() {
        var restorePause = false
        var resultCode = Activity.RESULT_CANCELED
        var message = "Native phone plans failed"
        try {
            val initial = call(JSONObject().put("command", "status"))
            check(initial.optBoolean("active") && initial.optBoolean("connected") && !initial.optBoolean("locked")) { "Own emulator service must already be enabled and unpaused" }
            val p = plan(open("settings:display"), screen("Display"))
            val first = call(p)
            check(first.optBoolean("ok") && first.optInt("completed") == 2) { first.toString() }
            check(first.getJSONObject("screen").optBoolean("shows"))
            check(call(open("settings:sound")).optBoolean("inFront"))
            val again = call(p)
            check(again.optBoolean("ok") && again.optBoolean("replayed"))
            check(call(screen("Sound")).optBoolean("shows")) { "Retry navigated again instead of returning receipt" }
            val receipt = call(JSONObject().put("command", "plan-receipt").put("id", p.getString("id")))
            check(receipt.optString("state") == "done")
            Log.i("SomaWalk", "plan PASS: native sequence verifies final screen and duplicate id does not replay")
            Log.i("SomaWalk", "plan METRIC native=" + first.getJSONObject("timing"))
            val changed = JSONObject(p.toString()).put("steps", JSONArray(listOf(open("settings:display"), screen("Different"))))
            check(!call(changed).optBoolean("ok"))
            val invalid = plan(open("settings:display"), JSONObject().put("command", "shell"), screen("Display"))
            check(!call(invalid).optBoolean("ok"))
            check(call(screen("Sound")).optBoolean("shows"))
            Log.i("SomaWalk", "plan PASS: all steps prevalidated and conflicting id refused without navigation")
            val failure = call(plan(screen("SOMA FICTIONAL MISSING 5937"), open("settings:display"), screen("Display")))
            check(!failure.optBoolean("ok") && failure.optInt("completed") == 0 && failure.optInt("failedStep") == 1) { failure.toString() }
            check(call(screen("Sound")).optBoolean("shows"))
            Log.i("SomaWalk", "plan PASS: failed assertion prevents later navigation")
            val finished = CountDownLatch(1)
            var cancelled: JSONObject? = null
            val pending = plan(screen("SOMA FICTIONAL STOP WAIT 1048"), open("settings:display"), screen("Display"))
            thread(isDaemon = true) {
                try { cancelled = call(pending) }
                catch (failure: Throwable) { cancelled = JSONObject().put("ok", false).put("error", failure.toString()) }
                finally { finished.countDown() }
            }
            val deadline = SystemClock.elapsedRealtime() + 2_000
            var active = false
            while (!active && SystemClock.elapsedRealtime() < deadline) {
                active = !call(JSONObject().put("command", "status")).isNull("plan")
                if (!active) SystemClock.sleep(20)
            }
            check(active) { "Plan did not become active" }
            restorePause = true // Stop changes only our isolated emulator app's pause flag; restore it below.
            val began = SystemClock.elapsedRealtime()
            check(call(JSONObject().put("command", "stop")).optBoolean("ok"))
            check(SystemClock.elapsedRealtime() - began < 1_500) { "Stop waited behind the plan" }
            check(finished.await(2, TimeUnit.SECONDS))
            check(cancelled?.optString("state") == "cancelled" && cancelled?.optInt("completed") == 0) { cancelled.toString() }
            val stoppedReceipt = call(JSONObject().put("command", "plan-receipt").put("id", pending.getString("id")))
            check(stoppedReceipt.optString("state") == "cancelled")
            Log.i("SomaWalk", "plan PASS: bridge status and Stop remain responsive; cancellation persisted while paused")
            resultCode = Activity.RESULT_OK
            message = "Native phone plans PASS\n"
        } catch (failure: Throwable) {
            Log.e("SomaWalk", "plan FAIL", failure)
            message = failure.stackTraceToString()
        } finally {
            if (restorePause) {
                try {
                    val type = targetContext.classLoader.loadClass("dev.omalauncher.phone.PhoneAccess")
                    val instance = type.getField("INSTANCE").get(null)
                    runOnMainSync { type.getMethod("resume", android.content.Context::class.java).invoke(instance, targetContext) }
                    check(call(screen("Sound")).optBoolean("shows"))
                    SystemClock.sleep(350)
                    check(call(screen("Sound")).optBoolean("shows"))
                    Log.i("SomaWalk", "plan PASS: restored emulator pause state; cancelled callbacks cannot navigate later")
                } catch (cleanup: Throwable) {
                    Log.e("SomaWalk", "plan FAIL: emulator cleanup", cleanup)
                    resultCode = Activity.RESULT_CANCELED
                    message = cleanup.stackTraceToString()
                }
            }
        }
        finish(resultCode, Bundle().apply { putString("stream", message) })
    }
}
