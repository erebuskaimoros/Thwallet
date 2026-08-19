package io.horizontalsystems.walletkit.chain.xrp

import java.math.BigDecimal
import java.math.RoundingMode

object XrpAmount {
    private const val decimals = 6
    private const val maxDrops = 100_000_000_000_000_000L

    fun toDropsExact(value: BigDecimal): Long {
        require(value.signum() >= 0) { "XRP amount must not be negative" }
        val drops = try {
            value.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("XRP supports at most six decimal places", error)
        }
        require(drops <= maxDrops) { "XRP amount exceeds the protocol supply bound" }
        return drops
    }

    fun fromDrops(drops: Long): BigDecimal {
        require(drops >= 0) { "Drops must not be negative" }
        return BigDecimal.valueOf(drops, decimals).stripTrailingZeros()
    }
}
