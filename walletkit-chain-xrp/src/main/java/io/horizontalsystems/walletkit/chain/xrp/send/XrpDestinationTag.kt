package io.horizontalsystems.walletkit.chain.xrp.send

object XrpDestinationTag {
    fun parse(value: String): UInt? {
        if (value.isBlank()) return null
        require(value.all(Char::isDigit)) { "Destination tag must be an unsigned whole number" }
        return value.toUIntOrNull()
            ?: throw IllegalArgumentException("Destination tag must be between 0 and 4294967295")
    }
}
