package dev.omalauncher.artifactpreview

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only instrumentation targeting our launcher. No accounts, sends, or setting value changes. */
class PhoneSpeedInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val type = targetContext.classLoader.loadClass("dev.omalauncher.phone.PhoneService")
            val companion = type.getField("Companion").get(null)
            val instance = companion.javaClass.getMethod("getInstance")
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var service: Any? = null
            while (service == null && SystemClock.elapsedRealtime() < deadline) {
                service = instance.invoke(companion)
                if (service == null) SystemClock.sleep(50)
            }
            check(service != null) { "Own launcher Accessibility service was not bound on emulator" }
            val bound = service
            val request = type.getMethod("request", JSONObject::class.java, kotlin.jvm.functions.Function1::class.java)
            fun call(input: JSONObject): JSONObject {
                val latch = CountDownLatch(1)
                var output: JSONObject? = null
                Handler(Looper.getMainLooper()).post {
                    val callback: (JSONObject) -> Unit = { output = it; latch.countDown() }
                    try { request.invoke(bound, input, callback) }
                    catch (failure: Throwable) {
                        output = JSONObject().put("ok", false).put("error", failure.toString())
                        latch.countDown()
                    }
                }
                check(latch.await(10, TimeUnit.SECONDS)) { "Phone service did not reply; do not retry mutations" }
                return checkNotNull(output)
            }
            val sound = call(JSONObject().put("command", "open").put("package", "settings:sound").put("until", "Sound"))
            check(sound.optBoolean("ok") && sound.optBoolean("destinationVerified")) { sound.toString() }
            val opened = call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "Display").put("timing", true))
            check(opened.optBoolean("ok") && opened.optBoolean("inFront") && opened.optBoolean("destinationVerified")) { opened.toString() }
            check(opened.getJSONObject("timing").getDouble("serviceMs") >= 0)
            val screen = call(JSONObject().put("command", "screen").put("until", "Display").put("timing", true))
            check(screen.optBoolean("ok") && screen.optBoolean("shows")) { screen.toString() }
            val timing = screen.getJSONObject("timing")
            check(timing.getInt("reads") >= 1 && timing.getInt("nodes") > 0 && timing.getDouble("readMs") >= 0)
            Log.i("SomaWalk", "speed METRIC open=" + opened.getJSONObject("timing") + " screen=" + timing)
            Log.i("SomaWalk", "speed PASS: actual service opens Display and reports read timing")
            // Stage 3 label query: one control that says exactly this is pressed without reading the whole screen first;
            // the only read is the screen it led to.
            val display = call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "Display"))
            check(display.optBoolean("ok") && display.optBoolean("destinationVerified")) { display.toString() }
            val quick = call(JSONObject().put("command", "press").put("label", "Screen timeout").put("timing", true))
            check(quick.optBoolean("ok")) { "Press by label failed: $quick" }
            val quickTiming = quick.getJSONObject("timing")
            Log.i("SomaWalk", "speed METRIC quickPress=" + quickTiming)
            check(quickTiming.getInt("quickFinds") == 1 && quickTiming.getInt("reads") == 1) { "The label query read the whole screen first: $quickTiming" }
            call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "Display"))
            Log.i("SomaWalk", "speed PASS: a press by label found its control without a whole read")
            // Stage 3 deltas: a second look after the screen changed, in the same app, is only what changed.
            call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "Display"))
            val first = call(JSONObject().put("command", "screen"))
            call(JSONObject().put("command", "open").put("package", "settings:sound").put("until", "Sound"))
            val delta = call(JSONObject().put("command", "screen").put("since", first.getString("snapshot")).put("delta", true))
            check(delta.has("changed") && delta.has("gone") && !delta.has("controls")) { "Expected a delta: $delta" }
            Log.i("SomaWalk", "speed METRIC delta changed=" + delta.getJSONArray("changed").length() + " gone=" + delta.getJSONArray("gone").length() + " same=" + delta.getInt("same"))
            Log.i("SomaWalk", "speed PASS: a changed screen came back as only what changed")
            // Stage 3: asked again about the snapshot it has, with nothing changed, the service answers "unchanged" without a
            // read. Settings may still be settling: a few tries, each a real answer, never a fabricated one.
            var since = screen.getString("snapshot")
            var quiet: JSONObject? = null
            repeat(5) {
                if (quiet != null) return@repeat
                Thread.sleep(600)
                val again = call(JSONObject().put("command", "screen").put("since", since).put("timing", true))
                check(again.optBoolean("ok")) { again.toString() }
                if (again.optBoolean("unchanged")) quiet = again else since = again.getString("snapshot")
            }
            val stood = checkNotNull(quiet) { "A still screen never answered unchanged" }
            check(stood.getJSONObject("timing").getInt("reads") == 0) { "An unchanged answer read the screen: $stood" }
            check(!stood.has("nodes") && !stood.has("controls")) { "An unchanged answer sent the screen again: $stood" }
            Log.i("SomaWalk", "speed PASS: unchanged screen answered without a read")
            val soundAgain = call(JSONObject().put("command", "open").put("package", "settings:sound").put("until", "Sound"))
            check(soundAgain.optBoolean("ok") && soundAgain.optBoolean("destinationVerified")) { soundAgain.toString() }
            val displayAgain = call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "Display"))
            check(displayAgain.optBoolean("ok") && displayAgain.optBoolean("destinationVerified")) { displayAgain.toString() }
            Log.i("SomaWalk", "speed PASS: Sound Display Sound Display transitions reach verified destinations")
            val wrong = call(JSONObject().put("command", "open").put("package", "settings:display").put("until", "SOMA FICTIONAL WRONG DESTINATION 6318"))
            check(!wrong.optBoolean("ok") && !wrong.optBoolean("destinationVerified")) { "Package-only success masqueraded as destination verification: $wrong" }
            Log.i("SomaWalk", "speed PASS: package alone cannot satisfy an open destination assertion")
            val refused = call(JSONObject().put("command", "open").put("package", "settings:fictional-invalid"))
            check(!refused.optBoolean("ok"))
            val unchanged = call(JSONObject().put("command", "screen").put("until", "Display"))
            check(unchanged.optBoolean("ok") && unchanged.optBoolean("shows"))
            Log.i("SomaWalk", "speed PASS: unsupported destination refuses without changing screen")
            val absent = call(JSONObject().put("command", "press").put("label", "SOMA FICTIONAL MISSING CONTROL 92631").put("timing", true))
            check(!absent.optBoolean("ok"))
            check(absent.optString("error").contains("Nothing on screen")) { "Expected search exhaustion, not another failure: $absent" }
            Log.i("SomaWalk", "speed METRIC absent=" + absent.getJSONObject("timing"))
            val scrolls = absent.getJSONObject("timing").getInt("searchScrolls")
            check(scrolls in 0..12) { "Search exceeded two bounded directions: $scrolls" }
            Log.i("SomaWalk", "speed PASS: actual absent-label search bounded; scrolls=$scrolls")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Phone service speed primitives PASS\n") })
        } catch (problem: Throwable) {
            Log.e("SomaWalk", "speed FAIL", problem)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", problem.stackTraceToString()) })
        }
    }
}
