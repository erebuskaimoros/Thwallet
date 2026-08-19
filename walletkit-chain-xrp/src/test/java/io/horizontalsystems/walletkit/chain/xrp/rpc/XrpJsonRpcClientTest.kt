package io.horizontalsystems.walletkit.chain.xrp.rpc

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XrpJsonRpcClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: XrpJsonRpcClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = XrpJsonRpcClient(server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `loads validated server account and dynamic fee data`() = runTest {
        enqueue(
            """{"result":{"status":"success","info":{"network_id":0,"complete_ledgers":"90-120","validated_ledger":{"seq":120,"reserve_base_xrp":"1","reserve_inc_xrp":"0.2"}}}}"""
        )
        enqueue(
            """{"result":{"status":"success","account_data":{"Account":"rSource","Balance":"5000000","Sequence":7,"OwnerCount":2,"Flags":131072},"ledger_index":120,"validated":true}}"""
        )
        enqueue(
            """{"result":{"status":"success","drops":{"base_fee":"10","minimum_fee":"10","median_fee":"12","open_ledger_fee":"14"},"ledger_current_index":121}}"""
        )

        val snapshot = XrpLedgerReader(client, XrpFeePolicy(maxFeeDrops = 100)).snapshot("rSource")

        assertEquals(120, snapshot.ledgerIndex)
        assertEquals(5_000_000, snapshot.balanceDrops)
        assertEquals(1_000_000, snapshot.reserveBaseDrops)
        assertEquals(200_000, snapshot.reserveIncrementDrops)
        assertEquals(3_599_986, snapshot.spendableDrops)
        assertEquals(14, snapshot.feeDrops)
        assertTrue(snapshot.requireDestinationTag)
        assertEquals(listOf(XrpLedgerRange(90, 120)), snapshot.completeLedgers.ranges)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"method\":\"server_info\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"ledger_index\":120"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"method\":\"fee\""))
    }

    @Test
    fun `rejects non-mainnet server and fee above cap`() = runTest {
        enqueue(
            """{"result":{"status":"success","info":{"network_id":1,"complete_ledgers":"1-10","validated_ledger":{"seq":10,"reserve_base_xrp":1,"reserve_inc_xrp":0.2}}}}"""
        )
        assertThrows(XrpNetworkMismatchException::class.java) {
            kotlinx.coroutines.runBlocking { client.serverState() }
        }

        enqueue(
            """{"result":{"status":"success","drops":{"base_fee":"10","minimum_fee":"10","median_fee":"10","open_ledger_fee":"1001"},"ledger_current_index":11}}"""
        )
        val quote = client.feeQuote()
        assertThrows(XrpFeeTooHighException::class.java) {
            XrpFeePolicy(maxFeeDrops = 100).select(quote)
        }
    }

    @Test
    fun `distinguishes an inactive account from rpc failure`() = runTest {
        enqueue("""{"result":{"status":"error","error":"actNotFound","error_message":"Account not found"}}""")
        assertEquals(null, client.accountState("rInactive", 44))

        enqueue("""{"result":{"status":"error","error":"invalidParams","error_message":"bad request"}}""")
        assertThrows(XrpRpcException::class.java) {
            kotlinx.coroutines.runBlocking { client.accountState("rBad", 44) }
        }
    }

    @Test
    fun `rejects balance and reserve values above the native XRP supply`() = runTest {
        enqueue(
            """{"result":{"status":"success","info":{"network_id":0,"complete_ledgers":"90-120","validated_ledger":{"seq":120,"reserve_base_xrp":"100000000000.000001","reserve_inc_xrp":"0.2"}}}}"""
        )
        assertThrows(XrpProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { client.serverState() }
        }

        enqueue(
            """{"result":{"status":"success","account_data":{"Account":"rSource","Balance":"100000000000000001","Sequence":7,"OwnerCount":0,"Flags":0},"ledger_index":120,"validated":true}}"""
        )
        assertThrows(XrpProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { client.accountState("rSource", 120) }
        }
    }

    @Test
    fun `round trips object and string account tx markers and filters unsafe amounts`() = runTest {
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"marker":{"ledger":100,"seq":7},"transactions":[{"validated":true,"tx_json":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","Amount":"123","Fee":"10","Sequence":1,"Flags":0,"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ledger_index":100},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"100"}},{"validated":true,"tx_json":{"TransactionType":"Payment","Account":"rIssuer","Destination":"rMine","Amount":{"currency":"USD","issuer":"rIssuer","value":"5"},"Fee":"10","Sequence":2,"hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","ledger_index":101},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":{"currency":"USD","issuer":"rIssuer","value":"5"}}}]}}"""
        )
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"marker":"opaque-next","transactions":[]}}"""
        )
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"transactions":[]}}"""
        )

        val first = client.accountTransactions("rMine", 90, 120, null)
        assertEquals(1, first.transactions.size)
        assertEquals(100, first.transactions.single().amountDrops)
        assertTrue(first.transactions.single().incoming)
        assertEquals("{\"ledger\":100,\"seq\":7}", first.marker?.encoded)

        val second = client.accountTransactions("rMine", 90, 120, first.marker)
        assertEquals("\"opaque-next\"", second.marker?.encoded)
        client.accountTransactions("rMine", 90, 120, second.marker)

        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        val thirdBody = server.takeRequest().body.readUtf8()
        assertFalse(firstBody.contains("\"marker\""))
        assertTrue(secondBody.contains("\"marker\":{\"ledger\":100,\"seq\":7}"))
        assertTrue(thirdBody.contains("\"marker\":\"opaque-next\""))
    }

    @Test
    fun `parses live shaped v1 Amount and v2 DeliverMax using delivered amount`() = runTest {
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"transactions":[{"validated":true,"tx":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","Amount":"123","Fee":"10","Sequence":1,"Flags":0,"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ledger_index":100,"date":800000000},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"100"}}]}}"""
        )
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"transactions":[{"validated":true,"hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","ledger_index":101,"close_time_iso":"2026-08-18T12:00:00Z","tx_json":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","DeliverMax":"999","Fee":"12","Sequence":2,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"321"}}]}}"""
        )

        val v1 = client.accountTransactions("rMine", 90, 120, null).transactions.single()
        val v2 = client.accountTransactions("rMine", 90, 120, null).transactions.single()

        assertEquals(100L, v1.amountDrops)
        assertEquals(321L, v2.amountDrops)
        assertEquals(1_746_684_800L, v1.timestampEpochSeconds)
        assertEquals(12L, v2.feeDrops)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"api_version\":2"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"api_version\":2"))
    }

    @Test
    fun `rejects issued partial and path payment history rows`() = runTest {
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"transactions":[
              {"validated":true,"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ledger_index":100,"tx_json":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","DeliverMax":{"currency":"USD","issuer":"rIssuer","value":"5"},"Fee":"10","Sequence":1,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":{"currency":"USD","issuer":"rIssuer","value":"5"}}},
              {"validated":true,"hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","ledger_index":101,"tx_json":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","DeliverMax":"200","Fee":"10","Sequence":2,"Flags":131072},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"100"}},
              {"validated":true,"hash":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC","ledger_index":102,"tx_json":{"TransactionType":"Payment","Account":"rMine","Destination":"rOther","DeliverMax":"200","Fee":"10","Sequence":3,"Flags":131072},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"100"}},
              {"validated":true,"hash":"DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD","ledger_index":103,"tx_json":{"TransactionType":"Payment","Account":"rMine","Destination":"rOther","DeliverMax":"200","SendMax":"250","Fee":"10","Sequence":4,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"200"}},
              {"validated":true,"hash":"EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE","ledger_index":104,"tx_json":{"TransactionType":"Payment","Account":"rMine","Destination":"rOther","DeliverMax":"200","DeliverMin":"190","Fee":"10","Sequence":5,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"200"}},
              {"validated":true,"hash":"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF","ledger_index":105,"tx_json":{"TransactionType":"Payment","Account":"rMine","Destination":"rOther","DeliverMax":"200","Paths":[],"Fee":"10","Sequence":6,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"200"}}
            ]}}"""
        )

        assertTrue(client.accountTransactions("rMine", 90, 120, null).transactions.isEmpty())
    }

    @Test
    fun `legacy unavailable delivered amount falls back only for a direct native payment`() = runTest {
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90000,"ledger_index_max":91000,"validated":true,"transactions":[{"validated":true,"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ledger_index":90628,"tx":{"TransactionType":"Payment","Account":"rOther","Destination":"rMine","Amount":"123","Fee":"10","Sequence":1,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"unavailable"}}]}}"""
        )

        val transaction = client.accountTransactions("rMine", 90_000, 91_000, null).transactions.single()

        assertEquals(123L, transaction.amountDrops)
        assertTrue(transaction.incoming)
    }

    @Test
    fun `self payment is outgoing and retains its fee`() = runTest {
        enqueue(
            """{"result":{"status":"success","ledger_index_min":90,"ledger_index_max":120,"validated":true,"transactions":[{"validated":true,"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ledger_index":100,"tx_json":{"TransactionType":"Payment","Account":"rMine","Destination":"rMine","DeliverMax":"123","Fee":"10","Sequence":1,"Flags":0},"meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"123"}}]}}"""
        )

        val transaction = client.accountTransactions("rMine", 90, 120, null).transactions.single()

        assertFalse(transaction.incoming)
        assertEquals(10L, transaction.feeDrops)
    }

    @Test
    fun `transaction lookup requires the server response hash`() = runTest {
        enqueue(
            """{"result":{"status":"success","validated":true,"ledger_index":100,"meta":{"TransactionResult":"tesSUCCESS"}}}"""
        )

        assertThrows(XrpProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { client.transaction("A".repeat(64)) }
        }
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"))
    }
}
