package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.SharedRoomActivation
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncResult
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Penandaan ruang bersama ACTIVE setelah pendaftaran anggota di server berhasil (Tahap 9). Dua sisi:
 * penerima undangan (`joinSharedRoom`, peran MEMBER) dan pemilik yang membuka Ruang Keluarga (peran OWNER).
 * Tanpa baris ACTIVE, [com.roziqrizal.rizqflow.data.sync.SharedRoomAutoSync] tidak pernah berjalan untuk ruang itu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRoomActivationTest {
    private lateinit var fx: SyncFixture
    private lateinit var activation: SharedRoomActivation
    private val calls = CopyOnWriteArrayList<RoomId>()
    private val logs = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        fx = SyncFixture(autoSyncScope = CoroutineScope(Dispatchers.Unconfined))
        fx.local.autoSync.attach(sync = { roomId -> calls += roomId; SyncResult.NotSignedIn }, log = { logs += it })
        activation = SharedRoomActivation(fx.local.syncStore)
    }

    @After
    fun tearDown() = fx.close()

    private suspend fun room(name: String): Room = fx.local.rooms.activeRooms().first { it.name == name }

    private suspend fun saveExpense(room: Room): LedgerResult<*> {
        val category = fx.local.rooms.categories(room.id).first()
        return fx.ledger.recordExpense(NewExpense(Money.rupiah(25_000), fx.accountId(), room.id, category.id, fx.today))
    }

    /** Menunggu sampai [condition] benar; gagal bila lewat 5 detik. Sinkron otomatis berjalan di latar. */
    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `penerima undangan yang sukses bergabung jadi ACTIVE dengan peran MEMBER`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        assertNull(fx.local.syncStore.sharedRoom(keluarga.id))

        activation.markActive(keluarga.id, SharedRoomRole.MEMBER, nowMillis = 7_000L)

        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 7_000L), fx.local.syncStore.sharedRoom(keluarga.id))
    }

    @Test
    fun `pemilik yang membuka ruang tanpa baris shared_room jadi ACTIVE dengan peran OWNER`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")

        activation.markActive(keluarga.id, SharedRoomRole.OWNER, nowMillis = 9_000L)

        val info = fx.local.syncStore.sharedRoom(keluarga.id)!!
        assertEquals(SharedRoomRole.OWNER, info.role)
        assertEquals(SharedRoomStatus.ACTIVE, info.status)
        assertEquals(9_000L, info.joinedAtMillis)
        assertEquals(1, fx.local.syncStore.sharedRooms().size)
    }

    @Test
    fun `penandaan ulang mempertahankan peran dan waktu bergabung, dan memulihkan status REMOVED`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.REMOVED, 1_000L))

        // Pendaftaran ulang yang berhasil: peran OWNER dari pemanggil tidak menimpa peran MEMBER yang sudah tersimpan.
        activation.markActive(keluarga.id, SharedRoomRole.OWNER, nowMillis = 5_000L)

        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 1_000L), fx.local.syncStore.sharedRoom(keluarga.id))
    }

    @Test
    fun `setelah penerima ditandai ACTIVE, pengeluaran di ruang itu memicu sinkron otomatis`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.MEMBER)

        assertTrue(saveExpense(keluarga) is LedgerResult.Success)

        awaitUntil { calls.isNotEmpty() }
        assertEquals(listOf(keluarga.id), calls.toList())
        assertEquals(emptyList<String>(), logs.toList())
    }

    @Test
    fun `setelah pemilik ditandai ACTIVE, pengeluaran di ruang itu memicu sinkron otomatis`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.OWNER)

        assertTrue(saveExpense(keluarga) is LedgerResult.Success)

        awaitUntil { calls.isNotEmpty() }
        assertEquals(listOf(keluarga.id), calls.toList())
    }

    @Test
    fun `peran dari server menimpa OWNER lokal yang keliru dan mempertahankan waktu bergabung`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        // Celah lama: anggota yang membuka layar Ruang Keluarga tercatat OWNER lokal tanpa syarat.
        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(keluarga.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1_000L))

        activation.applyServerRole(keluarga.id, SharedRoomRole.MEMBER, nowMillis = 5_000L)

        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 1_000L), fx.local.syncStore.sharedRoom(keluarga.id))
    }

    @Test
    fun `peran dari server mengaktifkan ruang yang belum punya baris atau berstatus REMOVED`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.applyServerRole(keluarga.id, SharedRoomRole.OWNER, nowMillis = 3_000L)
        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 3_000L), fx.local.syncStore.sharedRoom(keluarga.id))

        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(keluarga.id, SharedRoomRole.OWNER, SharedRoomStatus.REMOVED, 3_000L))
        activation.applyServerRole(keluarga.id, SharedRoomRole.OWNER, nowMillis = 8_000L)
        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 3_000L), fx.local.syncStore.sharedRoom(keluarga.id))
    }

    @Test
    fun `keluar menandai LEFT dengan mempertahankan peran dan waktu bergabung`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.MEMBER, nowMillis = 2_000L)
        assertEquals(SharedRoomStatus.ACTIVE, activation.statusOf(keluarga.id))

        activation.markLeft(keluarga.id)

        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.LEFT, 2_000L), fx.local.syncStore.sharedRoom(keluarga.id))
        assertEquals(SharedRoomStatus.LEFT, activation.statusOf(keluarga.id))
    }

    @Test
    fun `keluar tidak menghapus data ruang dan transaksinya, dan idempoten`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.MEMBER)
        assertTrue(saveExpense(keluarga) is LedgerResult.Success)
        val before = fx.local.transactions.between(fx.today, fx.today).size
        assertTrue(before > 0)

        activation.markLeft(keluarga.id)
        activation.markLeft(keluarga.id)

        assertEquals(before, fx.local.transactions.between(fx.today, fx.today).size)
        assertEquals(keluarga.name, room("Keluarga").name)
        assertEquals(1, fx.local.syncStore.sharedRooms().size)
    }

    @Test
    fun `setelah keluar, pengeluaran di ruang itu tidak lagi memicu sinkron`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.MEMBER)
        activation.markLeft(keluarga.id)

        assertTrue(saveExpense(keluarga) is LedgerResult.Success)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `keluar pada ruang yang belum pernah ditandai tidak membuat baris apa pun`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")

        activation.markLeft(keluarga.id)

        assertNull(activation.statusOf(keluarga.id))
        assertEquals(0, fx.local.syncStore.sharedRooms().size)
    }

    @Test
    fun `bergabung lagi lewat undangan memulihkan ACTIVE dan mempertahankan peran lama`() = runBlocking<Unit> {
        val keluarga = room("Keluarga")
        activation.markActive(keluarga.id, SharedRoomRole.MEMBER, nowMillis = 2_000L)
        activation.markLeft(keluarga.id)

        activation.markActive(keluarga.id, SharedRoomRole.MEMBER, nowMillis = 9_000L)

        assertEquals(SharedRoomInfo(keluarga.id, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 2_000L), fx.local.syncStore.sharedRoom(keluarga.id))
    }

    @Test
    fun `sebelum ditandai ACTIVE, pengeluaran di ruang itu tidak memicu sinkron`() = runBlocking<Unit> {
        // Kontrol untuk dua tes di atas: tanpa penandaan, ruang bukan ruang bersama sehingga pemicu diam.
        val keluarga = room("Keluarga")

        assertTrue(saveExpense(keluarga) is LedgerResult.Success)

        assertTrue(calls.isEmpty())
    }
}
