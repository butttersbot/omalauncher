package dev.omalauncher.oma

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID

/** How Oma came up. Only Android's own assistant gesture may bring the screen along. */
enum class Summon { AssistGesture, Other }

/** What the "this screen" chip is. */
enum class ScreenContext {
    /** No chip, nothing attached. */
    None,

    /** The screenshot Android handed the assistant session is attached, and the chip shows it. */
    Attached,

    /** Summoned over another app, but Android gave no screenshot (a secure window, or it is switched off); the chip says so. */
    Unavailable,
}

/**
 * Whether a request carries the screen. The screen goes to an outside AI provider, so the only yes is:
 * summoned by the assistant gesture, over another app, on an unlocked phone, with a screenshot from Android.
 * The tile, a notification, the dock, the rail and the prompt activity are all [Summon.Other].
 */
fun screenContext(summon: Summon, launcherInFront: Boolean, locked: Boolean, screenshot: Boolean): ScreenContext = when {
    summon != Summon.AssistGesture || launcherInFront || locked -> ScreenContext.None
    screenshot -> ScreenContext.Attached
    else -> ScreenContext.Unavailable
}

/** The size a screenshot is kept at: its longer edge at most [longest] pixels, never enlarged. */
fun screenshotSize(width: Int, height: Int, longest: Int = 1600): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val edge = maxOf(width, height)
    if (edge <= longest) return width to height
    return maxOf(1, width * longest / edge) to maxOf(1, height * longest / edge)
}

/**
 * The screenshot behind the chip, in a folder of the app's private files that Linux can't see.
 * A file lives there only while its chip shows: removing the chip, hiding the assistant, sending
 * the request and starting the app all delete it.
 */
object ScreenShots {
    private fun dir(context: Context) = File(context.filesDir, "oma-screen")

    /** Saves Android's screenshot for the chip; null if it couldn't be saved. */
    fun keep(context: Context, screenshot: Bitmap): File? = runCatching {
        val dir = dir(context).apply { mkdirs() }
        val (width, height) = screenshotSize(screenshot.width, screenshot.height)
        // The system's bitmap may live on the GPU, which can't be scaled or compressed directly.
        val pixels = screenshot.copy(Bitmap.Config.ARGB_8888, false)
        val scaled = if (width == pixels.width) pixels else Bitmap.createScaledBitmap(pixels, width, height, true)
        val file = File(dir, "screen-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            if (scaled !== pixels) scaled.recycle()
            pixels.recycle()
        }
    }.getOrNull()

    fun discard(file: File?) {
        file?.delete()
    }

    /** No chip is showing: nothing may be left. */
    fun discardAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** The copy Codex reads its image from; AgentTasks deletes it when that task ends. */
    fun handOver(file: File, attachments: File): File {
        check(attachments.mkdirs() || attachments.isDirectory) { "Could not create the attachments folder." }
        val copy = File(attachments, HANDED_PREFIX + file.name)
        try {
            file.copyTo(copy, overwrite = true)
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        file.delete()
        return copy
    }

    /** At process start no task runs, so no handed-over copy is still needed either. */
    fun discardHandedOver(attachments: File) {
        attachments.listFiles { f -> f.name.startsWith(HANDED_PREFIX) }?.forEach { it.delete() }
    }

    private const val HANDED_PREFIX = "oma-screen-"
}

/** Whether one of this app's own screens (the launcher, its windows, the chat) is showing, so Oma isn't over another app. */
object OwnScreens : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val visible get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Runs before anything else in the process, which is the only place [OwnScreens] can start counting
 * without missing a screen, and clears screenshots a killed process left. Holds no data.
 */
class OmaStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context as? Application ?: context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(OwnScreens)
        ScreenShots.discardAll(app)
        ScreenShots.discardHandedOver(File(app.filesDir, "attachments"))
        return true
    }
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
