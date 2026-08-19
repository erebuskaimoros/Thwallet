package io.horizontalsystems.walletkit.chain.xrp.history

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpProtocolException
import java.io.Closeable
import java.io.File

/** Indexed, crash-safe native-XRP history with disk-backed per-page staging. */
class SqliteXrpHistoryStore(databaseFile: File) : XrpQueryableHistoryStore, Closeable {
    private val lock = Any()
    private val database: SQLiteDatabase

    init {
        val parent = databaseFile.parentFile
            ?: throw XrpHistoryStoreException("XRP history database has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw XrpHistoryStoreException("Unable to create XRP history database directory")
        }
        val openedDatabase = try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        } catch (error: Exception) {
            throw XrpHistoryStoreException("Unable to open XRP history database", error)
        }
        try {
            openedDatabase.enableWriteAheadLogging()
            initializeSchema(openedDatabase)
        } catch (error: Exception) {
            try {
                if (openedDatabase.isOpen) openedDatabase.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw XrpHistoryStoreException("Unable to open XRP history database", error)
        }
        database = openedDatabase
    }

    override suspend fun checkpoint(address: String): Long? = synchronized(lock) {
        requireAddress(address)
        queryLong(
            "SELECT checkpoint FROM $TABLE_META WHERE address = ?",
            arrayOf(address),
        )
    }

    override suspend fun beginSync(address: String, targetCheckpoint: Long) = synchronized(lock) {
        requireAddress(address)
        require(targetCheckpoint >= 0) { "XRP history checkpoint cannot be negative" }
        val current = queryLong(
            "SELECT checkpoint FROM $TABLE_META WHERE address = ?",
            arrayOf(address),
        )
        require(current == null || targetCheckpoint >= current) {
            "XRP history checkpoint cannot move backwards"
        }
        transaction {
            delete(TABLE_STAGING, "address = ?", arrayOf(address))
            delete(TABLE_SYNC, "address = ?", arrayOf(address))
            insertOrThrow(
                TABLE_SYNC,
                null,
                ContentValues().apply {
                    put("address", address)
                    put("target_checkpoint", targetCheckpoint)
                },
            )
        }
    }

    override suspend fun stagePage(
        address: String,
        targetCheckpoint: Long,
        transactions: List<XrpNativeTransaction>,
    ): Int = synchronized(lock) {
        requireActiveSession(address, targetCheckpoint)
        var inserted = 0
        transaction {
            transactions.forEach { transaction ->
                validateTransaction(transaction, targetCheckpoint)
                val normalized = transaction.copy(hash = transaction.hash.uppercase())
                committedTransaction(address, normalized.hash)?.let { committed ->
                    if (committed != normalized) {
                        throw XrpProtocolException("Conflicting stored XRP transaction ${normalized.hash}")
                    }
                }
                val staged = stagedTransaction(address, targetCheckpoint, normalized.hash)
                if (staged != null) {
                    if (staged != normalized) {
                        throw XrpProtocolException("Conflicting account_tx records for ${normalized.hash}")
                    }
                } else {
                    insertOrThrow(TABLE_STAGING, null, values(address, normalized, targetCheckpoint))
                    inserted++
                }
            }
        }
        inserted
    }

