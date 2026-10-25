package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.JsonTransactionSyncCodec
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SyncAead
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore
import com.roziqrizal.rizqflow.domain.sync.SyncEngine
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryDownload
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class IdentityAead : SyncAead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
}

private class IdentityKeys : RoomKeyProvider {
    private val aead = IdentityAead()
    override fun getOrCreateRoomKey(roomId: RoomId) = aead
    override fun exportRoomKey(roomId: RoomId) = ByteArray(0)
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) = Unit
    override fun rotateRoomKey(roomId: RoomId) = aead
}

private class MemoryRemote : SyncRemote {
    val stored = mutableListOf<SyncLogEntryDownload>()
    private var nextId = 1L

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        val ids = entries.map { e ->
            val id = nextId++
            stored += SyncLogEntryDownload(id, e.roomId, e.recordType, e.recordId, e.deviceId, e.payload, e.deleted, e.changedAt)
            id
        }
        return RemoteResult.Success(SyncPushResult(ids, ids.maxOrNull() ?: 0))
    }

    override suspend fun pull(apiToken: String, after: Long, limit: Int): RemoteResult<SyncPullResult> {
        val page = stored.filter { it.id > after }.take(limit)
        return RemoteResult.Success(SyncPullResult(page, page.lastOrNull()?.id ?: after, page.size == limit))
    }
}

private class MemoryToken : ApiTokenStore {
    override fun read() = "token"
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

private class MemoryCursor : SyncCursorStore {
    private val map = mutableMapOf<String, Long>()
    override fun read(roomId: String) = map[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { map[roomId] = cursor }
}

/**
 * SyncEngine lama terhadap Room sungguhan di dua sisi: pengeluaran pasangan yang ditarik tersimpan sebagai origin
 * SHARED, tidak dicatat recorder, dan tidak disentuh SyncBootstrap.markAllDirty (tidak diterbitkan kelak ke scope=a),
 * namun tetap muncul sebagai transaksi biasa di riwayat dan terpakai ruang seperti perilaku lama.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineBootstrapTest {
    private lateinit var a: SyncFixture
    private lateinit var b: SyncFixture

    @Before
    fun setUp() {
        a = SyncFixture()
        b = SyncFixture() // id acak berurutan sama di kedua sisi: ruang, kategori, dan akun "Dompet" identik namanya
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    private fun engine(f: SyncFixture, remote: SyncRemote) =
        SyncEngine(f.local.transactions, f.local.rooms, f.local.accounts, IdentityKeys(), remote, JsonTransactionSyncCodec(), MemoryToken(), MemoryCursor())

    private suspend fun keluarga(f: SyncFixture) = f.local.rooms.activeRooms().first { it.name == "Keluarga" }

    @Test
    fun `pengeluaran pasangan hasil pull tersimpan sebagai SHARED, tidak dicatat, dan tidak disentuh markAllDirty`() = runBlocking {
        val remote = MemoryRemote()
        val roomA = keluarga(a)
        val category = a.local.rooms.categories(roomA.id).first()
        val expense = (a.ledger.recordExpense(
            com.roziqrizal.rizqflow.domain.ledger.NewExpense(com.roziqrizal.rizqflow.domain.money.Money.rupiah(50_000), a.accountId(), roomA.id, category.id, a.today, "belanja"),
        ) as com.roziqrizal.rizqflow.domain.ledger.LedgerResult.Success).value
        assertTrue(engine(a, remote).syncNow(roomA.id, "device-a") is SyncResult.Success)

        val roomB = keluarga(b)
        assertEquals(roomA.id, roomB.id)
        b.clearSync()
        val result = engine(b, remote).syncNow(roomB.id, "device-b") as SyncResult.Success
        assertEquals(1, result.applied)

        val pulled = assertNotNull(b.local.transactions.find(expense.id))
        assertEquals(TransactionOrigin.SHARED, pulled.origin)
        assertTrue(b.rows().none { it.type == SyncRecordType.TRANSACTION.wire }, "pull SyncEngine lama tidak boleh dicatat recorder")

        // Backfill sync pertama tidak menyentuh baris itu (dan juga tidak menandai apa pun dari transaksi anggota lain).
        b.local.syncBootstrap.markAllDirty()
        assertTrue(b.rows().none { it.type == SyncRecordType.TRANSACTION.wire && it.id == expense.id.value }, "baris pasangan bukan data pribadi")

        // Perilaku lama tetap: baris itu terhitung sebagai pengeluaran terpakai ruang dan terlihat di riwayat.
        val totals = b.local.transactions.roomTotals(b.today, b.today)
        assertEquals(50_000L, totals.spent.getValue(roomB.id).minor)
        assertEquals(listOf(TransactionId(expense.id.value)), b.local.transactions.between(b.today, b.today).map { it.id })
    }

    @Test
    fun `transaksi pribadi milik sendiri tetap dijangkau markAllDirty bersama baris pasangan`() = runBlocking {
        val remote = MemoryRemote()
        val roomA = keluarga(a)
        a.ledger.recordExpense(
            com.roziqrizal.rizqflow.domain.ledger.NewExpense(com.roziqrizal.rizqflow.domain.money.Money.rupiah(10_000), a.accountId(), roomA.id, a.local.rooms.categories(roomA.id).first().id, a.today),
        )
        engine(a, remote).syncNow(roomA.id, "device-a")
        repeat(100) { b.newId() } // geser penghitung id supaya id transaksi sendiri tidak kebetulan sama dengan milik A
        val own = b.expense(5_000, roomIndex = b.local.rooms.activeRooms().indexOfFirst { it.name == "Diri" })
        engine(b, remote).syncNow(keluarga(b).id, "device-b")
        b.clearSync()

        b.local.syncBootstrap.markAllDirty()

        assertEquals(setOf(own.value), b.ids(SyncRecordType.TRANSACTION))
    }
}
