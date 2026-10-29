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
internal data class OrderWallet(val wallet: String)
internal data class OrderTicket(
    val wallet: String, val asset: Int, val market: String, val side: String,
    val limitPrice: BigDecimal, val shares: BigDecimal, val notional: BigDecimal,
    val feeCeiling: BigDecimal, val maximumDebit: BigDecimal, val reviewedAt: Long,
    val buy: Boolean = true, val timeInForce: String = "Ioc", val testnet: Boolean = false,
    val feeRateCeiling: BigDecimal = BigDecimal.ZERO,
) {
    // Sale fees rise with an improved fill price, so subtracting the global fee
    // ceiling from limit proceeds would falsely reject low-price owned shares.
    val minimumProceeds: BigDecimal get() = if (buy) BigDecimal.ZERO else
        notional.multiply(BigDecimal.ONE.subtract(feeRateCeiling)).setScale(8,RoundingMode.DOWN)
    fun fresh(now: Long) = now >= reviewedAt && now - reviewedAt <= 30_000
}

/** Buy or owned-share sell, IOC/GTC, for the exact owner-entered size. No application spending caps. */
internal object OrderTickets {
    fun prepareFunded(owner: OrderWallet, rules: VenueOrderRules, price: BigDecimal,
                      shares: BigDecimal, feeRateCeiling: BigDecimal, funding: FundingSnapshot, now: Long,
                      buy: Boolean = true, timeInForce: String = "Ioc", testnet: Boolean = false,
                      availableShares: BigDecimal? = null): OrderTicket {
        val ticket = prepare(owner, rules, price, shares, feeRateCeiling, now, buy, timeInForce, testnet)
        if (buy) {
            val readiness = FundingChecks.check(funding, owner.wallet, ticket.maximumDebit, now)
            require(readiness.tradingCashReady) { readiness.message }
        } else {
            require(funding.wallet.equals(owner.wallet, true) && now >= funding.checkedAt && now - funding.checkedAt <= 30_000) { "Refresh the signing wallet balances" }
            require(availableShares != null && availableShares >= shares) { "Not enough available outcome shares; selling never opens a short" }
            require(feeRateCeiling < BigDecimal.ONE) { "Proceeds must cover fees" }
        }
        return ticket
    }
    private fun prepare(owner: OrderWallet, rules: VenueOrderRules, price: BigDecimal,
                shares: BigDecimal, feeRateCeiling: BigDecimal, now: Long, buy: Boolean, timeInForce: String, testnet: Boolean): OrderTicket {
        require(timeInForce in setOf("Ioc", "Gtc")) { "Choose IOC or a good-until-cancelled limit" }
        require(WalletAmounts.address(owner.wallet)) { "Verify the signing wallet" }
        require(rules.asset >= 100_000_000 && rules.market.isNotBlank() && rules.market.length <= 512 && rules.side.isNotBlank() && rules.side.length <= 128) { "Verify the outcome and side" }
        require(now >= rules.checkedAt && now - rules.checkedAt <= 30_000) { "Refresh market rules and quote" }
        require(rules.priceTick.signum() > 0 && rules.sizeStep.signum() > 0 && rules.minimumNotional.signum() >= 0 && rules.minimumShares.signum() >= 0)
        require(price.signum() > 0 && price < BigDecimal.ONE && price.remainder(rules.priceTick).signum() == 0) { "Price does not match venue rules" }
        require(feeRateCeiling.signum() >= 0 && feeRateCeiling <= BigDecimal.ONE) { "Verify fees" }
        require(shares.signum() > 0 && shares.remainder(rules.sizeStep).signum() == 0) { "Size does not match venue rules" }
        val notional = shares.multiply(price)
        require(shares >= rules.minimumShares && notional >= rules.minimumNotional) { "Size is below the venue minimum; choose a new size" }
        val fees = (if (buy) notional else shares).multiply(feeRateCeiling).setScale(8,RoundingMode.CEILING)
        val debit = if (buy) notional.add(fees) else BigDecimal.ZERO
        return OrderTicket(owner.wallet.lowercase(), rules.asset, rules.market, rules.side, price, shares, notional, fees, debit, now, buy, timeInForce, testnet, feeRateCeiling)
    }
}
