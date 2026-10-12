package dev.omalauncher.widgets

import dev.omalauncher.theme.pack.TypeRole
import dev.omalauncher.widgets.render.widgetRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of a look's voices a widget's text is set in. */
class WidgetVoiceTest {
    @Test fun bigTextIsTheDisplayVoiceCaptionsTheCapitalsAndTheRestTheBody() {
        assertEquals(TypeRole.Display, widgetRole("huge", null))
        assertEquals(TypeRole.Display, widgetRole("title", null))
        assertEquals(TypeRole.Mono, widgetRole("label", null))
        for (style in listOf(null, "body", "small", "muted")) assertEquals(TypeRole.Body, widgetRole(style, null))
    }

    @Test fun aWidgetCanPickAVoiceWhateverTheStyle() {
        assertEquals(TypeRole.Mono, widgetRole("title", "mono"))
        assertEquals(TypeRole.Hand, widgetRole(null, "hand"))
        assertEquals(TypeRole.Display, widgetRole("small", "display"))
        assertEquals(TypeRole.Body, widgetRole("huge", "body"))
    }

    /** The system's faces and the symbol font stay what they are in every look. */
    @Test fun theSystemsFacesAndTheSymbolsAreNoVoiceOfTheLooks() {
        for (font in listOf("sans", "serif", "icon")) assertNull(widgetRole("title", font))
    }
}
