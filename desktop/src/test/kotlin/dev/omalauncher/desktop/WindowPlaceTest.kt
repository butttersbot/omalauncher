package dev.omalauncher.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Point
import java.awt.Rectangle

/** Where the window goes when shown: onto the screen he is on (two side-by-side monitors, as on the Mac Pro). */
class WindowPlaceTest {
    private val main = Rectangle(0, 0, 3440, 1440)
    private val left = Rectangle(-3440, 0, 3440, 1440)

    @Test fun aWindowOnTheOtherMonitorIsCenteredOnHis() =
        assertEquals(Point(1100, 310), placeOn(Rectangle(-3392, 77, 1240, 820), main))

    @Test fun aWindowAlreadyOnHisMonitorStaysWhereHePutIt() {
        assertEquals(Point(300, 120), placeOn(Rectangle(300, 120, 1240, 820), main))
        assertEquals(Point(-3392, 77), placeOn(Rectangle(-3392, 77, 1240, 820), left))
    }

    @Test fun aWindowBiggerThanTheScreenStartsAtItsCorner() =
        assertEquals(Point(0, 0), placeOn(Rectangle(-3392, 77, 4000, 2000), main))
}
