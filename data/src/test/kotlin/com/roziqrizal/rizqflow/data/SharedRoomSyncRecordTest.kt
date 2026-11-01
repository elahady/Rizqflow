package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.SharedRoomActivation
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SharedRecordType
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Status kotor sinkron ruang bersama di `sync_record` (cakupan `r:<ruang>`, tipe `shared_expense`, `record_id` = id
 * transaksi lokal), sejak skema v11. Tes ini memeriksa isi tabelnya langsung: tiap penulisan lewat repositori menandai
 * rekaman yang tepat dan hanya itu, ruang pribadi tidak menerbitkan apa pun, dan pembaca (`pendingSync`,
 * `pendingTombstones`, `markSyncedIfUnchanged`, `clearPendingTombstones`) konsisten dengan isinya.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRoomSyncRecordTest {
    private lateinit var f: SyncFixture
    private val type = SharedRecordType.SHARED_EXPENSE.wire

    @Before
    fun setUp() {
        f = SyncFixture()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun room(name: String): Room = f.local.rooms.activeRooms().first { it.name == name }

    private suspend fun share(room: Room) = SharedRoomActivation(f.local.syncStore).markActive(room.id, SharedRoomRole.OWNER, nowMillis = 1_000L)

    private suspend fun spend(room: Room, amount: Long = 25_000): MoneyTransaction {
        val category = f.local.rooms.categories(room.id).first()
        val result = f.ledger.recordExpense(NewExpense(Money.rupiah(amount), f.accountId(), room.id, category.id, f.today))
        return (assertIs<LedgerResult.Success<*>>(result)).value as MoneyTransaction
    }

    private fun shared() = f.rows().filter { it.scope.startsWith("r:") }

    private fun record(room: Room, id: String) = f.rows().firstOrNull { it.scope == "r:${room.id.value}" && it.type == type && it.id == id }

    @Test
    fun `ruang yang belum dibagi tidak menerbitkan rekaman pada simpan, ubah, dan hapus`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val tx = spend(keluarga)
        f.local.transactions.update(tx.copy(note = "diubah", updatedAtMillis = tx.updatedAtMillis + 1))
        f.local.transactions.delete(tx.id)

        assertTrue(shared().isEmpty(), "ruang pribadi tidak boleh punya rekaman r:*")
        assertTrue(f.local.transactions.pendingTombstones(keluarga.id).isEmpty())
    }

    @Test
    fun `simpan dan ubah di ruang bersama menandai satu rekaman hidup yang kotor dengan cap yang naik`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)

        val first = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(first.dirty && !first.deleted)
        assertTrue(first.changedAt > 0)

        f.nowMillis += 10
        f.local.transactions.update(tx.copy(note = "diubah", updatedAtMillis = tx.updatedAtMillis + 1))

        val second = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(second.dirty && !second.deleted)
        assertTrue(second.changedAt > first.changedAt, "setiap penulisan menaikkan changed_at (lantai SyncClock)")
        assertEquals(1, shared().size, "satu baris per rekaman, bukan per penulisan")
        assertEquals(listOf(tx.id), f.local.transactions.pendingSync(keluarga.id).map { it.transaction.id })
    }

    @Test
    fun `hapus menggantikan rekaman hidup dengan tombstone pada baris yang sama`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        f.nowMillis += 10

        f.local.transactions.delete(tx.id)

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.deleted && row.dirty)
        assertEquals(1, shared().size, "tombstone dan versi hidup adalah satu baris yang saling menggantikan")
        assertTrue(f.local.transactions.pendingSync(keluarga.id).isEmpty())
        assertEquals(listOf(tx.id), f.local.transactions.pendingTombstones(keluarga.id).map { it.transactionId })
    }

    @Test
    fun `menyimpan ulang id yang sama setelah dihapus menggugurkan tombstone`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        f.local.transactions.delete(tx.id)
        f.nowMillis += 10

        f.local.transactions.save(tx.copy(updatedAtMillis = tx.updatedAtMillis + 5))

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.dirty && !row.deleted, "urungkan hapus: versi hidup menggantikan tombstone")
        assertTrue(f.local.transactions.pendingTombstones(keluarga.id).isEmpty())
        assertEquals(1, f.local.transactions.pendingSync(keluarga.id).size)
    }

    @Test
    fun `pindah ke ruang pribadi menerbitkan tombstone di ruang lama saja`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val diri = room("Diri")
        share(keluarga)
        val tx = spend(keluarga)
        f.markAllClean()

        f.local.transactions.update(tx.copy(roomId = diri.id, categoryId = f.local.rooms.categories(diri.id).first().id, updatedAtMillis = tx.updatedAtMillis + 1))

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.deleted && row.dirty)
        assertNull(record(diri, tx.id.value), "ruang pribadi tidak menerbitkan apa pun")
        assertEquals(1, shared().size)
    }

    @Test
    fun `pindah antar dua ruang bersama menerbitkan tombstone di yang lama dan versi hidup di yang baru`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val diri = room("Diri")
        share(keluarga)
        share(diri)
        val tx = spend(keluarga)
        f.markAllClean()

        f.local.transactions.update(tx.copy(roomId = diri.id, categoryId = f.local.rooms.categories(diri.id).first().id, updatedAtMillis = tx.updatedAtMillis + 1))

        assertTrue(assertNotNull(record(keluarga, tx.id.value)).deleted)
        val live = assertNotNull(record(diri, tx.id.value))
        assertTrue(live.dirty && !live.deleted)
        assertEquals(listOf(tx.id), f.local.transactions.pendingSync(diri.id).map { it.transaction.id })
        assertEquals(listOf(tx.id), f.local.transactions.pendingTombstones(keluarga.id).map { it.transactionId })
    }

    @Test
    fun `pemasukan dan baris pasangan tidak pernah menerbitkan rekaman ruang`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        f.income()
        val mine = spend(keluarga)
        f.markAllClean()

        // Baris pasangan hasil pull ditulis lewat applyFromSync: bukan edit lokal.
        val pair = mine.copy(id = com.roziqrizal.rizqflow.domain.model.TransactionId("pair-1"), origin = TransactionOrigin.SHARED)
        f.local.transactions.applyFromSync(pair.id, pair, changedAtMillis = 5_000, deleted = false, fromRoom = keluarga.id)

        assertEquals(listOf(mine.id.value), shared().map { it.id }, "hanya pengeluaran buatan sendiri yang punya rekaman")
        assertTrue(f.local.transactions.pendingSync(keluarga.id).isEmpty())
    }

    @Test
    fun `aktivasi menandai riwayat kotor sekali dan tidak menyentuh rekaman yang sudah ada`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val old = spend(keluarga)
        val other = spend(keluarga, 30_000)
        assertTrue(shared().isEmpty())

        share(keluarga)
        assertEquals(setOf(old.id.value, other.id.value), shared().map { it.id }.toSet())
        assertTrue(shared().all { it.dirty && !it.deleted && it.pushedChangedAt == null })

        // Salah satu sudah terkirim; aktivasi ulang (idempoten) tidak boleh mengotorinya lagi.
        val sent = assertNotNull(record(keluarga, old.id.value))
        f.local.transactions.markSyncedIfUnchanged(f.local.transactions.pendingSync(keluarga.id).filter { it.transaction.id == old.id }, 0L)
        assertTrue(!assertNotNull(record(keluarga, old.id.value)).dirty)
        share(keluarga)
        val afterReactivation = assertNotNull(record(keluarga, old.id.value))
        assertTrue(!afterReactivation.dirty, "aktivasi ulang tidak mengotori rekaman yang sudah terkirim")
        assertEquals(sent.changedAt, afterReactivation.changedAt)
        assertEquals(2, shared().size)
    }

    @Test
    fun `suntingan selama push tidak ikut ditandai terkirim karena changed_at sudah naik`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        val collected = f.local.transactions.pendingSync(keluarga.id)
        f.nowMillis += 50

        // Suntingan masuk sesudah pengumpulan, sebelum penandaan terkirim.
        f.local.transactions.update(tx.copy(note = "saat push", updatedAtMillis = tx.updatedAtMillis + 1))
        f.local.transactions.markSyncedIfUnchanged(collected, 0L)

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.dirty, "versi baru belum terkirim: tetap kotor")
        assertEquals(1, f.local.transactions.pendingSync(keluarga.id).size)
    }

    @Test
    fun `membersihkan tombstone yang terkirim tidak menghapus versi hidup yang disimpan ulang selama push`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        f.local.transactions.delete(tx.id)
        val collected = f.local.transactions.pendingTombstones(keluarga.id).map { it.transactionId }
        f.nowMillis += 50

        f.local.transactions.save(tx.copy(updatedAtMillis = tx.updatedAtMillis + 9)) // disimpan ulang selama push
        f.local.transactions.clearPendingTombstones(keluarga.id, collected)

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.dirty && !row.deleted, "versi hidup baru tetap menunggu kirim")
    }

    @Test
    fun `tombstone terkirim membersihkan penanda kotornya`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        f.local.transactions.delete(tx.id)

        f.local.transactions.clearPendingTombstones(keluarga.id, listOf(tx.id))

        val row = assertNotNull(record(keluarga, tx.id.value))
        assertTrue(row.deleted && !row.dirty && row.pushedChangedAt == row.changedAt)
        assertTrue(f.local.transactions.pendingTombstones(keluarga.id).isEmpty())
    }

    @Test
    fun `versi masuk yang menang menggantikan baris lokal dan membersihkan penanda kotornya`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        val tx = spend(keluarga)
        assertTrue(assertNotNull(record(keluarga, tx.id.value)).dirty)

        // Perangkat lain milik penulis yang sama mengirim versi lebih baru: versi lokal yang menunggu sudah kalah.
        val incoming = tx.copy(note = "dari perangkat lain", updatedAtMillis = tx.updatedAtMillis + 100)
        f.local.transactions.applyFromSync(tx.id, incoming, changedAtMillis = tx.updatedAtMillis + 100, deleted = false, fromRoom = keluarga.id)

        assertTrue(!assertNotNull(record(keluarga, tx.id.value)).dirty, "tidak boleh dikirim balik menimpa versi masuk")
        assertEquals("dari perangkat lain", f.local.transactions.find(tx.id)?.note)
    }

    @Test
    fun `ruang yang keluar tetap mencatat suntingan dan hapus tetapi tidak pindah keluar`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val diri = room("Diri")
        share(keluarga)
        val tx = spend(keluarga)
        SharedRoomActivation(f.local.syncStore).markLeft(keluarga.id)
        f.markAllClean()
        f.nowMillis += 10

        // Suntingan tetap dicatat (untuk bergabung kembali), hapus juga; pindah keluar dari ruang non-aktif tidak.
        f.local.transactions.update(tx.copy(note = "saat keluar", updatedAtMillis = tx.updatedAtMillis + 1))
        assertTrue(assertNotNull(record(keluarga, tx.id.value)).dirty)

        f.markAllClean()
        f.nowMillis += 10
        f.local.transactions.update(tx.copy(roomId = diri.id, categoryId = f.local.rooms.categories(diri.id).first().id, updatedAtMillis = tx.updatedAtMillis + 2))
        assertTrue(!assertNotNull(record(keluarga, tx.id.value)).deleted, "ruang non-aktif tidak menerbitkan tombstone pindah keluar")

        val other = spend(keluarga, 7_000)
        f.local.transactions.delete(other.id)
        assertTrue(assertNotNull(record(keluarga, other.id.value)).deleted, "hapus tetap dicatat untuk ruang yang pernah dibagi")
    }

    @Test
    fun `rekaman ruang bersama tidak mengganggu hitungan dan pembaca cakupan pribadi`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        share(keluarga)
        spend(keluarga)

        assertEquals(0, f.local.syncStore.dirtyCount(com.roziqrizal.rizqflow.domain.sync.SyncScope.SharedRoom(RoomId("tidak-ada"))))
        assertTrue(f.local.syncStore.dirtyCount(com.roziqrizal.rizqflow.domain.sync.SyncScope.SharedRoom(keluarga.id)) > 0)
        assertTrue(f.rows().filter { it.scope == "a" }.none { it.type == type }, "tipe shared_expense hanya di cakupan ruang")
    }
}
