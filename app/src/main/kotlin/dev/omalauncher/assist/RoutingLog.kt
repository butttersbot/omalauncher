package dev.omalauncher.assist

import android.content.Context
import java.io.File

/**
 * The requests that did not go where he wanted at the first try: ones the front agent answered that he then
 * sent to build, ones it handed to a thread that is not there, and ones he had to send to the Mac a second
 * time. [was] says which. Kept in the app's private files so they can be counted and fed back as examples.
 * Nothing reads it yet.
 */
object RoutingLog {
    @Synchronized fun corrected(context: Context, request: String, was: String = FRONT_ANSWERED) {
        runCatching {
            val file = File(context.filesDir, "oma-routing.jsonl")
            val kept = if (file.isFile) file.readLines() else emptyList()
            file.writeText(routingLog(kept, routingCorrection(System.currentTimeMillis(), request, was)).joinToString("\n", postfix = "\n"))
        }
    }
}
