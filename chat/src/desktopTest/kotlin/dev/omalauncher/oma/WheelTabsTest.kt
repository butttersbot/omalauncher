package dev.omalauncher.oma

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linux's Soma (Boss, 2026-10-29): a mouse wheel turned over the tab strip scrolls it sideways; elsewhere it doesn't. */
@OptIn(ExperimentalTestApi::class)
class WheelTabsTest {
    private fun strip(on: Boolean): Int {
        var scroll: ScrollState? = null
        runComposeUiTest {
            setContent {
                val s = rememberScrollState().also { scroll = it }
                Row(Modifier.width(300.dp).testTag("strip").wheelScrollsSideways(s, on).horizontalScroll(s)) {
                    repeat(20) { Box(Modifier.size(100.dp, 30.dp)) }
                }
            }
            onNodeWithTag("strip").performMouseInput { scroll(3f, ScrollWheel.Vertical) }
            waitForIdle()
        }
        return scroll!!.value
    }

    @Test fun aWheelScrollsTheStripSidewaysOnLinux() = assertTrue(strip(on = true) > 0)

    @Test fun elsewhereAnUpAndDownWheelLeavesItWhereItIs() = assertEquals(0, strip(on = false))
}
