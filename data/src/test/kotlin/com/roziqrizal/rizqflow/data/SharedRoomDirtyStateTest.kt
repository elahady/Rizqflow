package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.JsonTransactionSyncCodec
import com.roziqrizal.rizqflow.data.sync.SharedRoomActivation
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Membungkus server palsu: [failPush] menggagalkan push, [duringPush] dijalankan SETELAH baris tersimpan dan SEBELUM jawaban kembali. */
private class HookedRemote(private val inner: MemoryPersonalRemote) : SyncRemote {
    var failPush = false
    var duringPush: (suspend () -> Unit)? = null
    var pushRequests = 0

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        pushRequests++
        if (failPush) return RemoteResult.Failure(RemoteError.Unreachable)
        val result = inner.push(apiToken, entries)
        duringPush?.let {
            duringPush = null
            it()
        }
        return result
    }

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> =
        inner.pull(apiToken, deviceId, after, limit)
}

/**
 * Mengunci perilaku status kotor ruang bersama terhadap Room sungguhan. Status ini disimpan di `sync_record` (cakupan
 * `r:<ruang>`, tipe `shared_expense`) sejak skema v11 (sync-desain.md bagian I Tahap 2 butir (d)); sebelumnya di kolom
 * `money_transaction.synced_at` dan tabel `pending_room_tombstone`. Perilaku yang tertulis di sini sama sebelum dan sesudah
 * pemindahan, dengan SATU perbedaan yang disengaja: ruang yang belum dibagi tidak menerbitkan apa pun (riwayatnya ditandai
 * kotor ketika ruang mulai dibagi). Hanya terbukti di JVM (Robolectric), belum di perangkat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRoomDirtyStateTest {
    private lateinit var a: SyncFixture
    private lateinit var b: SyncFixture

    @Before
    fun setUp() {
        a = SyncFixture()
        b = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    private suspend fun room(f: SyncFixture, name: String): Room = f.local.rooms.activeRooms().first { it.name == name }

    /** Jalur produksi: SharedRoomActivation menyimpan status ACTIVE sekaligus menandai riwayat ruang kotor. */
    private suspend fun markShared(f: SyncFixture, room: Room) =
        SharedRoomActivation(f.local.syncStore).markActive(room.id, SharedRoomRole.OWNER, nowMillis = 1_000L)

    private suspend fun spend(f: SyncFixture, room: Room, amount: Long = 25_000): MoneyTransaction {
        val category = f.local.rooms.categories(room.id).first()
        val result = f.ledger.recordExpense(NewExpense(Money.rupiah(amount), f.accountId(), room.id, category.id, f.today))
        return (assertIs<LedgerResult.Success<*>>(result)).value as MoneyTransaction
    }

    private fun engine(f: SyncFixture, remote: SyncRemote) = SyncCoordinator(
        f.local.transactions, f.local.rooms, f.local.accounts, IdentityRoomKeys(), remote, JsonTransactionSyncCodec(),
        PersonalMemoryToken(), MapCursorStore(), issueStore = f.local.syncStore,
    )

    private suspend fun pending(f: SyncFixture, roomId: RoomId) = f.local.transactions.pendingSync(roomId).size

    @Test
    fun `ruang yang baru dibagikan mengirim seluruh riwayat pengeluarannya, termasuk yang dicatat sebelum dibagikan`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        spend(a, keluarga)
        spend(a, keluarga)
        spend(a, room(a, "Diri"))
        assertEquals(0, pending(a, keluarga.id), "ruang yang belum dibagi tidak menerbitkan apa pun")
        assertTrue(a.rows().none { it.scope.startsWith("r:") }, "tidak ada rekaman r:* sebelum ruang dibagi")

        markShared(a, keluarga)
        val remote = MemoryPersonalRemote()
        val result = engine(a, remote).syncNow(keluarga.id, "device-a") as SyncResult.Success

        assertEquals(2, result.pushed, "riwayat ruang itu ikut terkirim; ruang pribadi (Diri) tidak")
        assertEquals(0, pending(a, keluarga.id))
        assertTrue(remote.stored.all { it.roomId == keluarga.id.value })
    }

    @Test
    fun `mesin sync baru dengan kursor hilang tidak mengirim ulang apa yang sudah terkirim`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        markShared(a, keluarga)
        spend(a, keluarga)
        val remote = MemoryPersonalRemote()
        assertEquals(1, (engine(a, remote).syncNow(keluarga.id, "device-a") as SyncResult.Success).pushed)

        // Proses mati lalu dibuka lagi: mesin dan kursor baru, database yang sama.
        val second = engine(a, remote).syncNow(keluarga.id, "device-a") as SyncResult.Success

        assertEquals(0, second.pushed)
        assertEquals(1, remote.stored.size, "tidak ada baris ganda di server")
    }

    @Test
    fun `push yang gagal membiarkan semua baris dan tombstone tetap menunggu lalu terkirim di putaran berikutnya`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        markShared(a, keluarga)
        val kept = spend(a, keluarga)
        val removed = spend(a, keluarga)
        val remote = MemoryPersonalRemote()
        val hooked = HookedRemote(remote)
        val engineA = engine(a, hooked)
        engineA.syncNow(keluarga.id, "device-a")
        a.local.transactions.update(kept.copy(note = "diubah", updatedAtMillis = kept.updatedAtMillis + 1))
        a.local.transactions.delete(removed.id)

        hooked.failPush = true
        assertIs<SyncResult.Failed>(engineA.syncNow(keluarga.id, "device-a"))
        assertEquals(1, pending(a, keluarga.id))
        assertEquals(1, a.local.transactions.pendingTombstones(keluarga.id).size)

        hooked.failPush = false
        val retry = engineA.syncNow(keluarga.id, "device-a") as SyncResult.Success
        assertEquals(2, retry.pushed)
        assertEquals(0, pending(a, keluarga.id))
        assertEquals(0, a.local.transactions.pendingTombstones(keluarga.id).size)
    }

    @Test
    fun `suntingan yang masuk selama push berjalan tidak ditandai terkirim dan terkirim di putaran berikutnya`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)
        val hooked = HookedRemote(MemoryPersonalRemote())
        val engineA = engine(a, hooked)
        hooked.duringPush = {
            val current = checkNotNull(a.local.transactions.find(tx.id))
            a.local.transactions.update(current.copy(note = "diubah saat push", updatedAtMillis = current.updatedAtMillis + 10))
        }

        assertEquals(1, (engineA.syncNow(keluarga.id, "device-a") as SyncResult.Success).pushed)
        assertEquals(1, pending(a, keluarga.id), "versi baru belum terkirim, tidak boleh ikut ditandai")

        assertEquals(1, (engineA.syncNow(keluarga.id, "device-a") as SyncResult.Success).pushed)
        assertEquals(0, pending(a, keluarga.id))
    }

    @Test
    fun `baris hasil tarik tidak pernah menunggu kirim, sehingga tarik lalu kirim menghasilkan nol unggahan`() = runBlocking {
        val keluargaA = room(a, "Keluarga")
        val keluargaB = room(b, "Keluarga")
        markShared(a, keluargaA)
        markShared(b, keluargaB)
        spend(a, keluargaA)
        val remote = MemoryPersonalRemote()
        val hookedB = HookedRemote(remote)
        engine(a, remote).syncNow(keluargaA.id, "device-a")

        val pulled = engine(b, hookedB).syncNow(keluargaB.id, "device-b") as SyncResult.Success

        assertEquals(1, pulled.applied)
        assertEquals(0, pulled.pushed)
        assertEquals(0, pending(b, keluargaB.id))
        assertEquals(0, hookedB.pushRequests, "tidak ada permintaan push sama sekali")
    }

    @Test
    fun `hapus lalu sync mengirim satu tombstone, dan sync berikutnya tidak mengirim apa pun`() = runBlocking {
        val keluarga = room(a, "Keluarga")
        markShared(a, keluarga)
        val tx = spend(a, keluarga)
        val remote = MemoryPersonalRemote()
        val engineA = engine(a, remote)
        engineA.syncNow(keluarga.id, "device-a")

        a.local.transactions.delete(tx.id)
        assertEquals(1, (engineA.syncNow(keluarga.id, "device-a") as SyncResult.Success).pushed)
        assertEquals(0, (engineA.syncNow(keluarga.id, "device-a") as SyncResult.Success).pushed)
        assertEquals(listOf(false, true), remote.stored.map { it.deleted })
    }
}
