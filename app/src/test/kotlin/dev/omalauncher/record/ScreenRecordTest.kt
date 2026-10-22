package dev.omalauncher.record

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRecordTest {
    /** The Fold's screens: the inner one is scaled to the encoders' 1920, the cover kept; both shapes kept, both even. */
    @Test fun theVideoKeepsTheScreensShapeWithinWhatTheEncoderTakes() {
        assertEquals(1920 to 1598, ScreenRecord.videoSize(2184, 1818))
        assertEquals(1080 to 1920, ScreenRecord.videoSize(1080, 1920))
        assertEquals(1248 to 1920, ScreenRecord.videoSize(1248, 1920))
        assertEquals(712 to 1920, ScreenRecord.videoSize(968, 2610))
        assertEquals(720 to 1280, ScreenRecord.videoSize(720, 1280))
    }
}
