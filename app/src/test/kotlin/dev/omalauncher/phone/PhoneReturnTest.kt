package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Back where he was once the agent is done with the phone (PhoneReturn.kt), and the list a press scrolls to find its label. */
class PhoneReturnTest {
    private val own = "dev.omalauncher.boss"

    @Test fun whatIsInFrontIsAPlaceToComeBackTo() {
        assertEquals(PhoneOrigin.Chat, phoneOrigin(own, "dev.omalauncher.oma.OmaChatActivity", own) { "p2" })
        assertEquals(PhoneOrigin.Home("p2"), phoneOrigin(own, "dev.omalauncher.MainActivity", own) { "p2" })
        assertEquals(PhoneOrigin.App("com.spotify.music"), phoneOrigin("com.spotify.music", "com.spotify.MainActivity", own) { null })
        // The launcher's passing screens and the system's windows are not: what was under them still is.
        assertNull(phoneOrigin(own, "dev.omalauncher.assist.AgentApprovalActivity", own) { null })
        assertNull(phoneOrigin(own, "dev.omalauncher.assist.AssistantPromptActivity", own) { null })
        assertNull(phoneOrigin("com.android.systemui", "x", own) { null })
        assertNull(phoneOrigin(null, null, own) { null })
    }

    @Test fun itComesBackUnlessItShouldNot() {
        val chat = PhoneOrigin.Chat
        val settings = PhoneOrigin.App("com.android.settings")
        assertEquals(PhoneBack.Bring(chat), phoneBack(chat, settings, stay = false, took = false, locked = false))
        assertTrue(phoneBack(null, settings, false, false, false) is PhoneBack.Leave)
        // "Open Spotify": the agent says the screen is for him.
        assertTrue(phoneBack(chat, settings, stay = true, took = false, locked = false) is PhoneBack.Leave)
        // He picked the phone up himself meanwhile: what he chose stays.
        assertTrue(phoneBack(chat, settings, stay = false, took = true, locked = false) is PhoneBack.Leave)
        assertTrue(phoneBack(chat, settings, stay = false, took = false, locked = true) is PhoneBack.Leave)
        assertTrue(phoneBack(chat, chat, stay = false, took = false, locked = false) is PhoneBack.Leave)
    }

    @Test fun heTakesThePhoneOnlyWhenAnotherAppComesUpLongAfterTheAgentsLastMove() {
        assertFalse(phoneTakenOver("com.android.settings", own, now = 10_000, acted = 9_000))
        assertTrue(phoneTakenOver("com.android.settings", own, now = 10_000, acted = 10_000 - PHONE_OWN_MOVE_MILLIS - 1))
        // Answering an approval in the launcher, or a shade pulled down, is not taking the phone.
        assertFalse(phoneTakenOver(own, own, now = 60_000, acted = 1_000))
        assertFalse(phoneTakenOver("com.android.systemui", own, now = 60_000, acted = 1_000))
        assertFalse(phoneTakenOver("com.android.settings", own, now = 60_000, acted = null))
    }

    private fun node(id: String, scrollable: Boolean, l: Int, t: Int, r: Int, b: Int) =
        JSONObject().put("id", id).put("scrollable", scrollable).put("bounds", JSONArray(listOf(l, t, r, b)))

    @Test fun aPressScrollsThePagesOwnListNotARowInsideIt() {
        val read = JSONObject().put("nodes", JSONArray(listOf(
            node("root", false, 0, 0, 1080, 2400),
            node("root.0.chips", true, 0, 200, 1080, 320),
            node("root.0.list", true, 0, 320, 1080, 2300),
        )))
        assertEquals("root.0.list", PhoneScreen.scroller(read))
        assertNull(PhoneScreen.scroller(JSONObject().put("nodes", JSONArray(listOf(node("root", false, 0, 0, 1, 1))))))
    }
}
