package dev.omalauncher.hunch

import java.math.BigDecimal

/** Read-only funding checkpoint. Wallet balance is never confused with spendable venue cash. */
internal data class FundingSnapshot(
    val wallet: String, val checkedAt: Long,
    val arbitrumUsdc: BigDecimal?, val arbitrumEth: BigDecimal?,
    val venueAvailableUsdc: BigDecimal?,
)
internal data class FundingReadiness(val tradingCashReady: Boolean, val message: String)
internal object FundingChecks {
    fun check(snapshot: FundingSnapshot, signingWallet: String, requiredMaximumDebit: BigDecimal, now: Long): FundingReadiness {
        require(requiredMaximumDebit.signum() > 0)
        if (!WalletAmounts.address(signingWallet) || !snapshot.wallet.equals(signingWallet, true))
            return FundingReadiness(false, "Verify the same wallet is selected for funding and signing.")
        if (now < snapshot.checkedAt || now - snapshot.checkedAt > 30_000)
            return FundingReadiness(false, "Refresh balances before reviewing an order.")
        val available = snapshot.venueAvailableUsdc
        if (available == null || available.signum() < 0)
            return FundingReadiness(false, "Trading cash could not be verified. No order is ready.")
        if (available >= requiredMaximumDebit)
            return FundingReadiness(true, "Venue trading cash covers this maximum debit. Review market rules and fees next.")
        if (snapshot.arbitrumUsdc == null || snapshot.arbitrumEth == null)
            return FundingReadiness(false, "Trading cash is insufficient; wallet balances could not be verified.")
        if (snapshot.arbitrumUsdc.signum() < 0 || snapshot.arbitrumEth.signum() < 0)
            return FundingReadiness(false, "Invalid wallet balance. Refresh before funding.")
        return FundingReadiness(false, "Arbitrum wallet funds are not venue trading cash. The owner must choose and confirm the venue's funding route, then refresh until credited.")
    }
}
