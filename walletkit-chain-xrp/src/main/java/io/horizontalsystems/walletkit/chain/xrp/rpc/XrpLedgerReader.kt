package io.horizontalsystems.walletkit.chain.xrp.rpc

private fun requireProtocolDrops(name: String, drops: Long, allowZero: Boolean = false) {
    if (drops < 0 || (!allowZero && drops == 0L)) {
        throw XrpProtocolException("XRP $name must be ${if (allowZero) "non-negative" else "positive"}")
    }
    if (drops > XRP_MAX_NATIVE_DROPS) {
        throw XrpProtocolException("XRP $name exceeds protocol supply")
    }
}

class XrpFeePolicy(val maxFeeDrops: Long) {
    init {
        require(maxFeeDrops > 0) { "Maximum XRP fee must be positive" }
        require(maxFeeDrops <= XRP_MAX_NATIVE_DROPS) { "Maximum XRP fee exceeds protocol supply" }
    }

    fun select(quote: XrpFeeQuote, recommendedFeeDrops: Long? = null): Long {
        listOf(
            "base fee" to quote.baseFeeDrops,
            "minimum fee" to quote.minimumFeeDrops,
            "median fee" to quote.medianFeeDrops,
            "open-ledger fee" to quote.openLedgerFeeDrops,
        ).forEach { (name, drops) -> requireProtocolDrops(name, drops) }
        recommendedFeeDrops?.let {
            requireProtocolDrops("recommended fee", it)
        }
        val selected = maxOf(
            quote.baseFeeDrops,
            quote.minimumFeeDrops,
            quote.medianFeeDrops,
            quote.openLedgerFeeDrops,
            recommendedFeeDrops ?: 0,
        )
        if (selected > maxFeeDrops) throw XrpFeeTooHighException(selected, maxFeeDrops)
        return selected
    }
}

class XrpLedgerReader(
    private val rpc: XrpRpc,
    private val feePolicy: XrpFeePolicy
) {
    suspend fun snapshot(address: String): XrpAccountSnapshot {
        val server = rpc.serverState()
        requireMainnet(server)
        val account = rpc.accountState(address, server.validatedLedgerIndex)
            ?: throw XrpInactiveAccountException(address)
        requirePinned(account, server.validatedLedgerIndex)
        val feeQuote = rpc.feeQuote()
        return sourceSnapshot(server, account, feeQuote, null)
    }

    suspend fun destinationState(address: String, destinationTag: UInt?): XrpDestinationState {
        val server = rpc.serverState()
        requireMainnet(server)
        val account = rpc.accountState(address, server.validatedLedgerIndex)
            ?: return XrpDestinationState(
                active = false,
                minimumAmountDrops = server.reserveBaseDrops,
                requireDestinationTag = false
            )
        requirePinned(account, server.validatedLedgerIndex)
        if (account.requireDestinationTag && destinationTag == null) {
            throw XrpDestinationTagRequiredException(address)
        }
        return XrpDestinationState(active = true, minimumAmountDrops = 1, account.requireDestinationTag)
    }

    /** Pins source balance, destination flags/reserve and fee selection to one validated ledger. */
    suspend fun paymentPreflight(
        sourceAddress: String,
        destinationAddress: String,
        destinationTag: UInt?,
        recommendedFeeDrops: Long? = null,
    ): XrpPaymentPreflight {
        val server = rpc.serverState()
        requireMainnet(server)
        val ledger = server.validatedLedgerIndex
        val source = rpc.accountState(sourceAddress, ledger) ?: throw XrpInactiveAccountException(sourceAddress)
        requirePinned(source, ledger)
        val destination = rpc.accountState(destinationAddress, ledger)?.also { requirePinned(it, ledger) }
        if (destination?.requireDestinationTag == true && destinationTag == null) {
            throw XrpDestinationTagRequiredException(destinationAddress)
        }
        val feeQuote = rpc.feeQuote()
        return XrpPaymentPreflight(
            source = sourceSnapshot(server, source, feeQuote, recommendedFeeDrops),
            destination = if (destination == null) {
                XrpDestinationState(false, server.reserveBaseDrops, false)
            } else {
                XrpDestinationState(true, 1, destination.requireDestinationTag)
            },
        )
    }

    private fun sourceSnapshot(
        server: XrpServerState,
        account: XrpAccountState,
        feeQuote: XrpFeeQuote,
        recommendedFeeDrops: Long?,
    ): XrpAccountSnapshot {
        if (feeQuote.currentLedgerIndex < server.validatedLedgerIndex) {
            throw XrpProtocolException("fee response is older than the pinned validated ledger")
        }
        requireProtocolDrops("account balance", account.balanceDrops, allowZero = true)
        requireProtocolDrops("base reserve", server.reserveBaseDrops, allowZero = true)
        requireProtocolDrops("owner reserve", server.reserveIncrementDrops, allowZero = true)
        if (account.ownerCount !in 0..UInt.MAX_VALUE.toLong()) {
            throw XrpProtocolException("OwnerCount is outside UInt32 range")
        }
        val reserve = try {
            Math.addExact(
                server.reserveBaseDrops,
                Math.multiplyExact(server.reserveIncrementDrops, account.ownerCount),
            )
        } catch (error: ArithmeticException) {
            throw XrpProtocolException("XRP account reserve overflow", error)
        }
        if (reserve > XRP_MAX_NATIVE_DROPS) {
            throw XrpProtocolException("XRP account reserve exceeds protocol supply")
        }
        val fee = feePolicy.select(feeQuote, recommendedFeeDrops)
        val spendable = when {
            account.balanceDrops <= reserve -> 0L
            Math.subtractExact(account.balanceDrops, reserve) <= fee -> 0L
            else -> Math.subtractExact(Math.subtractExact(account.balanceDrops, reserve), fee)
        }
        return XrpAccountSnapshot(
            ledgerIndex = server.validatedLedgerIndex,
            completeLedgers = server.completeLedgers,
            balanceDrops = account.balanceDrops,
            sequence = account.sequence,
            ownerCount = account.ownerCount,
            reserveBaseDrops = server.reserveBaseDrops,
            reserveIncrementDrops = server.reserveIncrementDrops,
            spendableDrops = spendable,
            feeDrops = fee,
            requireDestinationTag = account.requireDestinationTag,
        )
    }

    private fun requireMainnet(server: XrpServerState) {
        if (server.networkId != 0) throw XrpNetworkMismatchException(server.networkId)
        if (!server.completeLedgers.covers(server.validatedLedgerIndex, server.validatedLedgerIndex)) {
            throw XrpLedgerGapException("Current validated ledger is not in complete_ledgers")
        }
    }

    private fun requirePinned(account: XrpAccountState, expectedLedger: Long) {
        if (account.ledgerIndex != expectedLedger) {
            throw XrpProtocolException(
                "account_info returned ledger ${account.ledgerIndex}, expected pinned ledger $expectedLedger"
            )
        }
    }
}
