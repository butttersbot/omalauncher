package dev.omalauncher.widgets.annotations

import android.content.Context
import java.io.File

/** The one [StickerShelf] of the running launcher. */
object HomeStickers {
    private var shelf: StickerShelf? = null

    /** The shelf, made on first use: the copies are `annotations/stickers/` in the app's private files, beside the pages' files. */
    fun of(context: Context): StickerShelf {
        val app = context.applicationContext
        return shelf ?: StickerShelf(dev.omalauncher.LauncherData.stickersDir(app), StickerSnapshots(File(app.filesDir, "annotations/stickers"), ::measurePicture)).also { shelf = it }
    }
}
