package dev.omalauncher.messages.engine

import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacRefusalTest {
    @Test fun whatTheMacCannotVouchForIsNotConfirmed() {
        for ((status, error) in listOf(
            409 to "client_id_conflict", 409 to "request_outcome_unknown_do_not_resend",
            503 to "request_state_unavailable", 502 to "request_response_too_large_do_not_resend",
        )) {
            val refusal = MacRefusal.of(status, error, "error")
            assertEquals(error, Outcome.NotConfirmed, refusal.outcome)
            assertTrue(error, refusal.theMacs)
            assertEquals(MacRefusal.CHECK, refusal.message)
        }
    }

    @Test fun aFullLedgerSentNothing() {
        val full = MacRefusal.of(503, "request_ledger_full", "error")
        assertEquals(Outcome.Refused, full.outcome)
        assertEquals(MacRefusal.FULL, full.message)
    }

    @Test fun otherAnswersAreRefusalsInTheWordsTheyHadBefore() {
        assertEquals("Sending is off on your Mac.", MacRefusal.of(403, "read only", "read_only").message)
        assertEquals("Your Mac didn't take this phone's token.", MacRefusal.of(401, "no", "unauthorized").message)
        assertEquals("messages said no", MacRefusal.of(502, "messages said no", "messages_failed").message)
        assertEquals("Your Mac answered 500.", MacRefusal.of(500, null, null).message)
        assertEquals(Outcome.Refused, MacRefusal.of(502, "messages said no", "messages_failed").outcome)
    }

    @Test fun noAnswerIsOnlyWhatNeverReachedAnAnswer() {
        assertEquals(Outcome.NoAnswer, MacRefusal.outcomeOf(EOFException()))
        assertEquals(Outcome.Refused, MacRefusal.outcomeOf(MacRefusal.of(502, "no", "error")))
    }

    @Test fun notConfirmedLeadsWithThatInsteadOfSayingItFailed() {
        assertEquals("Not confirmed: ${MacRefusal.CHECK}", ActionFailure(MacRefusal.CHECK, Outcome.NotConfirmed).told("Not sent"))
        assertEquals("Not sent: locked", ActionFailure("locked", Outcome.Refused).told("Not sent"))
    }

    @Test fun aFailedSendRemembersHowItFailedAcrossARestart() {
        fun failedAs(outcome: Outcome): Message {
            val book = ConversationBook().take(SeenConversation("c", "Mina", false, emptyList()))
                .waiting("c", "id1", "hello", 1_000).failed("c", "id1", outcome)
            return ConversationBook.fromJson(book.toJson())["c"]!!.messages.single()
        }
        with(failedAs(Outcome.NoAnswer)) { assertTrue(failed); assertTrue(unanswered); assertFalse(notConfirmed); assertEquals("c:id1", id) }
        with(failedAs(Outcome.NotConfirmed)) { assertTrue(failed); assertFalse(unanswered); assertTrue(notConfirmed) }
        with(failedAs(Outcome.Refused)) { assertTrue(failed); assertFalse(unanswered); assertFalse(notConfirmed) }
    }

    @Test fun theMacsCopyClearsAFailure() {
        val book = ConversationBook().take(SeenConversation("c", "Mina", false, emptyList()))
            .waiting("c", "id1", "hello", 1_000).failed("c", "id1", Outcome.NotConfirmed)
        val echoed = book.fromMac("c", null, null, listOf(Message("hello", 1_200, "", id = "M1", clientId = "id1")), counting = false)
        with(echoed["c"]!!.messages.single()) { assertEquals("M1", id); assertFalse(failed); assertFalse(notConfirmed) }
    }
}
