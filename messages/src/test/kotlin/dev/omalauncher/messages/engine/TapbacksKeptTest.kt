package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TapbacksKeptTest {
    private val me = Reaction("like", "", true, "You")
    private val ana = Reaction("love", "ana", false, "Ana")
    private fun message(id: String, vararg tapbacks: String) = Message("hi", 1_000, "Ana", id = id, tapbacks = tapbacks.toList())

    @Test fun aCopyWithoutItYetKeepsTheOwnersTapback() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        val out = kept.applied("c", listOf(message("M")), 1_000).single()
        assertEquals(listOf("me:like"), out.tapbacks)
        assertEquals(listOf(me), out.reactions)
    }

    @Test fun aCopyStillWithARemovedOneDoesNotBringItBack() {
        val kept = TapbacksKept().apply { hold("c", "M", null, emptyList(), 0) }
        assertEquals(emptyList<String>(), kept.applied("c", listOf(message("M", "me:like")), 1_000).single().tapbacks)
    }

    @Test fun afterTheMacAgreesItsCopiesAreBelievedAgain() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        kept.applied("c", listOf(message("M", "me:like")), 1_000)
        assertEquals(emptyList<String>(), kept.applied("c", listOf(message("M")), 2_000).single().tapbacks)
    }

    @Test fun onlyTheOwnersTapbackIsHeldNotOthers() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        val copy = message("M", "love").copy(reactions = listOf(ana))
        val out = kept.applied("c", listOf(copy), 1_000).single()
        assertEquals(listOf("love", "me:like"), out.tapbacks)
        assertEquals(listOf(ana, me), out.reactions)
    }

    @Test fun afterItsPatienceTheMacIsBelievedSoANeverSentOneGoes() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        assertEquals(emptyList<String>(), kept.applied("c", listOf(message("M")), 60_000).single().tapbacks)
    }

    @Test fun aRefusedTapbackIsLetGoButNotALaterOne() {
        val kept = TapbacksKept()
        val first = kept.hold("c", "M", "me:like", listOf(me), 0)
        kept.hold("c", "M", "me:love", emptyList(), 1)
        kept.drop("c", "M", first)
        assertEquals(listOf("me:love"), kept.applied("c", listOf(message("M")), 2).single().tapbacks)
        val second = kept.hold("c", "M", "me:laugh", emptyList(), 3)
        kept.drop("c", "M", second)
        assertEquals(emptyList<String>(), kept.applied("c", listOf(message("M")), 4).single().tapbacks)
    }

    @Test fun aLaterTapReplacesWhatIsHeld() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0); hold("c", "M", "me:love", emptyList(), 1) }
        assertEquals(listOf("me:love"), kept.applied("c", listOf(message("M", "me:like")), 2).single().tapbacks)
    }

    @Test fun onAManyPartMessageTheTapbackStaysOnItsLastPart() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        val out = kept.applied("c", listOf(message("M"), message("M#a")), 1_000)
        assertEquals(emptyList<String>(), out[0].tapbacks)
        assertEquals(listOf("me:like"), out[1].tapbacks)
    }

    @Test fun otherMessagesAndChatsPassUntouched() {
        val kept = TapbacksKept().apply { hold("c", "M", "me:like", listOf(me), 0) }
        val other = message("N")
        assertSame(other, kept.applied("c", listOf(other), 1).single())
        assertSame(other, kept.applied("d", listOf(other), 1).single())
    }
}
