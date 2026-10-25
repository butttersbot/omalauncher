package dev.omalauncher.messages.engine

/**
 * Which way up a picture goes. A phone's camera keeps most photos sideways and says in the file's EXIF which way is up;
 * Android's decoder draws the pixels as stored, so without this they showed turned a quarter (Boss, 2026-10-24).
 * Pure, for the JVM tests.
 */
object Upright {
    /** The turn, clockwise in degrees, and whether to mirror first, for EXIF orientation [tag] (1-8; others: as is). */
    fun of(tag: Int): Pair<Int, Boolean> =
        when (tag) {
            2 -> 0 to true
            3 -> 180 to false
            4 -> 180 to true
            5 -> 90 to true
            6 -> 90 to false
            7 -> 270 to true
            8 -> 270 to false
            else -> 0 to false
        }

    /**
     * Whether a video's frame still needs turning by [rotation]: some Androids hand frames back already upright, others
     * as stored. A frame [frameWidth] x [frameHeight] the stored way round ([storedWidth] x [storedHeight]) needs it.
     */
    fun frameNeedsTurn(
        rotation: Int,
        frameWidth: Int,
        frameHeight: Int,
        storedWidth: Int,
        storedHeight: Int,
    ): Boolean {
        if (rotation % 360 == 0) return false
        // Turned half round, or square: its shape can't say; frames come back upright on the phones that say rotation.
        if (rotation % 180 == 0 || storedWidth == storedHeight) return false
        return (frameWidth > frameHeight) == (storedWidth > storedHeight)
    }
}
