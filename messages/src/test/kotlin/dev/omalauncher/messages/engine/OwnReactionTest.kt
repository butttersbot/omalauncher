package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnReactionTest {
    private fun copy(vararg tapbacks: Pair<String, Boolean>) = MacWire.msg(JSONObject().put("id", "M").put("tapbacks", JSONArray(tapbacks.map { (sender, fromMe) ->
        JSONObject().put("kind", "emphasize").put("sender", sender).put("fromMe", fromMe)
    })))

    @After fun clear() { MacWire.ownHandles = emptySet() }

    @Test fun anotherOfHisHandlesIsHim() {
        MacWire.ownHandles = setOf("+15550000000", "owner@example.test")
        val m = copy("Owner@Example.test" to false)
        assertEquals(listOf("me:emphasize"), m.tapbacks)
        assertTrue(m.reactions.single().fromMe)
    }

    @Test fun underTwoOfHisHandlesHeIsOnePerson() {
        MacWire.ownHandles = setOf("+15550000000", "owner@example.test")
        val m = copy("owner@example.test" to false, "+15550000000" to true, "+15551112222" to false)
        assertEquals(listOf("me:emphasize", "emphasize"), m.tapbacks)
        assertEquals(listOf(true, false), m.reactions.map { it.fromMe })
    }

    @Test fun someoneElseStaysSomeoneElse() {
        MacWire.ownHandles = setOf("+15550000000")
        val m = copy("+15551112222" to false)
        assertEquals(listOf("emphasize"), m.tapbacks)
        assertFalse(m.reactions.single().fromMe)
    }

    @Test fun whatTheMacSaysIsHisStaysHis() {
        assertEquals(listOf("me:emphasize"), copy("" to true).tapbacks)
    }

    @Test fun withTheMacsHeldTapbackThereIsOneChip() {
        MacWire.ownHandles = setOf("owner@example.test")
        val kept = TapbacksKept().apply { hold("c", "M", "me:emphasize", emptyList(), 0) }
        val parsed = copy("owner@example.test" to false)
        val incoming = Message("hi", 1, "Ana", id = "M", tapbacks = parsed.tapbacks, reactions = parsed.reactions)
        assertEquals(listOf("me:emphasize"), kept.applied("c", listOf(incoming), 1).single().tapbacks)
    }
}
