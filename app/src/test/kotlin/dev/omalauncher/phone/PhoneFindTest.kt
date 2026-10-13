package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFindTest {
    private fun node(id: String, text: String = "", description: String = "", clickable: Boolean = false, editable: Boolean = false, enabled: Boolean = true) =
        SeenNode(id, text, description, clickable, editable, enabled)

    private val chat =
        listOf(
            node("root"),
            node("root.0", clickable = true),
            node("root.0.0", text = "[ send ]"),
            node("root.1", text = "follow up on this thread...", editable = true, clickable = true),
            node("root.2", description = "Send feedback", clickable = true),
            node("root.3", text = "new thread"),
        )

    @Test fun aWholeLabelIsPreferredAndPressedOnTheControlAroundIt() {
        // "[ send ]" is the words inside the button at root.0; "Send feedback" only has it in part.
        assertEquals(Found.One("root.0"), PhoneFind.press(chat, "[ send ]"))
        assertEquals(Found.One("root.2"), PhoneFind.press(chat, "  send FEEDBACK "))
    }

    @Test fun aPartOfALabelFindsItWhenNoWholeLabelDoes() {
        assertEquals(Found.One("root.2"), PhoneFind.press(chat, "feedback"))
        // Nothing around these words takes a press: they are pressed where they stand.
        assertEquals(Found.One("root.3"), PhoneFind.press(chat, "new thread"))
    }

    @Test fun twoControlsThatAnswerAreNeverChosenBetween() {
        assertEquals(Found.Several(listOf("root.0", "root.2")), PhoneFind.press(chat, "send"))
    }

    @Test fun nothingIsFoundForWordsNotOnScreenOrNoWordsOrADisabledControl() {
        assertEquals(Found.None, PhoneFind.press(chat, "delete"))
        assertEquals(Found.None, PhoneFind.press(chat, "  "))
        assertEquals(Found.None, PhoneFind.press(chat, "s".repeat(PhoneFind.MAX_LABEL + 1)))
        assertEquals(Found.None, PhoneFind.press(listOf(node("root", text = "Pay", clickable = true, enabled = false)), "pay"))
    }

    @Test fun twoPiecesOfOneButtonAreOneControl() {
        val nodes = listOf(node("root", clickable = true), node("root.0", text = "Save"), node("root.1", description = "Save"))
        assertEquals(Found.One("root"), PhoneFind.press(nodes, "save"))
    }

    @Test fun onlyATextFieldIsFilled() {
        assertEquals(Found.One("root.1"), PhoneFind.field(chat, "follow up"))
        assertEquals(Found.None, PhoneFind.field(chat, "send"))
    }

    private fun read() =
        JSONObject().put("ok", true).put("snapshot", "s1").put(
            "nodes",
            JSONArray()
                .put(JSONObject().put("id", "root").put("text", "").put("description", "").put("package", "dev.omalauncher.boss").put("clickable", false).put("enabled", true))
                .put(JSONObject().put("id", "root.0").put("text", "").put("description", "").put("package", "dev.omalauncher.boss").put("clickable", true).put("enabled", true))
                .put(JSONObject().put("id", "root.0.0").put("text", "[ send ]").put("description", "").put("clickable", false).put("enabled", true))
                .put(JSONObject().put("id", "root.1").put("text", "").put("description", "").put("editable", true).put("clickable", true).put("enabled", true))
                .put(JSONObject().put("id", "root.2").put("text", "Done").put("description", "").put("clickable", false).put("enabled", true).put("checked", true)),
        )

    @Test fun theBriefScreenKeepsWhatIsActedOn() {
        val brief = PhoneScreen.brief(read())
        assertEquals("s1", brief.getString("snapshot"))
        assertEquals("dev.omalauncher.boss", brief.getString("package"))
        val controls = brief.getJSONArray("controls")
        // The two nodes that say nothing and take no text are left out.
        assertEquals(listOf("root.0.0", "root.1", "root.2"), (0 until controls.length()).map { controls.getJSONObject(it).getString("id") })
        assertEquals("[\"press\"]", controls.getJSONObject(0).getJSONArray("can").toString())
        assertEquals("[\"press\",\"fill\"]", controls.getJSONObject(1).getJSONArray("can").toString())
        assertEquals("[]", controls.getJSONObject(2).getJSONArray("can").toString())
        assertTrue(controls.getJSONObject(2).getBoolean("checked"))
        assertFalse(controls.getJSONObject(0).has("checked"))
    }

    @Test fun anotherAppInFrontIsRefused() {
        PhoneScreen.requireFront(read(), "")
        PhoneScreen.requireFront(read(), "dev.omalauncher.boss")
        val refused = runCatching { PhoneScreen.requireFront(read(), "com.android.settings") }.exceptionOrNull()
        assertEquals("Expected com.android.settings in front, but dev.omalauncher.boss is. Nothing was done.", refused?.message)
    }

    @Test fun wordsOnScreenAreFoundInPart() {
        assertTrue(PhoneScreen.says(read(), "send"))
        assertFalse(PhoneScreen.says(read(), "settings"))
        assertFalse(PhoneScreen.says(read(), " "))
    }
}
