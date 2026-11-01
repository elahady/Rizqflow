package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.JsonTransactionSyncCodec
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.LocalSyncHistoryReader
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class IdentityRoomKeys : RoomKeyProvider {
    private val aead = PersonalIdentityAead()
    override fun getOrCreateRoomKey(roomId: RoomId) = aead
    override fun exportRoomKey(roomId: RoomId) = ByteArray(0)
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) = Unit
    override fun rotateRoomKey(roomId: RoomId) = aead
}

internal class MapCursorStore : SyncCursorStore {
    val map = mutableMapOf<String, Long>()
    override fun read(roomId: String) = map[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { map[roomId] = cursor }
}

/**
 * Pindah pengeluaran KELUAR dari ruang bersama terhadap Room sungguhan (sync-desain.md bagian A, Tahap 2): repositori
 * mencatat tombstone per (transaksi, ruang) dalam transaksi database yang sama dengan edit, tombstone gugur bila
 * pengeluaran kembali ke ruang itu, dan dua klien Room yang saling sinkron konvergen. Hanya terbukti di JVM (Robolectric),
 * belum di perangkat atau server hidup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRoomMoveOutTest {
    private lateinit var a: SyncFixture
    private lateinit var b: SyncFixture
    private val calls = CopyOnWriteArrayList<RoomId>()

    @Before
    fun setUp() {
        a = SyncFixture(autoSyncScope = CoroutineScope(Dispatchers.Unconfined))
        b = SyncFixture() // id berurutan sama di kedua sisi: ruang, kategori, dan akun identik
        a.local.autoSync.attach(sync = { roomId -> calls += roomId; SyncResult.NotSignedIn }, log = {})
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    private suspend fun room(f: SyncFixture, name: String): Room = f.local.rooms.activeRooms().first { it.name == name }

    private suspend fun markShared(f: SyncFixture, room: Room, status: SharedRoomStatus = SharedRoomStatus.ACTIVE) =
        f.local.syncStore.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.OWNER, status, 1_000L))

    private suspend fun spend(f: SyncFixture, room: Room): MoneyTransaction {
        val category = f.local.rooms.categories(room.id).first()
        val result = f.ledger.recordExpense(NewExpense(Money.rupiah(25_000), f.accountId(), room.id, category.id, f.today))
        return (assertIs<LedgerResult.Success<*>>(result)).value as MoneyTransaction
    }

    /** Menyunting ruang (dan kategori ruang itu) seperti layar edit; cap suntingan naik supaya LWW lokal jelas. */
    private suspend fun moveTo(f: SyncFixture, id: TransactionId, room: Room, updatedAt: Long = 9_000_000L) {
        val current = checkNotNull(f.local.transactions.find(id))
        val category = f.local.rooms.categories(room.id).first()
        f.local.transactions.update(current.copy(roomId = room.id, categoryId = category.id, updatedAtMillis = updatedAt))
    }

    private suspend fun tombstones(f: SyncFixture, room: Room) = f.local.transactions.pendingTombstones(room.id).map { it.transactionId }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    // ------------------------------------------------------------------ repositori

