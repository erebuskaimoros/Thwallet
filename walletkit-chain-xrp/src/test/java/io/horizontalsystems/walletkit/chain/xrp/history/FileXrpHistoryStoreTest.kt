package io.horizontalsystems.walletkit.chain.xrp.history

import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SqliteXrpHistoryStoreTest {
    private val address = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"

    @Test
    fun `history and checkpoint survive a process restart as one record`() = runBlocking {
        val directory = Files.createTempDirectory("xrp-history-test").toFile()
        try {
            val incoming = transaction(hash = "A".repeat(64), ledger = 90, incoming = true)
            val outgoing = transaction(hash = "B".repeat(64), ledger = 91, incoming = false)

            SqliteXrpHistoryStore(directory.resolve("history.sqlite")).use { store ->
                store.commit(address, listOf(incoming, outgoing), 100)
            }

            SqliteXrpHistoryStore(directory.resolve("history.sqlite")).use { restored ->
                assertEquals(100L, restored.checkpoint(address))
                assertEquals(listOf(outgoing, incoming), restored.transactions(address, limit = 20))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unsupported schema construction closes its opened database`() {
        val directory = Files.createTempDirectory("xrp-history-schema-test").toFile()
        val databaseFile = directory.resolve("history.sqlite")
        try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
                database.version = 2
            }

            assertThrows(XrpHistoryStoreException::class.java) {
                SqliteXrpHistoryStore(databaseFile)
            }

            activeDatabases()
                .filter { it.path == databaseFile.absolutePath }
                .forEach { assertFalse("Failed XRP store construction leaked SQLite", it.isOpen) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a conflicting validated hash is rejected without advancing checkpoint`() = runBlocking {
        val directory = Files.createTempDirectory("xrp-history-upsert-test").toFile()
        try {
            val first = transaction(hash = "C".repeat(64), ledger = 90, incoming = true)
            val corrected = first.copy(amountDrops = 25)
            val store = SqliteXrpHistoryStore(directory.resolve("history.sqlite"))

            store.commit(address, listOf(first), 90)
            org.junit.Assert.assertThrows(io.horizontalsystems.walletkit.chain.xrp.rpc.XrpProtocolException::class.java) {
                runBlocking { store.commit(address, listOf(corrected), 100) }
            }

            assertEquals(90L, store.checkpoint(address))
            assertEquals(listOf(first), store.transactions(address, limit = 20))
            store.clear(address)
            assertNull(store.checkpoint(address))
            assertEquals(emptyList<XrpNativeTransaction>(), store.transactions(address, limit = 20))
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `durable cursor orders multiple transactions in one ledger by hash`() = runBlocking {
        val directory = Files.createTempDirectory("xrp-history-cursor-test").toFile()
        try {
            val high = transaction("F".repeat(64), 100, true)
            val middle = transaction("B".repeat(64), 100, false)
            val low = transaction("A".repeat(64), 100, true)
            val older = transaction("E".repeat(64), 99, false)
            val store = SqliteXrpHistoryStore(directory.resolve("history.sqlite"))
            store.commit(address, listOf(low, older, high, middle), 100)

            assertEquals(listOf(high, middle), store.transactions(address, limit = 2))
            assertEquals(
                listOf(low, older),
                store.transactions(address, beforeLedger = 100, beforeHash = middle.hash, limit = 2),
            )
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `staged pages stay invisible across restart until atomic checkpoint commit`() = runBlocking {
        val directory = Files.createTempDirectory("xrp-history-staging-test").toFile()
        val databaseFile = directory.resolve("history.sqlite")
        try {
            val pending = transaction("D".repeat(64), 100, true)
            SqliteXrpHistoryStore(databaseFile).use { store ->
                store.beginSync(address, 100)
                store.stagePage(address, 100, listOf(pending))
                assertNull(store.checkpoint(address))
                assertEquals(emptyList<XrpNativeTransaction>(), store.transactions(address, limit = 10))
            }

            SqliteXrpHistoryStore(databaseFile).use { restored ->
                assertNull(restored.checkpoint(address))
                assertEquals(emptyList<XrpNativeTransaction>(), restored.transactions(address, limit = 10))
                restored.beginSync(address, 100)
                restored.stagePage(address, 100, listOf(pending))
                restored.commitSync(address, 100)
                assertEquals(100L, restored.checkpoint(address))
                assertEquals(listOf(pending), restored.transactions(address, limit = 10))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `indexed cursor reaches records beyond ten thousand without loading full history`() = runBlocking {
        val directory = Files.createTempDirectory("xrp-history-scale-test").toFile()
        try {
            val history = (0 until 10_020).map { index ->
                transaction(
                    hash = index.toString(16).uppercase().padStart(64, '0'),
                    ledger = 1L + index / 2,
                    incoming = index % 2 == 0,
                )
            }
            SqliteXrpHistoryStore(directory.resolve("history.sqlite")).use { store ->
                store.beginSync(address, 6_000)
                history.chunked(200).forEach { page -> store.stagePage(address, 6_000, page) }
                store.commitSync(address, 6_000)

                val first = store.transactions(address, limit = 10_000)
                val boundary = first.last()
                val second = store.transactions(
                    address,
                    beforeLedger = boundary.ledgerIndex,
                    beforeHash = boundary.hash,
                    limit = 100,
                )
                assertEquals(10_000, first.size)
                assertEquals(20, second.size)
                assertEquals(10_020, (first + second).map { it.hash }.distinct().size)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun transaction(hash: String, ledger: Long, incoming: Boolean) = XrpNativeTransaction(
        hash = hash,
        ledgerIndex = ledger,
        timestampEpochSeconds = 1_700_000_000 + ledger,
        source = if (incoming) "rSender" else address,
        destination = if (incoming) address else "rDestination",
        destinationTag = 0u,
        amountDrops = 10,
        feeDrops = 12,
        sequence = 7,
        incoming = incoming,
        successful = true,
    )

    @Suppress("UNCHECKED_CAST")
    private fun activeDatabases(): Set<SQLiteDatabase> {
        val field = SQLiteDatabase::class.java.getDeclaredField("sActiveDatabases")
        field.isAccessible = true
        return (field.get(null) as Map<SQLiteDatabase, *>).keys.toSet()
    }
}
