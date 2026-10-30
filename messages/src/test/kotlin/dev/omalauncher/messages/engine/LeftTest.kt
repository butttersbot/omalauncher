package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftTest {
    @Test fun draftsQuotesAndPlacesSurviveASave() {
        val all = mapOf("chat-mina" to Left("bring it", "Mina: the sake?", 3, 120, 9), "chat-dad" to Left(index = 0, count = 3))
        assertEquals(all, Left.fromJson(Left.toJson(all)))
    }

    @Test fun nothingLeftIsNotSaved() {
        assertTrue(Left.fromJson(Left.toJson(mapOf("chat-mina" to Left("  ")))).isEmpty())
    }

    @Test fun aGarbledFileIsNothingLeft() {
        assertTrue(Left.fromJson("{nope").isEmpty())
    }
}
