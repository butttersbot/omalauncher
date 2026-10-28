package dev.omalauncher.hunch

import java.math.BigDecimal
import java.math.RoundingMode

/** No defaults for venue rules or owner consent: live adapters must supply verified values. */
internal data class VenueOrderRules(
    val asset: Int, val market: String, val side: String,
    val priceTick: BigDecimal, val sizeStep: BigDecimal,
    val minimumNotional: BigDecimal, val minimumShares: BigDecimal,
    val checkedAt: Long,
)
internal data class OwnerOrderLimit(val wallet: String, val maximumLoss: BigDecimal)
internal data class OrderTicket(
    val wallet: String, val asset: Int, val market: String, val side: String,
    val limitPrice: BigDecimal, val shares: BigDecimal, val notional: BigDecimal,
    val feeCeiling: BigDecimal, val maximumDebit: BigDecimal, val reviewedAt: Long,
) {
    val timeInForce = "Ioc"
    fun fresh(now: Long) = now >= reviewedAt && now - reviewedAt <= 30_000
}

/** Buy-only, limit IOC. Fees and the owner's loss cap include a partial fill; never round size upward. */
internal object OrderTickets {
    fun prepareFunded(owner: OwnerOrderLimit, rules: VenueOrderRules, price: BigDecimal,
                      budget: BigDecimal, feeRateCeiling: BigDecimal, funding: FundingSnapshot, now: Long): OrderTicket {
        val readiness = FundingChecks.check(funding, owner.wallet, budget, now)
        require(readiness.tradingCashReady) { readiness.message }
        return prepare(owner, rules, price, budget, feeRateCeiling, checkNotNull(funding.venueAvailableUsdc), now)
    }
    fun prepare(owner: OwnerOrderLimit, rules: VenueOrderRules, price: BigDecimal,
                budget: BigDecimal, feeRateCeiling: BigDecimal, available: BigDecimal, now: Long): OrderTicket {
        require(WalletAmounts.address(owner.wallet)) { "Verify the signing wallet" }
        require(owner.maximumLoss.signum() > 0 && budget.signum() > 0 && budget <= owner.maximumLoss) { "Owner's loss limit exceeded" }
        require(available.signum() >= 0 && budget <= available) { "Not enough verified trading cash" }
        require(rules.asset >= 100_000_000 && rules.market.isNotBlank() && rules.market.length <= 512 && rules.side.isNotBlank() && rules.side.length <= 128) { "Verify the outcome and side" }
        require(now >= rules.checkedAt && now - rules.checkedAt <= 30_000) { "Refresh market rules and quote" }
        require(rules.priceTick.signum() > 0 && rules.sizeStep.signum() > 0 && rules.minimumNotional.signum() >= 0 && rules.minimumShares.signum() >= 0)
        require(price.signum() > 0 && price < BigDecimal.ONE && price.remainder(rules.priceTick).signum() == 0) { "Price does not match venue rules" }
        require(feeRateCeiling.signum() >= 0 && feeRateCeiling <= BigDecimal.ONE) { "Verify fees" }
        val unitCost = price.multiply(BigDecimal.ONE.add(feeRateCeiling)).multiply(rules.sizeStep)
        val steps = budget.divide(unitCost, 0, RoundingMode.DOWN)
        val shares = steps.multiply(rules.sizeStep)
        val notional = shares.multiply(price)
        require(shares.signum() > 0 && shares >= rules.minimumShares && notional >= rules.minimumNotional) { "Venue minimum exceeds this budget; ask the owner, never increase it" }
        val fees = notional.multiply(feeRateCeiling)
        val debit = notional.add(fees)
        require(debit <= budget && debit <= owner.maximumLoss && debit <= available)
        return OrderTicket(owner.wallet.lowercase(), rules.asset, rules.market, rules.side, price, shares, notional, fees, debit, now)
    }
}
