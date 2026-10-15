package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun aFieldIsFoundByAHintThatStandsInsideIt() {
        // The app list's search field as the Fold gave it: the field says nothing, its hint is a node within.
        val list = listOf(node("root"), node("root.3", editable = true, clickable = true), node("root.3.0", text = "search apps", clickable = true), node("root.4", text = "search tips"))
        assertEquals(Found.One("root.3"), PhoneFind.field(list, "search apps"))
        assertEquals(Found.One("root.3"), PhoneFind.field(list, "apps"))
        // Words outside any field name none.
        assertEquals(Found.None, PhoneFind.field(list, "tips"))
    }

    // The Oma chat's composer as the real launcher gave it on 2026-10-14: a Compose BasicTextField whose own text is
    // empty, its placeholder a node of its own inside it that takes no text, next to a tab that says the same name.
    private val composer =
        listOf(
            node("root"),
            node("root.0.0.0.4", clickable = true),
            node("root.0.0.0.4.0", text = "Back up projects before phone…"),
            node("root.0.0.0.5", clickable = false),
            node("root.0.0.0.5.0", editable = true, clickable = true),
            node("root.0.0.0.5.0.0", text = "to Back up projects before phone…"),
            node("root.0.0.0.6", clickable = true),
            node("root.0.0.0.6.0", text = "[ send ]"),
        )

    @Test fun aPlaceholderInsideAComposeFieldFillsTheField() {
        assertEquals(Found.One("root.0.0.0.5.0"), PhoneFind.field(composer, "to Back up projects before phone…"))
        // In part, the placeholder and the tab both say it; only the placeholder stands in a field.
        assertEquals(Found.One("root.0.0.0.5.0"), PhoneFind.field(composer, "back up projects"))
        assertEquals(Found.None, PhoneFind.field(composer, "[ send ]"))
    }

    @Test fun aFieldThatSaysTheLabelItselfIsFilledThoughItsBoxHoldsAnother() {
        val box = listOf(node("root"), node("root.1", clickable = true), node("root.1.0", text = "Ann", editable = true), node("root.1.1", editable = true))
        assertEquals(Found.One("root.1.0"), PhoneFind.field(box, "ann"))
    }

    @Test fun aHintBesideItsFieldFillsTheOneFieldInTheBoxAroundThem() {
        // A View text field with its hint drawn as a sibling, both inside one control that takes a press.
        val form =
            listOf(
                node("root", clickable = true),
                node("root.0", clickable = true),
                node("root.0.0", text = "Email"),
                node("root.0.1", editable = true),
                node("root.1", clickable = true),
                node("root.1.0", text = "Name or number"),
                node("root.1.1", editable = true),
                node("root.1.2", editable = true),
                node("root.2", text = "Password"),
                node("root.3", editable = true),
            )
        assertEquals(Found.One("root.0.1"), PhoneFind.field(form, "email"))
        // Two fields in the box: neither is guessed at.
        assertEquals(Found.None, PhoneFind.field(form, "name or number"))
        // Nothing but the window's root takes a press around it: no box, so no field.
        assertEquals(Found.None, PhoneFind.field(form, "password"))
    }

    @Test fun aFillIsOnlyDoneWhenTheFieldIsReadBackHoldingTheText() {
        val filled = composer.map { if (it.editable) it.copy(text = "Back up all of it ") else it }
        assertTrue(PhoneFind.holds(filled, "root.0.0.0.5.0", " Back up all of it"))
        // Android said yes and the field stayed empty, with only its placeholder showing.
        assertFalse(PhoneFind.holds(composer, "root.0.0.0.5.0", "Back up all of it"))
        // The placeholder saying the words is not the field holding them.
        assertFalse(PhoneFind.holds(composer, "root.0.0.0.5.0.0", "to Back up projects before phone…"))
        assertFalse(PhoneFind.holds(filled, "root.0.0.0.6", "Back up all of it"))
    }

    @Test fun aFillThatDidNotLandIsNotOkAndAPressIs() {
        fun json(text: String) =
            JSONObject().put("snapshot", "s2").put("nodes", JSONArray()
                .put(JSONObject().put("id", "root").put("text", "").put("package", "dev.omalauncher.boss").put("enabled", true))
                .put(JSONObject().put("id", "root.1").put("text", text).put("editable", true).put("clickable", true).put("enabled", true)))
        val missed = PhoneScreen.after(json(""), "fill", "root.1", "hello")
        assertFalse(missed.getBoolean("ok"))
        assertTrue(missed.getString("error").startsWith("The text did not land in the field root.1."))
        // The answer still carries the screen, so the field can be typed into by its snapshot and id.
        assertEquals("s2", missed.getString("snapshot"))
        val landed = PhoneScreen.after(json("hello"), "fill", "root.1", "hello")
        assertTrue(landed.getBoolean("ok"))
        assertEquals("fill", landed.getString("did"))
        assertEquals("root.1", landed.getString("on"))
        assertTrue(PhoneScreen.after(json(""), "press", "root.1", null).getBoolean("ok"))
    }

    @Test fun noLabelIsTheOnlyTextFieldAndNeverOneOfTwo() {
        assertEquals(Found.One("root.1"), PhoneFind.field(chat, ""))
        assertEquals(Found.Several(listOf("a", "b")), PhoneFind.field(listOf(node("a", editable = true), node("b", editable = true)), " "))
        assertEquals(Found.None, PhoneFind.field(listOf(node("a", text = "x")), ""))
    }

    @Test fun aButtonSaidTwiceIsListedOnceAndASilentIconNotAtAll() {
        fun json(id: String, text: String, clickable: Boolean = false) = JSONObject().put("id", id).put("text", text).put("description", "").put("clickable", clickable).put("enabled", true)
        val read = JSONObject().put("snapshot", "s").put("nodes", JSONArray()
            .put(json("root", "")).put(json("root.0", "", clickable = true)).put(json("root.0.0", "btop")).put(json("root.0.1", "btop"))
            .put(json("root.1", "\uE88A")).put(json("root.2", "\u2191", clickable = true)).put(json("root.3", "ALL APPS")))
        val controls = PhoneScreen.brief(read).getJSONArray("controls")
        assertEquals(listOf("root.0.0", "root.2", "root.3"), (0 until controls.length()).map { controls.getJSONObject(it).getString("id") })
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

    // The Oma chat while an answer streams in: the words above grow, so what is below them moves down the screen.
    private fun seen(path: String, text: String? = null, description: String? = null, viewId: String? = null, className: String = "android.widget.Button", top: Int = 1000) =
        SeenControl(path, "dev.omalauncher.boss", viewId, className, text, description, listOf(0, top, 200, top + 80))

    private val stop = seen("root.0.4", text = "[ stop ]")

    @Test fun aControlThatMovedIsFoundAgainByWhatItIs() {
        // Only its bounds changed: the same control, at the same place in the tree.
        assertEquals("root.0.4", PhoneRefind.refind(stop, listOf(seen("root.0.3", text = "Thinking…", className = "android.widget.TextView"), seen("root.0.4", text = "[ stop ]", top = 1240))))
        // A step line came in above it, so it is a child further on as well as further down.
        assertEquals("root.0.5", PhoneRefind.refind(stop, listOf(seen("root.0.4", text = "Reading PhoneService.kt", className = "android.widget.TextView"), seen("root.0.5", text = "[ stop ]", top = 1320))))
        // A view id is part of what it is: one of the same words with another id is another control.
        val send = seen("root.2", text = "Send", viewId = "app:id/send")
        assertEquals("root.3", PhoneRefind.refind(send, listOf(seen("root.2", text = "Send", viewId = "app:id/share"), seen("root.3", text = "Send", viewId = "app:id/send", top = 1400))))
    }

    @Test fun aSecondControlWithTheSameWordsIsNeverGuessedBetween() {
        // A new answer brought its own [ copy ]: the old one is still there, but which is which is not known.
        val copy = seen("root.0.2.1", text = "[ copy ]", top = 600)
        assertNull(PhoneRefind.refind(copy, listOf(seen("root.0.2.1", text = "[ copy ]", top = 500), seen("root.0.3.1", text = "[ copy ]", top = 900))))
    }

    @Test fun aControlThatIsGoneOrChangedIsNotFound() {
        assertNull(PhoneRefind.refind(stop, emptyList()))
        // The answer ended: where [ stop ] was is now [ send ], which is another control however near it is.
        assertNull(PhoneRefind.refind(stop, listOf(seen("root.0.4", text = "[ send ]"))))
        // Other words, or another kind of control or another app, are another control too.
        assertNull(PhoneRefind.refind(stop, listOf(seen("root.0.4", text = "[ stop ]", className = "android.widget.TextView"))))
        assertNull(PhoneRefind.refind(stop, listOf(stop.copy(packageName = "com.android.settings"))))
    }

    @Test fun aControlThatSaysNothingIsOnlyFoundWhereItStood() {
        // The chat's composer says nothing of its own and has no view id: its place in the tree is all it has.
        val field = seen("root.0.5.0", className = "android.widget.EditText", top = 1800)
        assertEquals("root.0.5.0", PhoneRefind.refind(field, listOf(seen("root.0.5.0", className = "android.widget.EditText", top = 1700))))
        assertNull(PhoneRefind.refind(field, listOf(seen("root.0.6.0", className = "android.widget.EditText", top = 1800))))
        // A field elsewhere that says nothing either does not make the one where it stood any less its own.
        assertEquals("root.0.5.0", PhoneRefind.refind(field, listOf(seen("root.0.2.0", className = "android.widget.EditText"), seen("root.0.5.0", className = "android.widget.EditText"))))
    }
}
