package io.horizontalsystems.walletkit.chain.xrp.adapters

import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.core.AdapterState
import io.horizontalsystems.walletkit.entities.Account
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.AccountType
import io.horizontalsystems.walletkit.modules.transactions.FilterTransactionType
import io.horizontalsystems.walletkit.modules.transactions.TransactionSource
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import io.horizontalsystems.walletkit.chain.xrp.runtime.FakeXrpWalletKit
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpBalance
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpKitSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class XrpAdapterTest {
    private val account = Account("xrp-test", "XRP", AccountType.XrpAddress("rMine"), AccountOrigin.Restored, 0)
    private val blockchain = Blockchain(BlockchainType.Ripple, "XRP Ledger", "https://xrpscan.com/account/\$ref")
    private val token = Token(Coin("ripple", "XRP", "XRP"), blockchain, TokenType.Native, 6)
    private val source = TransactionSource(blockchain, account, null)

    @Test
    fun `balance adapter separates spendable balance from reserve`() {
        val kit = FakeXrpWalletKit(
            address = "rMine",
            initialBalance = XrpBalance(8_000_000, 1_000_000, 6_999_985, 15, 100),
            initialState = XrpKitSyncState.Synced,
        )
        val adapter = XrpAdapter(kit)

        assertEquals(AdapterState.Synced, adapter.balanceState)
        assertEquals(BigDecimal("7.000000"), adapter.balanceData?.available)
        assertEquals(BigDecimal("1.000000"), adapter.balanceData?.minimumBalance)
        assertEquals(BigDecimal("6.999985"), adapter.maxSendableBalance)
        assertEquals("rMine", adapter.receiveAddress)
    }

    @Test
    fun `transactions adapter pages and filters durable native history`() = runBlocking {
        val incoming = transaction("A".repeat(64), true, 101)
        val outgoing = transaction("B".repeat(64), false, 100)
        val kit = FakeXrpWalletKit(
            address = "rMine",
            transactions = MutableStateFlow(listOf(incoming, outgoing)),
        )
        val adapter = XrpTransactionsAdapter(kit, XrpTransactionConverter(source, token))

        val incomingRecords = adapter.getTransactions(null, token, 10, FilterTransactionType.Incoming, null)
        assertEquals(1, incomingRecords.size)
        assertTrue(incomingRecords.single() is XrpIncomingTransactionRecord)
        assertEquals(BigDecimal("0.000025"), incomingRecords.single().mainValue?.decimalValue)

        val outgoingRecords = adapter.getTransactions(null, token, 10, FilterTransactionType.Outgoing, "rOther")
        assertEquals(1, outgoingRecords.size)
        assertTrue(outgoingRecords.single() is XrpOutgoingTransactionRecord)
    }

    @Test
    fun `self payment is rendered outgoing with the sender paid fee`() {
        val self = transaction("C".repeat(64), incoming = false, ledger = 102).copy(
            source = "rMine",
            destination = "rMine",
        )

        val record = XrpTransactionConverter(source, token).convert(self)

        assertTrue(record is XrpOutgoingTransactionRecord)
        record as XrpOutgoingTransactionRecord
        assertTrue(record.sentToSelf)
        assertEquals(BigDecimal("0.000012"), record.fee?.decimalValue)
        assertEquals(BigDecimal("-0.000025"), record.mainValue?.decimalValue)
    }

    @Test
    fun `transaction pages cross the memory cap with a composite durable cursor and filters`() = runBlocking {
        val durableHistory = (0 until 10_020)
            .map { index ->
                transaction(
                    hash = index.toString(16).uppercase().padStart(64, '0'),
                    incoming = index % 3 == 0,
                    ledger = 20_000L - index / 2,
                )
            }
            .sortedWith(
                compareByDescending<XrpNativeTransaction> { it.ledgerIndex }
                    .thenByDescending { it.hash }
            )
        val kit = FakeXrpWalletKit(
            address = "rMine",
            transactions = MutableStateFlow(durableHistory.take(10_000)),
            durableTransactions = durableHistory,
        )
        val converter = XrpTransactionConverter(source, token)
        val adapter = XrpTransactionsAdapter(kit, converter)

        val boundary = converter.convert(durableHistory[9_998])
        val acrossCap = adapter.getTransactions(
            boundary,
            token,
            5,
            FilterTransactionType.All,
            null,
        )
        assertEquals(durableHistory.subList(9_999, 10_004).map { it.hash }, acrossCap.map { it.transactionHash })
        assertEquals(durableHistory[9_998].ledgerIndex, durableHistory[9_999].ledgerIndex)

        val outOfCacheBoundary = converter.convert(durableHistory[10_002])
        val filtered = adapter.getTransactions(
            outOfCacheBoundary,
            token,
            3,
            FilterTransactionType.Incoming,
            null,
        )
        assertEquals(
            durableHistory.drop(10_003).filter { it.incoming }.take(3).map { it.hash },
            filtered.map { it.transactionHash },
        )
    }

    private fun transaction(hash: String, incoming: Boolean, ledger: Long) = XrpNativeTransaction(
        hash,
        ledger,
        1_700_000_000 + ledger,
        if (incoming) "rOther" else "rMine",
        if (incoming) "rMine" else "rOther",
        0u,
        25,
        12,
        7,
        incoming,
        true,
    )
}
