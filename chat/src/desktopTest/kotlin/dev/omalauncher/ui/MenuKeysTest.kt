package dev.omalauncher.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A field in a menu (a tab's Rename): Space types a space and Enter doesn't close the menu (Boss, 2026-10-29, on Linux:
 * "every time he types a space the rename box closes"). Only a click on the scrim closes it.
 */
@OptIn(ExperimentalTestApi::class)
class MenuKeysTest {
    @Test fun spaceAndEnterInAMenusFieldNeverCloseIt() = runComposeUiTest {
        var closed = 0
        var name by mutableStateOf("Fold")
        setContent {
            Menu(onDismiss = { closed++ }) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(name, { name = it }, Modifier.testTag("field").focusRequester(focus), singleLine = true)
            }
        }
        waitForIdle()
        onNodeWithTag("field").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("field").performTextInput(" GPU")
        onNodeWithTag("field").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("field").performKeyInput { pressKey(Key.NumPadEnter) }
        waitForIdle()
        assertEquals("the menu stayed open", 0, closed)
    }
}
