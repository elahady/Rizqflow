package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Urutan dan batas "sinkron ulang penuh" ([FullResync]) dengan penyimpanan di memori: reset kursor lebih dulu, hanya ruang
 * bersama aktif, berhenti bila sinkron pribadi gagal, dan hasilnya jujur. Perilaku dengan Room dan mesin sungguhan diuji
 * di `:data` (`FullResyncTest`).
 */
class FullResyncTest {
    private val roomA = RoomId("room-a")
    private val roomB = RoomId("room-b")
    private val roomGone = RoomId("room-gone")

    private fun store() = FakeSyncStore(
        shared = listOf(
            SharedRoomInfo(roomA, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1),
            SharedRoomInfo(roomGone, SharedRoomRole.MEMBER, SharedRoomStatus.REMOVED, 2),
            SharedRoomInfo(roomB, SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 3),
        ),
    ).also { it.cursors[SyncScope.Personal] = 500 }

    private fun cursors() = FakeCursorStore().apply {
        save(roomA.value, 70)
        save(roomB.value, 80)
        save(roomGone.value, 90)
    }.also { it.writes.clear() }

    private val success = PersonalSyncResult.Success(pushed = 1, applied = 10, skipped = 0, pushGated = false)

    @Test
    fun `kursor pribadi dan semua ruang bersama aktif direset ke nol sebelum tarik pertama`() = runBlocking {
        val store = store()
        val cursors = cursors()
        var personalSaw = -1L
        val sharedSaw = mutableMapOf<RoomId, Long>()

        FullResync(
            store = store,
            cursors = cursors,
            personal = {
                personalSaw = store.cursor(SyncScope.Personal)
                success
            },
            shared = { id ->
                sharedSaw[id] = cursors.read(id.value)
                SyncResult.Success(pushed = 0, applied = 2, skipped = 0)
            },
        ).run()

        assertEquals(0L, personalSaw)
        assertEquals(mapOf(roomA to 0L, roomB to 0L), sharedSaw)
        assertEquals(90L, cursors.read(roomGone.value), "ruang yang sudah dikeluarkan tidak disentuh")
    }

    @Test
    fun `hasil lengkap menjumlahkan baris yang diterapkan dari semua cakupan`() = runBlocking {
        val outcome = FullResync(
            store = store(),
            cursors = cursors(),
            personal = { success },
            shared = { SyncResult.Success(pushed = 0, applied = 2, skipped = 0) },
        ).run()

        assertTrue(outcome.complete)
        assertEquals(14, outcome.applied)
        assertNull(outcome.firstError)
        assertEquals(setOf(roomA, roomB), outcome.sharedRooms.keys)
    }

    @Test
    fun `sinkron pribadi yang gagal menghentikan ruang bersama dan kursor tetap nol agar aman diulang`() = runBlocking {
        val store = store()
        var sharedCalls = 0

        val outcome = FullResync(
            store = store,
            cursors = cursors(),
            personal = { PersonalSyncResult.Failed(RemoteError.Unreachable) },
            shared = {
                sharedCalls++
                SyncResult.Success(0, 0, 0)
            },
        ).run()

        assertFalse(outcome.complete)
        assertEquals(RemoteError.Unreachable, outcome.firstError)
        assertEquals(0, sharedCalls)
        assertTrue(outcome.sharedRooms.isEmpty())
        assertEquals(0L, store.cursor(SyncScope.Personal), "sinkron berikutnya melanjutkan dari awal")
    }

    @Test
    fun `satu ruang bersama yang gagal membuat hasil belum lengkap tetapi ruang lain tetap ditarik`() = runBlocking {
        val outcome = FullResync(
            store = store(),
            cursors = cursors(),
            personal = { success },
            shared = { id -> if (id == roomA) SyncResult.Failed(RemoteError.Unexpected(500)) else SyncResult.Success(0, 3, 0) },
        ).run()

        assertFalse(outcome.complete)
        assertEquals(RemoteError.Unexpected(500), outcome.firstError)
        assertEquals(2, outcome.sharedRooms.size)
        assertEquals(10 + 3, outcome.applied)
    }

    @Test
    fun `belum masuk ke server tidak dianggap lengkap dan tidak punya galat remote`() = runBlocking {
        val outcome = FullResync(store(), cursors(), { PersonalSyncResult.NotSignedIn }, { error("tidak dipanggil") }).run()

        assertFalse(outcome.complete)
        assertNull(outcome.firstError)
        assertEquals(0, outcome.applied)
    }

    @Test
    fun `tidak ada penyimpanan yang dihapus, hanya kursor yang ditulis`() = runBlocking {
        val store = store()

        FullResync(store, cursors(), { success }, { SyncResult.Success(0, 0, 0) }).run()

        // FakeSyncStore melempar untuk operasi tulis data apa pun; satu-satunya tulis di store adalah kursor pribadi.
        assertEquals(listOf<Pair<SyncScope, Long>>(SyncScope.Personal to 0L), store.cursorWrites)
    }
}