    @Test
    fun `pindah keluar dari ruang bersama aktif ke ruang pribadi mencatat tombstone dan melepas dari antrean kirim ruang lama`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)
        a.local.transactions.markSynced(listOf(tx.id), 5_000L)
        assertEquals(emptyList(), tombstones(a, keluarga))

        moveTo(a, tx.id, diri)

        assertEquals(listOf(tx.id), tombstones(a, keluarga))
        assertTrue(a.local.transactions.pendingSync(keluarga.id).isEmpty(), "pengeluaran sudah bukan bagian ruang bersama")
        assertEquals(diri.id, a.local.transactions.find(tx.id)?.roomId, "salinan pribadi tetap ada di ruang barunya")
    }

    @Test
    fun `pindah antar dua ruang bersama mencatat tombstone ruang asal dan versi hidup di ruang tujuan`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val memberi = room(a, "Memberi")
        markShared(a, keluarga)
        markShared(a, memberi)
        val tx = spend(a, keluarga)
        a.local.transactions.markSynced(listOf(tx.id), 5_000L)

        moveTo(a, tx.id, memberi)

        assertEquals(listOf(tx.id), tombstones(a, keluarga))
        assertEquals(emptyList(), tombstones(a, memberi))
        assertEquals(listOf(tx.id), a.local.transactions.pendingSync(memberi.id).map { it.transaction.id })
    }

    @Test
    fun `pindah masuk dari ruang pribadi ke ruang bersama tidak mencatat tombstone dan menjadikannya antrean kirim`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        val tx = spend(a, diri)

        moveTo(a, tx.id, keluarga)

        assertEquals(emptyList(), tombstones(a, diri))
        assertEquals(emptyList(), tombstones(a, keluarga))
        assertEquals(listOf(tx.id), a.local.transactions.pendingSync(keluarga.id).map { it.transaction.id })
    }

    @Test
    fun `pindah antar ruang yang bukan bersama atau sudah berstatus keluar tidak mencatat tombstone`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        val memberi = room(a, "Memberi")
        val tx = spend(a, diri)
        moveTo(a, tx.id, memberi)
        assertEquals(emptyList(), tombstones(a, diri))

        markShared(a, keluarga, SharedRoomStatus.REMOVED)
        val other = spend(a, keluarga)
        moveTo(a, other.id, diri)
        assertEquals(emptyList(), tombstones(a, keluarga), "ruang yang sudah ditinggalkan tidak punya jalur kirim lagi")
    }

    @Test
    fun `pindah dua kali sebelum sempat sinkron menyisakan satu tombstone per ruang bersama yang ditinggalkan`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val memberi = room(a, "Memberi")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        markShared(a, memberi)
        val tx = spend(a, keluarga)

        moveTo(a, tx.id, memberi, updatedAt = 8_000_000L)
        moveTo(a, tx.id, diri, updatedAt = 9_000_000L)

        assertEquals(listOf(tx.id), tombstones(a, keluarga))
        assertEquals(listOf(tx.id), tombstones(a, memberi))
        a.local.transactions.clearPendingTombstones(keluarga.id, listOf(tx.id))
        assertEquals(emptyList(), tombstones(a, keluarga))
        assertEquals(listOf(tx.id), tombstones(a, memberi), "membersihkan satu ruang tidak menyentuh ruang lain")
    }

    @Test
    fun `kembali ke ruang asal dan simpan ulang id yang sama menggugurkan tombstone yang menunggu`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)

        moveTo(a, tx.id, diri, updatedAt = 8_000_000L)
        assertEquals(listOf(tx.id), tombstones(a, keluarga))
        moveTo(a, tx.id, keluarga, updatedAt = 9_000_000L)
        assertEquals(emptyList(), tombstones(a, keluarga), "kembali ke ruang yang sama: tombstone gugur")

        // Hapus lalu urungkan (simpan ulang id yang sama): tombstone hapus yang belum terkirim juga gugur.
        val current = checkNotNull(a.local.transactions.find(tx.id))
        a.local.transactions.delete(tx.id)
        assertEquals(listOf(tx.id), tombstones(a, keluarga))
        a.local.transactions.save(current)
        assertEquals(emptyList(), tombstones(a, keluarga))
    }

    @Test
    fun `suntingan tanpa pindah ruang, baris pasangan, dan pemasukan tidak menerbitkan tombstone`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)
        a.local.transactions.update(checkNotNull(a.local.transactions.find(tx.id)).copy(note = "diubah", updatedAtMillis = 9_000_000L))
        assertEquals(emptyList(), tombstones(a, keluarga))

        // Baris pasangan (origin SHARED) bukan milik lokal: memindahkannya tidak menerbitkan tombstone atas nama kita.
        val category = a.local.rooms.categories(keluarga.id).first()
        val peer = tx.copy(id = TransactionId("peer-1"), origin = TransactionOrigin.SHARED)
        a.local.transactions.save(peer.copy(roomId = keluarga.id, categoryId = category.id))
        moveTo(a, peer.id, diri)
        assertEquals(emptyList(), tombstones(a, keluarga))
    }

    @Test
    fun `pindah keluar memicu sinkron otomatis ruang lama supaya tombstone cepat terkirim`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        val diri = room(a, "Diri")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)
        awaitUntil { calls.isNotEmpty() }
        calls.clear()

        moveTo(a, tx.id, diri)

        awaitUntil { calls.isNotEmpty() }
        assertEquals(listOf(keluarga.id), calls.toList())
    }

    // ------------------------------------------------------------------ dua klien Room

    private fun engine(f: SyncFixture, remote: MemoryPersonalRemote, cursors: MapCursorStore) = SyncCoordinator(
        f.local.transactions, f.local.rooms, f.local.accounts, IdentityRoomKeys(), remote, JsonTransactionSyncCodec(),
        PersonalMemoryToken(), cursors, issueStore = f.local.syncStore,
    )

    @Test
    fun `dua klien Room konvergen saat pengeluaran dipindah keluar ke ruang pribadi`() = runBlocking {
        val remote = MemoryPersonalRemote()
        val keluargaA = room(a, "Keluarga")
        val keluargaB = room(b, "Keluarga")
        assertEquals(keluargaA.id, keluargaB.id)
        markShared(a, keluargaA)
        markShared(b, keluargaB)
        val engineA = engine(a, remote, MapCursorStore())
        val engineB = engine(b, remote, MapCursorStore())
        val tx = spend(a, keluargaA)
        engineA.syncNow(keluargaA.id, "device-a")
        engineB.syncNow(keluargaB.id, "device-b")
        assertEquals(25_000L, b.local.transactions.roomTotals(b.today, b.today).spent.getValue(keluargaB.id).minor, "sanity: B menerima pengeluaran A")

        moveTo(a, tx.id, room(a, "Diri"))
        val result = engineA.syncNow(keluargaA.id, "device-a") as SyncResult.Success
        assertEquals(1, result.pushed)
        assertTrue(tombstones(a, keluargaA).isEmpty(), "tombstone dibuang setelah terkirim")
        engineB.syncNow(keluargaB.id, "device-b")

        assertNull(b.local.transactions.find(tx.id), "B ikut menghapus pengeluaran yang dipindah keluar")
        assertEquals(null, b.local.transactions.roomTotals(b.today, b.today).spent[keluargaB.id])
        assertEquals(room(a, "Diri").id, a.local.transactions.find(tx.id)?.roomId)
        // Tidak satu baris pun di server berasal dari ruang lain dan tidak ada yang membocorkan ruang pribadi.
        assertTrue(remote.stored.all { it.roomId == keluargaA.id.value })

        // Sinkron lagi di kedua sisi: tidak ada unggahan baru dan tidak ada yang berubah.
        assertEquals(0, (engineA.syncNow(keluargaA.id, "device-a") as SyncResult.Success).pushed)
        assertEquals(0, (engineB.syncNow(keluargaB.id, "device-b") as SyncResult.Success).pushed)
    }

    @Test
    fun `dua klien Room konvergen saat pengeluaran pindah antar dua ruang bersama, apa pun urutan tariknya`() = runBlocking {
        val remote = MemoryPersonalRemote()
        val keluargaA = room(a, "Keluarga")
        val memberiA = room(a, "Memberi")
        val keluargaB = room(b, "Keluarga")
        val memberiB = room(b, "Memberi")
        markShared(a, keluargaA)
        markShared(a, memberiA)
        markShared(b, keluargaB)
        markShared(b, memberiB)
        val engineA = engine(a, remote, MapCursorStore())
        val engineB = engine(b, remote, MapCursorStore())
        val tx = spend(a, keluargaA)
        engineA.syncNow(keluargaA.id, "device-a")
        engineB.syncNow(keluargaB.id, "device-b")
        engineB.syncNow(memberiB.id, "device-b")

        moveTo(a, tx.id, memberiA)
        engineA.syncNow(keluargaA.id, "device-a") // tombstone di ruang asal
        engineA.syncNow(memberiA.id, "device-a") // versi hidup di ruang tujuan
        // Ruang tujuan lebih dulu: baris B sudah bergeser ke sana saat tombstone ruang asal tiba, dan tidak boleh ikut terhapus.
        engineB.syncNow(memberiB.id, "device-b")
        engineB.syncNow(keluargaB.id, "device-b")

        val pulled = b.local.transactions.find(tx.id)
        assertEquals(memberiB.id, pulled?.roomId, "baris berakhir di ruang tujuan")
        assertEquals(TransactionOrigin.SHARED, pulled?.origin)
        assertEquals(1, b.local.transactions.between(b.today, b.today).count { it.id == tx.id })
        assertEquals(25_000L, b.local.transactions.roomTotals(b.today, b.today).spent.getValue(memberiB.id).minor)
        assertEquals(null, b.local.transactions.roomTotals(b.today, b.today).spent[keluargaB.id])
    }

    // ------------------------------------------------------------------ sync_issue di Room dan layar Riwayat sinkron

    @Test
    fun `baris ruang bersama yang gagal dibuka tercatat di sync_issue sekali saja dan terbaca pembaca Riwayat sinkron`() = runBlocking {
        val remote = MemoryPersonalRemote()
        val keluargaA = room(a, "Keluarga")
        val keluargaB = room(b, "Keluarga")
        markShared(a, keluargaA)
        markShared(b, keluargaB)
        spend(a, keluargaA)
        spend(a, keluargaA)
        engine(a, remote, MapCursorStore()).syncNow(keluargaA.id, "device-a")
        remote.stored[0] = remote.stored[0].copy(payload = "###bukan-base64###")

        val cursors = MapCursorStore()
        val engineB = engine(b, remote, cursors)
        val first = engineB.syncNow(keluargaB.id, "device-b") as SyncResult.Success
        assertEquals(1, first.applied)
        assertEquals(1, first.skipped)

        // Tarik ulang dari awal (mis. sinkron ulang penuh): baris server yang sama tidak menggandakan catatan.
        cursors.map.clear()
        engineB.syncNow(keluargaB.id, "device-b")

        val issues = b.local.syncStore.issues(SyncScope.SharedRoom(keluargaB.id))
        assertEquals(1, issues.size)
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, issues.single().kind)
        assertEquals(remote.stored[0].id, issues.single().serverId)
        assertTrue(issues.single().detail?.contains("25") != true, "keterangan tanpa isi transaksi")

        val history = LocalSyncHistoryReader(b.local.syncStore, cursors, pendingShared = { 0 }, roomName = { "Keluarga" }).read()
        assertEquals(1, history.issueTotal, "muncul di Riwayat sinkron tanpa mengubah layarnya")
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, history.issues.single().issue.kind)
    }
}
