package dev.omalauncher.hunch

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class FundingReadinessTest {
    private val wallet = "0x1111111111111111111111111111111111111111"
    private val snapshot = FundingSnapshot(wallet, 1000, BigDecimal("100"), BigDecimal("0.01"), BigDecimal.ZERO)
    @Test fun arbitrumFundsNeverCountAsVenueTradingCash() {
        assertFalse(FundingChecks.check(snapshot, wallet, BigDecimal.ONE, 1000).tradingCashReady)
        assertTrue(FundingChecks.check(snapshot.copy(venueAvailableUsdc = BigDecimal.ONE), wallet, BigDecimal.ONE, 1000).tradingCashReady)
    }
    @Test fun unknownStaleFutureAndWrongWalletCannotPassFundingCheckpoint() {
        assertFalse(FundingChecks.check(snapshot.copy(venueAvailableUsdc = null), wallet, BigDecimal.ONE, 1000).tradingCashReady)
        val funded = snapshot.copy(venueAvailableUsdc = BigDecimal.TEN)
        for (now in listOf(999L, 31_001L)) assertFalse(FundingChecks.check(funded, wallet, BigDecimal.ONE, now).tradingCashReady)
        assertFalse(FundingChecks.check(funded, "0x2222222222222222222222222222222222222222", BigDecimal.ONE, 1000).tradingCashReady)
    }
}
