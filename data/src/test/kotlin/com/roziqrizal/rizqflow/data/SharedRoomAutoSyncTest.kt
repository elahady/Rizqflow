package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

/**
 * Sinkron otomatis ruang bersama (Tahap 9): pemicu dipanggil setelah pengeluaran di ruang bersama tersimpan, TIDAK
 * dipanggil untuk ruang pribadi, dan tidak pernah menghalangi penyimpanan lokal. Sinkron sungguhan (SyncEngine,
 * `final`) diganti fungsi palsu lewat `attach`.
 *
 * Lingkup `Unconfined` dipakai supaya sinkron berjalan di utas tes. Tetapi query Room itu suspend dan bisa pindah
 * utas, jadi tes yang menunggu hasil asinkron memakai [awaitUntil] (batas waktu) dan tidak membaca `calls`/`logs`
 * begitu saja sesudah penyimpanan kembali.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRoomAutoSyncTest {
    private lateinit var fx: SyncFixture
    private val calls = CopyOnWriteArrayList<RoomId>()
    private val logs = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        fx = SyncFixture(autoSyncScope = CoroutineScope(Dispatchers.Unconfined))
        fx.local.autoSync.attach(sync = { roomId -> calls += roomId; SyncResult.NotSignedIn }, log = { logs += it })
    }

    @After
    fun tearDown() = fx.close()

    private suspend fun room(name: String): Room = fx.local.rooms.activeRooms().first { it.name == name }

    private suspend fun markShared(room: Room, status: SharedRoomStatus = SharedRoomStatus.ACTIVE) =
        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.MEMBER, status, 1_000L))

    private suspend fun saveExpense(room: Room): LedgerResult<*> {
        val category = fx.local.rooms.categories(room.id).first()
        return fx.ledger.recordExpense(NewExpense(Money.rupiah(25_000), fx.accountId(), room.id, category.id, fx.today))
    }

    /** Menunggu sampai [condition] benar; gagal bila lewat 5 detik. Dipakai untuk hasil yang berjalan di latar. */
    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `pengeluaran di ruang bersama memicu sinkron, dan transaksinya sudah tersimpan saat sinkron berjalan`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        markShared(keluarga)
        val visibleWhenSynced = CompletableDeferred<Boolean>()
        fx.local.autoSync.attach(
            sync = { roomId ->
                calls += roomId
                // Query ini berjalan setelah penyimpanan komit; hasilnya harus sudah terlihat.
                visibleWhenSynced.complete(fx.local.transactions.between(fx.today, fx.today).isNotEmpty())
                SyncResult.NotSignedIn
            },
            log = { logs += it },
        )

        val result = saveExpense(keluarga)

        assertTrue(result is LedgerResult.Success)
        assertEquals(listOf(keluarga.id), calls.toList())
        assertTrue(withTimeout(5_000) { visibleWhenSynced.await() }, "sinkron harus berjalan SETELAH transaksi komit, bukan sebelumnya")
        assertEquals(emptyList<String>(), logs.toList(), "galat tertelan di pemicu")
    }

    @Test
    fun `pengeluaran di ruang pribadi tidak memicu sinkron`() = runBlocking<Unit> {
        // Tidak ada baris shared_room untuk Diri dan Memberi: keduanya ruang pribadi. Pemeriksaan status dilakukan
        // sebelum sinkron dijadwalkan, jadi tidak ada yang berjalan di latar yang perlu ditunggu.
        assertTrue(saveExpense(room("Diri")) is LedgerResult.Success)
        assertTrue(saveExpense(room("Memberi")) is LedgerResult.Success)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ruang yang statusnya sudah keluar tidak memicu sinkron`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        markShared(keluarga, SharedRoomStatus.REMOVED)
        assertTrue(saveExpense(keluarga) is LedgerResult.Success)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `penyimpanan tidak menunggu sinkron yang masih berjalan`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        markShared(keluarga)
        val gate = CompletableDeferred<Unit>()
        fx.local.autoSync.attach(
            sync = { roomId -> calls += roomId; gate.await(); SyncResult.NotSignedIn },
            log = { logs += it },
        )

        val result = saveExpense(keluarga)

        // Sinkron sudah dimulai tetapi masih menunggu gerbang, dan penyimpanan lokal sudah kembali.
        assertTrue(result is LedgerResult.Success)
        awaitUntil { calls.size == 1 }
        assertEquals(listOf(keluarga.id), calls.toList())
        assertEquals(1, fx.local.transactions.between(fx.today, fx.today).size)
        gate.complete(Unit)
    }

    @Test
    fun `galat sinkron dicatat saja dan tidak menggagalkan penyimpanan`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        markShared(keluarga)
        fx.local.autoSync.attach(
            sync = { throw IllegalStateException("jaringan palsu") },
            log = { logs += it },
        )

        val result = saveExpense(keluarga)

        assertTrue(result is LedgerResult.Success)
        awaitUntil { logs.size == 1 }
        assertEquals(1, fx.local.transactions.between(fx.today, fx.today).size)
        assertTrue(logs.single().contains("IllegalStateException"))
    }

    @Test
    fun `hasil gagal dari sinkron dicatat saja`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        markShared(keluarga)
        fx.local.autoSync.attach(
            sync = { SyncResult.Failed(RemoteError.Unreachable) },
            log = { logs += it },
        )

        assertTrue(saveExpense(keluarga) is LedgerResult.Success)
        awaitUntil { logs.size == 1 }
        assertTrue(logs.single().contains("Unreachable"))
    }

    @Test
    fun `saat ruang kerja dibuka hanya ruang bersama yang aktif yang disinkronkan`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        val lain = room("Diri")
        markShared(keluarga)
        markShared(lain, SharedRoomStatus.LEFT)

        fx.local.autoSync.onWorkspaceOpened()

        // Hanya ruang aktif yang dijadwalkan, jadi begitu ruang itu terpanggil tidak ada lagi yang tertinggal.
        awaitUntil { calls.isNotEmpty() }
        assertEquals(listOf(keluarga.id), calls.toList())
    }

    @Test
    fun `tanpa sinkron terpasang pemicu diam dan penyimpanan tetap berhasil`() = runBlocking<Unit> {
        // Ruang kerja yang belum merakit SyncEngine: tidak ada fungsi sinkron, jadi tidak boleh melempar.
        val bare = SyncFixture(autoSyncScope = CoroutineScope(Dispatchers.Unconfined))
        try {
            val keluarga = bare.local.rooms.activeRooms().first { it.name == "Keluarga" }
            bare.local.syncStore.saveSharedRoom(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 1L))
            val category = bare.local.rooms.categories(keluarga.id).first()
            val result = bare.ledger.recordExpense(NewExpense(Money.rupiah(10_000), bare.accountId(), keluarga.id, category.id, bare.today))
            assertTrue(result is LedgerResult.Success)
            assertEquals(1, bare.local.transactions.between(bare.today, bare.today).size)
        } finally {
            bare.close()
        }
    }
}
