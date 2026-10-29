package dev.omalauncher.hunch

import java.math.BigDecimal

/** No defaults for venue rules or owner consent: live adapters must supply verified values. */
internal data class VenueOrderRules(
    val asset: Int, val market: String, val side: String,
    val priceTick: BigDecimal, val sizeStep: BigDecimal,
    val minimumNotional: BigDecimal, val minimumShares: BigDecimal,
    val checkedAt: Long,
)
internal data class OrderWallet(val wallet: String)
internal data class OrderTicket(
    val wallet: String, val asset: Int, val market: String, val side: String,
    val limitPrice: BigDecimal, val shares: BigDecimal, val notional: BigDecimal,
    val feeCeiling: BigDecimal, val maximumDebit: BigDecimal, val reviewedAt: Long,
) {
    val timeInForce = "Ioc"
    fun fresh(now: Long) = now >= reviewedAt && now - reviewedAt <= 30_000
}

/** Buy-only limit IOC for the exact owner-entered size. No application spending caps. */
internal object OrderTickets {
    fun prepareFunded(owner: OrderWallet, rules: VenueOrderRules, price: BigDecimal,
                      shares: BigDecimal, feeRateCeiling: BigDecimal, funding: FundingSnapshot, now: Long): OrderTicket {
        val ticket = prepare(owner, rules, price, shares, feeRateCeiling, now)
        val readiness = FundingChecks.check(funding, owner.wallet, ticket.maximumDebit, now)
        require(readiness.tradingCashReady) { readiness.message }
        return ticket
    }
    private fun prepare(owner: OrderWallet, rules: VenueOrderRules, price: BigDecimal,
                shares: BigDecimal, feeRateCeiling: BigDecimal, now: Long): OrderTicket {
        require(WalletAmounts.address(owner.wallet)) { "Verify the signing wallet" }
        require(rules.asset >= 100_000_000 && rules.market.isNotBlank() && rules.market.length <= 512 && rules.side.isNotBlank() && rules.side.length <= 128) { "Verify the outcome and side" }
        require(now >= rules.checkedAt && now - rules.checkedAt <= 30_000) { "Refresh market rules and quote" }
        require(rules.priceTick.signum() > 0 && rules.sizeStep.signum() > 0 && rules.minimumNotional.signum() >= 0 && rules.minimumShares.signum() >= 0)
        require(price.signum() > 0 && price < BigDecimal.ONE && price.remainder(rules.priceTick).signum() == 0) { "Price does not match venue rules" }
        require(feeRateCeiling.signum() >= 0 && feeRateCeiling <= BigDecimal.ONE) { "Verify fees" }
        require(shares.signum() > 0 && shares.remainder(rules.sizeStep).signum() == 0) { "Size does not match venue rules" }
        val notional = shares.multiply(price)
        require(shares >= rules.minimumShares && notional >= rules.minimumNotional) { "Size is below the venue minimum; choose a new size" }
        val fees = notional.multiply(feeRateCeiling)
        val debit = notional.add(fees)
        return OrderTicket(owner.wallet.lowercase(), rules.asset, rules.market, rules.side, price, shares, notional, fees, debit, now)
    }
}
