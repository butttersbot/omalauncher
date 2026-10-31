package dev.omalauncher.oma

import java.io.File

/** The one thread of the Oma app. */
object ChatLog {
    @Volatile private var store: ChatStore? = null

    fun of(context: android.content.Context): ChatStore = store ?: synchronized(this) {
        store ?: ChatStore(File(context.filesDir, "oma-chat.json")).also { it.load(QueuedMail.times(context.filesDir, dev.omalauncher.assist.BuilderId.entries.map { b -> queuedMailFile(b) })); store = it }
    }
}

/** The chat's list of copied-in images, in this app's private files. */
fun ChatDrafts.Companion.of(context: android.content.Context): ChatDrafts = of(context.filesDir)

/** Where a builder's waiting agent mail is kept across a restart. */
fun queuedMailFile(builder: dev.omalauncher.assist.BuilderId) = "queued-mail-" + builder.letter.lowercase() + ".json"