    override suspend fun commitSync(address: String, targetCheckpoint: Long) = synchronized(lock) {
        requireActiveSession(address, targetCheckpoint)
        transaction {
            execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_TRANSACTIONS (
                    address, hash, ledger_index, timestamp, source, destination,
                    destination_tag, amount_drops, fee_drops, sequence, incoming, successful
                )
                SELECT address, hash, ledger_index, timestamp, source, destination,
                    destination_tag, amount_drops, fee_drops, sequence, incoming, successful
                FROM $TABLE_STAGING
                WHERE address = ? AND target_checkpoint = ?
                """.trimIndent(),
                arrayOf<Any>(address, targetCheckpoint),
            )
            insertWithOnConflict(
                TABLE_META,
                null,
                ContentValues().apply {
                    put("address", address)
                    put("checkpoint", targetCheckpoint)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            delete(
                TABLE_STAGING,
                "address = ? AND target_checkpoint = ?",
                arrayOf(address, targetCheckpoint.toString()),
            )
            delete(TABLE_SYNC, "address = ?", arrayOf(address))
        }
    }

    override suspend fun rollbackSync(address: String, targetCheckpoint: Long) = synchronized(lock) {
        transaction {
            delete(
                TABLE_STAGING,
                "address = ? AND target_checkpoint = ?",
                arrayOf(address, targetCheckpoint.toString()),
            )
            delete(
                TABLE_SYNC,
                "address = ? AND target_checkpoint = ?",
                arrayOf(address, targetCheckpoint.toString()),
            )
        }
    }

    override fun transactions(
        address: String,
        beforeLedger: Long?,
        beforeHash: String?,
        limit: Int,
    ): List<XrpNativeTransaction> = synchronized(lock) {
        requireAddress(address)
        require(limit in 1..MAX_QUERY_LIMIT) { "XRP history query limit must be 1-$MAX_QUERY_LIMIT" }
        require((beforeLedger == null) == (beforeHash == null)) {
            "History cursor requires both ledger and hash"
        }
        val selection: String
        val arguments: Array<String>
        if (beforeLedger == null) {
            selection = "address = ?"
            arguments = arrayOf(address)
        } else {
            require(beforeLedger >= 0) { "History cursor ledger cannot be negative" }
            require(HASH_REGEX.matches(requireNotNull(beforeHash))) { "Invalid history cursor hash" }
            selection = "address = ? AND (ledger_index < ? OR (ledger_index = ? AND hash < ?))"
            arguments = arrayOf(address, beforeLedger.toString(), beforeLedger.toString(), beforeHash.uppercase())
        }
        try {
            database.query(
                TABLE_TRANSACTIONS,
                TRANSACTION_COLUMNS,
                selection,
                arguments,
                null,
                null,
                "ledger_index DESC, hash DESC",
                limit.toString(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.transaction())
                }
            }
        } catch (error: XrpHistoryStoreException) {
            throw error
        } catch (error: Exception) {
            throw XrpHistoryStoreException("Unable to query XRP history", error)
        }
    }

    override fun clear(address: String) = synchronized(lock) {
        requireAddress(address)
        transaction {
            delete(TABLE_STAGING, "address = ?", arrayOf(address))
            delete(TABLE_SYNC, "address = ?", arrayOf(address))
            delete(TABLE_TRANSACTIONS, "address = ?", arrayOf(address))
            delete(TABLE_META, "address = ?", arrayOf(address))
        }
    }

    override fun close() = synchronized(lock) {
        if (database.isOpen) database.close()
    }

    private fun initializeSchema(database: SQLiteDatabase) {
        val version = database.version
        if (version !in 0..SCHEMA_VERSION) {
            throw XrpHistoryStoreException("Unsupported XRP history schema $version")
        }
        if (version == 0) {
            database.beginTransaction()
            try {
                database.execSQL(
                    "CREATE TABLE $TABLE_META (address TEXT PRIMARY KEY NOT NULL, checkpoint INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE TABLE $TABLE_SYNC (address TEXT PRIMARY KEY NOT NULL, target_checkpoint INTEGER NOT NULL)"
                )
                database.execSQL(transactionTableSql(TABLE_TRANSACTIONS, staging = false))
                database.execSQL(transactionTableSql(TABLE_STAGING, staging = true))
                database.execSQL(
                    "CREATE INDEX xrp_history_page ON $TABLE_TRANSACTIONS(address, ledger_index DESC, hash DESC)"
                )
                database.version = SCHEMA_VERSION
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun transaction(block: SQLiteDatabase.() -> Unit) {
        try {
            database.beginTransaction()
            database.block()
            database.setTransactionSuccessful()
        } catch (error: XrpProtocolException) {
            throw error
        } catch (error: Exception) {
            throw XrpHistoryStoreException("XRP history database transaction failed", error)
        } finally {
            if (database.inTransaction()) database.endTransaction()
        }
    }

    private fun requireActiveSession(address: String, targetCheckpoint: Long) {
        val target = queryLong(
            "SELECT target_checkpoint FROM $TABLE_SYNC WHERE address = ?",
            arrayOf(address),
        )
        if (target != targetCheckpoint) {
            throw XrpHistoryStoreException("XRP history staging session is missing or changed")
        }
    }

    private fun queryLong(sql: String, arguments: Array<String>): Long? = try {
        database.rawQuery(sql, arguments).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    } catch (error: Exception) {
        throw XrpHistoryStoreException("Unable to query XRP history metadata", error)
    }

    private fun committedTransaction(address: String, hash: String): XrpNativeTransaction? =
        queryTransaction(TABLE_TRANSACTIONS, "address = ? AND hash = ?", arrayOf(address, hash))

    private fun stagedTransaction(
        address: String,
        targetCheckpoint: Long,
        hash: String,
    ): XrpNativeTransaction? = queryTransaction(
        TABLE_STAGING,
        "address = ? AND target_checkpoint = ? AND hash = ?",
        arrayOf(address, targetCheckpoint.toString(), hash),
    )

    private fun queryTransaction(
        table: String,
        selection: String,
        arguments: Array<String>,
    ): XrpNativeTransaction? = database.query(
        table,
        TRANSACTION_COLUMNS,
        selection,
        arguments,
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.transaction() else null }

    private fun Cursor.transaction(): XrpNativeTransaction {
        val transaction = XrpNativeTransaction(
            hash = getString(getColumnIndexOrThrow("hash")),
            ledgerIndex = getLong(getColumnIndexOrThrow("ledger_index")),
            timestampEpochSeconds = getColumnIndexOrThrow("timestamp").let { index ->
                if (isNull(index)) null else getLong(index)
            },
            source = getString(getColumnIndexOrThrow("source")),
            destination = getString(getColumnIndexOrThrow("destination")),
            destinationTag = getColumnIndexOrThrow("destination_tag").let { index ->
                if (isNull(index)) null else getLong(index).also {
                    if (it !in 0..UInt.MAX_VALUE.toLong()) {
                        throw XrpHistoryStoreException("Stored XRP destination tag is invalid")
                    }
                }.toUInt()
            },
            amountDrops = getLong(getColumnIndexOrThrow("amount_drops")),
            feeDrops = getLong(getColumnIndexOrThrow("fee_drops")),
            sequence = getLong(getColumnIndexOrThrow("sequence")),
            incoming = getBoolean("incoming"),
            successful = getBoolean("successful"),
        )
        validateTransaction(transaction, Long.MAX_VALUE)
        return transaction
    }

    private fun Cursor.getBoolean(column: String): Boolean = when (val value = getInt(getColumnIndexOrThrow(column))) {
        0 -> false
        1 -> true
        else -> throw XrpHistoryStoreException("Stored XRP $column flag is invalid: $value")
    }

    private fun values(
        address: String,
        transaction: XrpNativeTransaction,
        targetCheckpoint: Long,
    ) = ContentValues().apply {
        put("address", address)
        put("target_checkpoint", targetCheckpoint)
        put("hash", transaction.hash)
        put("ledger_index", transaction.ledgerIndex)
        transaction.timestampEpochSeconds?.let { put("timestamp", it) } ?: putNull("timestamp")
        put("source", transaction.source)
        put("destination", transaction.destination)
        transaction.destinationTag?.let { put("destination_tag", it.toLong()) } ?: putNull("destination_tag")
        put("amount_drops", transaction.amountDrops)
        put("fee_drops", transaction.feeDrops)
        put("sequence", transaction.sequence)
        put("incoming", if (transaction.incoming) 1 else 0)
        put("successful", if (transaction.successful) 1 else 0)
    }

    private fun requireAddress(address: String) {
        require(address.isNotBlank()) { "XRP history address is required" }
    }

    private fun validateTransaction(transaction: XrpNativeTransaction, checkpoint: Long) {
        require(HASH_REGEX.matches(transaction.hash)) { "Invalid XRP history hash" }
        require(transaction.ledgerIndex in 1..checkpoint) { "XRP transaction ledger is outside checkpoint" }
        require(transaction.timestampEpochSeconds == null || transaction.timestampEpochSeconds >= 0) {
            "Invalid XRP transaction timestamp"
        }
        require(transaction.source.isNotBlank() && transaction.destination.isNotBlank()) {
            "XRP transaction accounts are required"
        }
        require(transaction.amountDrops >= 0) { "Invalid XRP transaction amount" }
        require(transaction.feeDrops > 0) { "Invalid XRP transaction fee" }
        require(transaction.sequence in 1..UInt.MAX_VALUE.toLong()) { "Invalid XRP transaction sequence" }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_QUERY_LIMIT = 10_000
        const val TABLE_META = "xrp_history_meta"
        const val TABLE_SYNC = "xrp_history_sync"
        const val TABLE_TRANSACTIONS = "xrp_history_transactions"
        const val TABLE_STAGING = "xrp_history_staging"
        val HASH_REGEX = Regex("^[A-Fa-f0-9]{64}$")
        val TRANSACTION_COLUMNS = arrayOf(
            "hash",
            "ledger_index",
            "timestamp",
            "source",
            "destination",
            "destination_tag",
            "amount_drops",
            "fee_drops",
            "sequence",
            "incoming",
            "successful",
        )

        fun transactionTableSql(table: String, staging: Boolean): String {
            val targetColumn = if (staging) "target_checkpoint INTEGER NOT NULL," else ""
            val primaryKey = if (staging) {
                "PRIMARY KEY(address, target_checkpoint, hash)"
            } else {
                "PRIMARY KEY(address, hash)"
            }
            return """
                CREATE TABLE $table (
                    address TEXT NOT NULL,
                    $targetColumn
                    hash TEXT NOT NULL,
                    ledger_index INTEGER NOT NULL,
                    timestamp INTEGER,
                    source TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    destination_tag INTEGER,
                    amount_drops INTEGER NOT NULL,
                    fee_drops INTEGER NOT NULL,
                    sequence INTEGER NOT NULL,
                    incoming INTEGER NOT NULL,
                    successful INTEGER NOT NULL,
                    $primaryKey
                )
            """.trimIndent()
        }
    }
}

class XrpHistoryStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
