package dev.omalauncher.hunch

import java.math.BigDecimal
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OrderSafetyTest {
    private val now = 1_800_000_000_000L
    private fun d(s: String) = BigDecimal(s)
    private val owner = OwnerOrderLimit("0x1111111111111111111111111111111111111111", d("1"))
    private val rules = VenueOrderRules(100_000_010, "Fictional market", "Yes", d("0.01"), d("0.1"), d("0.1"), d("0.1"), now)
    private fun ticket() = OrderTickets.prepare(owner, rules, d("0.30"), d("1"), d("0.01"), d("1"), now)
    private fun refuses(block: () -> Unit) { try { block(); fail("Must refuse") } catch (_: IllegalArgumentException) {} }
    @Test fun roundsSizeDownAndIncludesFeesInsideTheOwnersLimit() {
        val t = ticket()
        assertEquals(0, t.shares.compareTo(d("3.3")))
        assertTrue(t.maximumDebit <= owner.maximumLoss)
        assertEquals(0, t.maximumDebit.compareTo(t.notional.add(t.feeCeiling)))
        assertEquals("Ioc", t.timeInForce)
        assertTrue(t.fresh(now + 30_000)); assertFalse(t.fresh(now + 30_001)); assertFalse(t.fresh(now - 1))
    }
    @Test fun neverIncreasesBudgetToMeetVenueMinimum() {
        refuses { OrderTickets.prepare(owner, rules.copy(minimumNotional = d("10")), d("0.30"), d("1"), d("0.01"), d("20"), now) }
        refuses { OrderTickets.prepare(owner, rules, d("0.30"), d("2"), d("0"), d("20"), now) }
        refuses { OrderTickets.prepare(owner, rules, d("0.30"), d("1"), d("0"), d("0.5"), now) }
    }
    @Test fun refusesStaleFutureMispricedAndUnverifiedOutcomeInputs() {
        for (bad in listOf(rules.copy(checkedAt = now - 30_001), rules.copy(checkedAt = now + 1), rules.copy(asset = 0), rules.copy(sizeStep = d("0")))) {
            refuses { OrderTickets.prepare(owner, bad, d("0.30"), d("1"), d("0"), d("1"), now) }
        }
        for (price in listOf("0", "1", "0.301", "-1")) refuses { OrderTickets.prepare(owner, rules, d(price), d("1"), d("0"), d("1"), now) }
    }
    @Test fun persistsClientIdBeforeSendAndRecoveryRequiresReconciliation() {
        val dir = Files.createTempDirectory("hunch-order-test").toFile()
        try {
            val file = dir.resolve("orders.json")
            val journal = OrderJournal(file)
            val first = journal.review(ticket(), now)
            assertTrue(Regex("0x[0-9a-f]{32}").matches(first.cloid))
            journal.transition(first.id, OrderStage.Signing, now)
            journal.transition(first.id, OrderStage.Submitting, now)
            assertTrue(file.readText().contains("Submitting"))
            val recovered = OrderJournal(file)
            val row = recovered.all().single()
            assertEquals(first.cloid, row.cloid); assertEquals(OrderStage.Unknown, row.stage)
            refuses { recovered.transition(row.id, OrderStage.Submitting, now) }
            try { recovered.review(ticket(), now); fail("Uncertain send must block another order") } catch (_: IllegalStateException) {}
            recovered.transition(row.id, OrderStage.Filled, now + 1, "fixture-order-id")
            assertEquals("fixture-order-id", OrderJournal(file).all().single().venueId)
            assertEquals(2, recovered.run { review(ticket(), now + 2); all().size })
        } finally { dir.deleteRecursively() }
    }
    @Test fun expiredReviewNeverSignsAndCorruptJournalNeverStartsEmpty() {
        val dir = Files.createTempDirectory("hunch-order-test").toFile()
        try {
            val file = dir.resolve("orders.json")
            val journal = OrderJournal(file)
            val first = journal.review(ticket(), now)
            refuses { journal.transition(first.id, OrderStage.Signing, now + 30_001) }
            file.writeText("not-json")
            try { OrderJournal(file); fail("Corruption must block trading") } catch (_: org.json.JSONException) {}
        } finally { dir.deleteRecursively() }
    }
}
