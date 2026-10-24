package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.InMemoryLedger
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fondasi sync tanpa kripto/jaringan sungguhan — [FakeSyncAead] identitas, [FakeSyncRemote] di memori. */
private class FakeSyncAead : SyncAead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
}

private class FakeRoomKeyProvider : RoomKeyProvider {
    private val aead = FakeSyncAead()
    override fun getOrCreateRoomKey(roomId: RoomId) = aead
    override fun exportRoomKey(roomId: RoomId) = ByteArray(0)
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) = Unit
    override fun rotateRoomKey(roomId: RoomId) = aead
}

/** Codec tiruan: baris teks dipisah "|" — cukup untuk menguji logika [SyncEngine], bukan format sungguhan. */
private class FakeTransactionSyncCodec : TransactionSyncCodec {
    override fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray =
        listOf(
            transaction.id.value,
            transaction.amount.minor.toString(),
            accountName,
            categoryName ?: "",
            transaction.occurredOn.toString(),
            transaction.note ?: "",
        ).joinToString("|").toByteArray()

    override fun decode(bytes: ByteArray): DecodedSyncTransaction {
        val parts = String(bytes).split("|")
        return DecodedSyncTransaction(
            id = TransactionId(parts[0]),
            amount = Money(parts[1].toLong()),
            accountName = parts[2],
            categoryName = parts[3].ifEmpty { null },
            occurredOn = LocalDate.parse(parts[4]),
            note = parts[5].ifEmpty { null },
        )
    }
}

private class FakeSyncRemote : SyncRemote {
    val stored = mutableListOf<SyncLogEntryDownload>()
    var nextId = 1L
    var failWith: RemoteError? = null

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        val ids = entries.map { entry ->
            val id = nextId++
            stored += SyncLogEntryDownload(id, entry.roomId, entry.recordType, entry.recordId, entry.deviceId, entry.payload, entry.deleted, entry.changedAt)
            id
        }
        return RemoteResult.Success(SyncPushResult(ids, ids.max()))
    }

    override suspend fun pull(apiToken: String, after: Long, limit: Int): RemoteResult<SyncPullResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        val page = stored.filter { it.id > after }.sortedBy { it.id }.take(limit)
        return RemoteResult.Success(SyncPullResult(page, page.lastOrNull()?.id ?: after, page.size == limit))
    }
}

private class FakeApiTokenStore(private var token: String? = "token-1") : ApiTokenStore {
    override fun read() = token
    override fun save(token: String) { this.token = token }
    override fun clear() { token = null }
}

private class FakeSyncCursorStore : SyncCursorStore {
    private val cursors = mutableMapOf<String, Long>()
    override fun read(roomId: String) = cursors[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { cursors[roomId] = cursor }
}

class SyncEngineTest {
    private val roomId = RoomId("room-keluarga")
    private val accountId = AccountId("acc-1")
    private val categoryId = CategoryId("cat-1")

    private fun engine(ledger: InMemoryLedger, remote: FakeSyncRemote, tokenStore: ApiTokenStore = FakeApiTokenStore()) =
        SyncEngine(ledger, ledger, ledger, FakeRoomKeyProvider(), remote, FakeTransactionSyncCodec(), tokenStore, FakeSyncCursorStore())

    private fun seedRoomAndAccount(ledger: InMemoryLedger) {
        ledger.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledger.categoryRows[categoryId] = Category(categoryId, roomId, "Belanja")
        ledger.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
    }

    private fun expense(id: String, amount: Long, note: String? = null, updatedAt: Long = 1_000L) = MoneyTransaction(
        id = TransactionId(id),
        kind = TransactionKind.EXPENSE,
        amount = Money(amount),
        accountId = accountId,
        roomId = roomId,
        categoryId = categoryId,
        occurredOn = LocalDate.of(2026, 10, 24),
        note = note,
        origin = TransactionOrigin.MANUAL,
        createdAtMillis = updatedAt,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun pushes_pending_transactions_and_marks_them_synced() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()

        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a")

        assertTrue(result is SyncResult.Success)
        assertEquals(1, result.pushed)
        assertEquals(1, remote.stored.size)
        assertTrue(ledger.syncedAtRows.containsKey(TransactionId("tx-1")))
    }

    @Test
    fun does_not_push_the_same_transaction_twice() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()
        val e = engine(ledger, remote)

        e.syncNow(roomId, deviceId = "device-a")
        val second = e.syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(0, second.pushed)
        assertEquals(1, remote.stored.size)
    }

    @Test
    fun applies_a_transaction_pushed_by_another_member() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000, note = "Belanja mingguan")
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB) // anggota B: ruang, akun "Dompet", kategori "Belanja" sama namanya
        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        assertTrue(result is SyncResult.Success)
        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        val applied = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(50_000), applied?.amount)
        assertEquals("Belanja mingguan", applied?.note)
        assertEquals(categoryId, applied?.categoryId)
    }

    @Test
    fun ignores_its_own_pushed_entries_when_pulling() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()

        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(0, result.applied) // entry baru saja dipush oleh device-a sendiri, bukan dari anggota lain
    }

    @Test
    fun skips_an_incoming_transaction_with_an_unknown_account_name() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        ledgerB.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledgerB.categoryRows[categoryId] = Category(categoryId, roomId, "Belanja")
        // Tidak ada akun "Dompet" di sisi B sama sekali.
        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        assertTrue(ledgerB.transactionRows.isEmpty())
    }

    @Test
    fun falls_back_to_lain_lain_category_when_the_named_category_is_missing() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        ledgerB.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledgerB.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
        val fallbackCategoryId = CategoryId("cat-lain-lain")
        ledgerB.categoryRows[fallbackCategoryId] = Category(fallbackCategoryId, roomId, "Lain-lain")
        // Tidak ada kategori "Belanja" di sisi B — hanya "Lain-lain" yang selalu ada per ruang.

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        assertEquals(fallbackCategoryId, ledgerB.transactionRows[TransactionId("tx-1")]?.categoryId)
    }

    @Test
    fun last_write_wins_keeps_the_newer_change_on_conflict() = runSuspend {
        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        // B sudah punya versi lama transaksi ini secara lokal (belum pernah sync), lalu A mengirim versi baru.
        ledgerB.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000, note = "lama", updatedAt = 500L)

        val remote = FakeSyncRemote()
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 75_000, note = "baru", updatedAt = 2_000L)
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        val merged = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(75_000), merged?.amount)
        assertEquals("baru", merged?.note)
    }

    @Test
    fun reports_not_signed_in_without_throwing_when_there_is_no_api_token() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        val result = engine(ledger, FakeSyncRemote(), tokenStore = FakeApiTokenStore(token = null)).syncNow(roomId, "device-a")

        assertEquals(SyncResult.NotSignedIn, result)
    }

    @Test
    fun reports_failure_without_throwing_when_the_server_is_unreachable() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        val remote = FakeSyncRemote().apply { failWith = RemoteError.Unreachable }

        val result = engine(ledger, remote).syncNow(roomId, "device-a")

        assertEquals(SyncResult.Failed(RemoteError.Unreachable), result)
    }
}
