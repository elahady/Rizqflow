package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.ledger.FirstAccount
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplate
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSetup
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Menguji [com.roziqrizal.rizqflow.data.repo.LocalRepositories.LocalTransactionRepository] sisi sync (Tahap 9) terhadap Room sungguhan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTest {

    private lateinit var local: LocalLedger
    private lateinit var ledger: LedgerService
    private var counter = 0
    private val newId: () -> String = { "id-${++counter}" }
    private val today = java.time.LocalDate.of(2026, 10, 24)

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AndroidRoom.inMemoryDatabaseBuilder(context, RizqflowDatabase::class.java).allowMainThreadQueries().build()
        local = LocalLedger(db)
        runBlocking { WorkspaceSetup(local.workspace, newId).setUp(RoomTemplate.TIGA_HAK, FirstAccount("Dompet", AccountKind.CASH, Money.rupiah(5_000_000))) }
        ledger = LedgerService(local.accounts, local.rooms, local.transactions, newId) { 1_000L }
    }

    private suspend fun keluargaRoomId() = local.rooms.activeRooms().first { it.name == "Keluarga" }.id
    private suspend fun accountId() = local.accounts.activeAccounts().first().id

    private suspend fun recordExpense(amount: Long, note: String? = null) =
        assertIs<LedgerResult.Success<*>>(
            ledger.recordExpense(
                NewExpense(
                    amount = Money.rupiah(amount),
                    accountId = accountId(),
                    roomId = keluargaRoomId(),
                    categoryId = local.rooms.categories(keluargaRoomId()).first { it.name == "Lain-lain" }.id,
                    occurredOn = today,
                    note = note,
                ),
            ),
        )

    @Test
    fun `transaksi baru pending sync sampai ditandai markSynced`() = runBlocking {
        recordExpense(25_000)

        val pending = local.transactions.pendingSync(keluargaRoomId())
        assertEquals(1, pending.size)

        local.transactions.markSynced(listOf(pending.single().transaction.id), 1_234L)

        assertTrue(local.transactions.pendingSync(keluargaRoomId()).isEmpty())
    }

    @Test
    fun `mengubah transaksi yang sudah synced menjadikannya pending lagi`() = runBlocking {
        val receipt = recordExpense(25_000)
        val transaction = (receipt.value as com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction)
        local.transactions.markSynced(listOf(transaction.id), 1_234L)
        assertTrue(local.transactions.pendingSync(keluargaRoomId()).isEmpty())

        local.transactions.update(transaction.copy(note = "diubah", updatedAtMillis = 5_000L))

        val pending = local.transactions.pendingSync(keluargaRoomId())
        assertEquals(1, pending.size)
        assertEquals("diubah", pending.single().transaction.note)
    }

    @Test
    fun `applyFromSync memasukkan transaksi baru dari anggota lain`() = runBlocking {
        val roomId = keluargaRoomId()
        val categoryId = local.rooms.categories(roomId).first { it.name == "Lain-lain" }.id
        val incoming = com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction(
            id = TransactionId("remote-1"),
            kind = com.roziqrizal.rizqflow.domain.model.TransactionKind.EXPENSE,
            amount = Money.rupiah(75_000),
            accountId = accountId(),
            roomId = roomId,
            categoryId = categoryId,
            occurredOn = today,
            note = "dari anggota lain",
            createdAtMillis = 2_000L,
            updatedAtMillis = 2_000L,
        )

        local.transactions.applyFromSync(TransactionId("remote-1"), incoming, changedAtMillis = 2_000L, deleted = false)

        val stored = local.transactions.find(TransactionId("remote-1"))
        assertEquals(Money.rupiah(75_000), stored?.amount)
        // Baris hasil terapan tidak pending push lagi.
        assertTrue(local.transactions.pendingSync(roomId).none { it.transaction.id == TransactionId("remote-1") })
    }

    @Test
    fun `applyFromSync mengabaikan versi lebih lama, last-write-wins`() = runBlocking {
        val roomId = keluargaRoomId()
        val categoryId = local.rooms.categories(roomId).first { it.name == "Lain-lain" }.id
        val newer = com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction(
            id = TransactionId("remote-1"), kind = com.roziqrizal.rizqflow.domain.model.TransactionKind.EXPENSE,
            amount = Money.rupiah(90_000), accountId = accountId(), roomId = roomId, categoryId = categoryId,
            occurredOn = today, note = "baru", createdAtMillis = 5_000L, updatedAtMillis = 5_000L,
        )
        local.transactions.applyFromSync(TransactionId("remote-1"), newer, changedAtMillis = 5_000L, deleted = false)

        val older = newer.copy(amount = Money.rupiah(10_000), note = "lama")
        local.transactions.applyFromSync(TransactionId("remote-1"), older, changedAtMillis = 1_000L, deleted = false)

        val stored = local.transactions.find(TransactionId("remote-1"))
        assertEquals(Money.rupiah(90_000), stored?.amount)
        assertEquals("baru", stored?.note)
    }

    @Test
    fun `applyFromSync dengan deleted menghapus baris lokal`() = runBlocking {
        val receipt = recordExpense(25_000)
        val transaction = receipt.value as com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction

        local.transactions.applyFromSync(transaction.id, null, changedAtMillis = 9_999L, deleted = true)

        assertNull(local.transactions.find(transaction.id))
    }
}
