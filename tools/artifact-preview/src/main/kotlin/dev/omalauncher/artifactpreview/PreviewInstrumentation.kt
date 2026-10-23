package dev.omalauncher.artifactpreview

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.WindowInsets
import android.graphics.Rect
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only the isolated fixture, never a user's app, data or agent. No additional test libraries required. */
class PreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }
    private fun find(text: String): AccessibilityNodeInfo? = nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.text?.contains(text) == true || it.contentDescription?.contains(text) == true }
    private fun await(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected fixture UI: $text. Seen: " + nodes(uiAutomation.rootInActiveWindow).mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                SystemClock.sleep(250)
                current.refresh()
                val bounds = Rect()
                current.getBoundsInScreen(bounds)
                val now = SystemClock.uptimeMillis()
                sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                sendPointerSync(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                return
            }
            current = current.parent
        }
        error("Fixture control did not accept a click")
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PreviewActivity
            await("listening-room.html")
            val field = nodes(uiAutomation.rootInActiveWindow).first { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep this draft") }))
            check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            runOnMainSync { activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.currentFocus, InputMethodManager.SHOW_IMPLICIT) }
            fun imeVisible(): Boolean {
                var visible = false
                runOnMainSync { visible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                return visible
            }
            val imeEnd = SystemClock.uptimeMillis() + 5000
            while (!imeVisible() && SystemClock.uptimeMillis() < imeEnd) SystemClock.sleep(100)
            check(imeVisible()) { "Test keyboard did not open" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(500)
            check(!imeVisible()) { "Back did not hide keyboard" }
            await("Keep this draft")
            Log.i("SomaWalk", "artifact IME Back and draft PASS")
            click(await("listening-room.html"))
            click(await("Play sample"))
            await("PLAYING / SIDE A")
            Log.i("SomaWalk", "artifact HTML JavaScript PASS")
            click(await("[ source ]"))
            await("<!doctype html>")
            click(await("[ files ]"))
            click(await("interaction-notes.md"))
            await("Close it to return to the same draft.")
            click(await("listening-room.html"))
            await("Play sample")
            Log.i("SomaWalk", "artifact Files Source Preview PASS")
            uiAutomation.waitForIdle(200, 3000)
            SystemClock.sleep(350)
            val output = File(targetContext.getExternalFilesDir(null), "artifact-android-native.png")
            uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            fun web(view: View): WebView? = when (view) {
                is WebView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
                else -> null
            }
            fun pageLoaded() {
                val ready = CountDownLatch(1)
                var body = ""
                runOnMainSync {
                    checkNotNull(web(activity.window.decorView)).evaluateJavascript("document.body.innerText") { body = it; ready.countDown() }
                }
                check(ready.await(5, TimeUnit.SECONDS)) { "WebView stopped answering after resize" }
                check(body.contains("For an evening in")) { "HTML missing after resize: $body" }
            }
            repeat(3) { cycle ->
                for (narrow in listOf(true, false)) {
                    runOnMainSync {
                        val page = checkNotNull(web(activity.window.decorView))
                        check(page.requestFocus())
                        check(page.hasFocus())
                        activity.narrow = narrow
                    }
                    waitForIdleSync()
                    SystemClock.sleep(700)
                    pageLoaded()
                    sendStatus(0, Bundle().apply { putString("stream", "Focused fold cycle ${cycle + 1}, narrow=$narrow passed\n") })
                }
            }
            Log.i("SomaWalk", "artifact focused fold cycles PASS")
            click(await("[ close ]"))
            await("Keep this draft")
            await("[ send ]")
            await("Dictate")
            runOnMainSync { activity.narrow = true }
            waitForIdleSync()
            click(await("listening-room.html"))
            await("Play sample")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Keep this draft")
            await("[ send ]")
            Log.i("SomaWalk", "artifact viewer Back and draft PASS")
            result.putString("stream", "PASS: native HTML and JavaScript; three focused-WebView cover/unfold cycles; close restored draft and existing composer. Screenshot: ${output.name}")
            finish(RESULT_OK, result)
        } catch (problem: Throwable) {
            Log.e("SomaWalk", "artifact FAIL: ${problem.message}")
            result.putString("stream", "FAIL: ${problem.message}")
            finish(RESULT_CANCELED, result)
        }
    }
    companion object { private const val RESULT_OK = -1; private const val RESULT_CANCELED = 0 }
}
