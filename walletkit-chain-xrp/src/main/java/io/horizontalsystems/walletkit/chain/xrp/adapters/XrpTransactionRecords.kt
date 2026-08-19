package io.horizontalsystems.walletkit.chain.xrp.adapters

import io.horizontalsystems.walletkit.entities.TransactionValue
import io.horizontalsystems.walletkit.entities.transactionrecords.TransactionRecord
import io.horizontalsystems.walletkit.modules.transactions.TransactionSource
import io.horizontalsystems.marketkit.models.Token
import java.math.BigDecimal

abstract class XrpTransactionRecord(
    uid: String,
    transactionHash: String,
    blockHeight: Int,
    timestamp: Long,
    failed: Boolean,
    val fee: TransactionValue?,
    val destinationTag: UInt?,
    source: TransactionSource,
) : TransactionRecord(
    uid = uid,
    transactionHash = transactionHash,
    transactionIndex = 0,
    blockHeight = blockHeight,
    confirmationsThreshold = 1,
    timestamp = timestamp,
    failed = failed,
    source = source,
)

class XrpIncomingTransactionRecord(
    token: Token,
    uid: String,
    transactionHash: String,
    blockHeight: Int,
    timestamp: Long,
    failed: Boolean,
    amount: BigDecimal,
    val from: String,
    destinationTag: UInt?,
    source: TransactionSource,
) : XrpTransactionRecord(
    uid,
    transactionHash,
    blockHeight,
    timestamp,
    failed,
    null,
    destinationTag,
    source,
) {
    override val mainValue = TransactionValue.CoinValue(token, amount)
}

class XrpOutgoingTransactionRecord(
    token: Token,
    uid: String,
    transactionHash: String,
    blockHeight: Int,
    timestamp: Long,
    failed: Boolean,
    fee: BigDecimal,
    amount: BigDecimal,
    val to: String,
    val sentToSelf: Boolean,
    destinationTag: UInt?,
    source: TransactionSource,
) : XrpTransactionRecord(
    uid,
    transactionHash,
    blockHeight,
    timestamp,
    failed,
    TransactionValue.CoinValue(token, fee),
    destinationTag,
    source,
) {
    override val mainValue = TransactionValue.CoinValue(token, amount.negate())
}
