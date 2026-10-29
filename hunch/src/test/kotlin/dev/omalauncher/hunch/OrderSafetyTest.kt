package dev.omalauncher.hunch

import java.math.BigDecimal
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OrderSafetyTest {
    private val now = 1_800_000_000_000L
    private fun d(s: String) = BigDecimal(s)
    private val owner = OrderWallet("0x1111111111111111111111111111111111111111")
    private val rules = VenueOrderRules(100_000_010, "Fictional market", "Yes", d("0.01"), d("0.1"), d("0.1"), d("0.1"), now)
    private fun prepare(owner: OrderWallet, rules: VenueOrderRules, price: BigDecimal, shares: BigDecimal,
                        fees: BigDecimal, available: BigDecimal, now: Long) =
        OrderTickets.prepareFunded(owner, rules, price, shares, fees, FundingSnapshot(owner.wallet, now, null, null, available), now)
    private fun ticket() = prepare(owner, rules, d("0.30"), d("3.3"), d("0.01"), d("1"), now)
    private fun refuses(block: () -> Unit) { try { block(); fail("Must refuse") } catch (_: IllegalArgumentException) {} }
    @Test fun preservesExactSizeAndIncludesFeesInTheBalanceCheck() {
        val t = ticket()
        assertEquals(0, t.shares.compareTo(d("3.3")))
        assertEquals(0, t.maximumDebit.compareTo(d("0.9999")))
        assertEquals(0, t.maximumDebit.compareTo(t.notional.add(t.feeCeiling)))
        assertEquals("Ioc", t.timeInForce)
        assertTrue(t.fresh(now + 30_000)); assertFalse(t.fresh(now + 30_001)); assertFalse(t.fresh(now - 1))
    }
    @Test fun fundedPreparationUsesTheSameVerifiedWalletAndVenueSnapshot() {
        val funding = FundingSnapshot(owner.wallet, now, d("100"), d("1"), d("1"))
        assertTrue(OrderTickets.prepareFunded(owner, rules, d("0.30"), d("1"), d("0.01"), funding, now).maximumDebit <= d("1"))
        refuses { OrderTickets.prepareFunded(owner, rules, d("0.30"), d("1"), d("0.01"), funding.copy(venueAvailableUsdc = d("0")), now) }
        refuses { OrderTickets.prepareFunded(owner, rules, d("0.30"), d("1"), d("0.01"), funding.copy(checkedAt = now - 30_001), now) }
    }
    @Test fun neverChangesOwnerSizeToMeetVenueRulesOrBalance() {
        refuses { prepare(owner, rules.copy(minimumNotional = d("10")), d("0.30"), d("1"), d("0.01"), d("20"), now) }
        refuses { prepare(owner, rules, d("0.30"), d("2.01"), d("0"), d("20"), now) }
        refuses { prepare(owner, rules, d("0.30"), d("2"), d("0"), d("0.5"), now) }
    }
    @Test fun refusesStaleFutureMispricedAndUnverifiedOutcomeInputs() {
        for (bad in listOf(rules.copy(checkedAt = now - 30_001), rules.copy(checkedAt = now + 1), rules.copy(asset = 0), rules.copy(sizeStep = d("0")))) {
            refuses { prepare(owner, bad, d("0.30"), d("1"), d("0"), d("1"), now) }
        }
        for (price in listOf("0", "1", "0.301", "-1")) refuses { prepare(owner, rules, d(price), d("1"), d("0"), d("1"), now) }
    }
    @Test fun noApplicationCapButFeesAndExactBalanceStillApply() {
        val t = prepare(owner, rules, d("0.30"), d("1000"), d("0.01"), d("303"), now)
        assertEquals(0, t.shares.compareTo(d("1000")))
        assertEquals(0, t.maximumDebit.compareTo(d("303")))
        refuses { prepare(owner, rules, d("0.30"), d("1000"), d("0.01"), d("302.99"), now) }
        for (size in listOf("0", "-1", "0.01")) refuses { prepare(owner, rules, d("0.30"), d(size), d("0"), d("100"), now) }
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
            try { OrderJournal(file); fail("A second controller must not overwrite the journal") } catch (_: IllegalStateException) {}
            journal.close()
            val recovered = OrderJournal(file)
            val row = recovered.all().single()
            assertEquals(first.cloid, row.cloid); assertEquals(OrderStage.Unknown, row.stage)
            refuses { recovered.transition(row.id, OrderStage.Submitting, now) }
            try { recovered.review(ticket(), now); fail("Uncertain send must block another order") } catch (_: IllegalStateException) {}
            recovered.transition(row.id, OrderStage.Filled, now - 1000, "fixture-order-id", venueConfirmed=true)
            assertEquals(now, recovered.all().single().updated) // reconciliation can record truth after clock rollback
            assertEquals("fixture-order-id", recovered.all().single().venueId)
            assertEquals(2, recovered.run { review(ticket(), now + 2); all().size })
            recovered.close()
            OrderJournal(file).use { assertEquals("fixture-order-id", it.all().first().venueId) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun knownPreSendCancellationDoesNotPoisonTradingButInFlightNeedsVenueProof() {
        val dir=Files.createTempDirectory("hunch-stage-test").toFile()
        try {
            val file=dir.resolve("orders.json")
            OrderJournal(file).use { journal ->
                val first=journal.review(ticket(),now)
                journal.transition(first.id,OrderStage.Signing,now)
                journal.transition(first.id,OrderStage.Cancelled,now)
                val second=journal.review(ticket(),now)
                journal.transition(second.id,OrderStage.Signing,now)
                // A crash before Submitting is known not to have touched HTTP.
            }
            OrderJournal(file).use { journal ->
                assertTrue(journal.all().all{it.stage==OrderStage.Cancelled})
                val next=journal.review(ticket(),now)
                journal.transition(next.id,OrderStage.Signing,now)
                journal.transition(next.id,OrderStage.Submitting,now)
                refuses{journal.transition(next.id,OrderStage.Cancelled,now)}
                refuses{journal.transition(next.id,OrderStage.Rejected,now)}
                journal.transition(next.id,OrderStage.Unknown,now)
                refuses{journal.transition(next.id,OrderStage.Cancelled,now)}
                journal.transition(next.id,OrderStage.Rejected,now,venueConfirmed=true)
                assertEquals(OrderStage.Reviewed,journal.review(ticket(),now).stage)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun expiredReviewNeverSignsAndCorruptJournalNeverStartsEmpty() {
        val dir = Files.createTempDirectory("hunch-order-test").toFile()
        try {
            val file = dir.resolve("orders.json")
            val journal = OrderJournal(file)
            val first = journal.review(ticket(), now)
            refuses { journal.transition(first.id, OrderStage.Signing, now + 30_001) }
            journal.close()
            file.writeText("not-json")
            try { OrderJournal(file); fail("Corruption must block trading") } catch (_: org.json.JSONException) {}
        } finally { dir.deleteRecursively() }
    }
}
