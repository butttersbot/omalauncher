package dev.omalauncher.artifactpreview

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

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
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        error("Fixture control did not accept a click")
    }
    override fun onStart() {
        val result = Bundle()
        try {
            startActivitySync(Intent(targetContext, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("listening-room.html")
            val field = nodes(uiAutomation.rootInActiveWindow).first { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep this draft") }))
            click(await("listening-room.html"))
            click(await("Play sample"))
            await("PLAYING / SIDE A")
            uiAutomation.waitForIdle(200, 3000)
            SystemClock.sleep(350)
            val output = File(targetContext.getExternalFilesDir(null), "artifact-android-native.png")
            uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            click(await("[ close ]"))
            await("Keep this draft")
            await("[ send ]")
            await("Dictate")
            result.putString("stream", "PASS: native HTML rendered; JavaScript interaction worked; close restored draft and existing composer. Screenshot: ${output.name}")
            finish(RESULT_OK, result)
        } catch (problem: Throwable) {
            result.putString("stream", "FAIL: ${problem.message}")
            finish(RESULT_CANCELED, result)
        }
    }
    companion object { private const val RESULT_OK = -1; private const val RESULT_CANCELED = 0 }
}
