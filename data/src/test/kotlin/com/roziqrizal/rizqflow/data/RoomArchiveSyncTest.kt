package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.AllocationConfigPayloadDto
import com.roziqrizal.rizqflow.data.sync.AllocationConfigSyncHandler
import com.roziqrizal.rizqflow.data.sync.decodeAs
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.RuleService
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Arsip ruang mengubah isi rekaman ALLOCATION_CONFIG: payload-nya dibangun dari aturan RUANG AKTIF saja
 * (`RoomDao.rules` menggabung ke `room.archived = 0`). Karena itu mengarsipkan atau memulihkan ruang harus menandainya
 * kotor walau tabel `allocation_rule` tidak disentuh, kalau tidak perangkat lain tidak pernah tahu ruang itu tak lagi
 * menerima alokasi (Tahap 2 sync-desain.md, utang yang dicatat 2026-10-25).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomArchiveSyncTest {
    private lateinit var fx: SyncFixture
    private lateinit var rules: RuleService

    @Before
    fun setUp() {
        fx = SyncFixture()
        rules = RuleService(fx.local.rooms, PlanEntitlements(emptySet()), fx.newId)
        fx.clearSync()
    }

    @After
    fun tearDown() = fx.close()

    private fun configRow() = fx.row(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)

    private suspend fun configRuleRoomIds(): Set<String> {
        val outgoing = AllocationConfigSyncHandler(fx.db, fx.local.syncStore).collectDirty(SyncScope.Personal).single()
        return outgoing.data.decodeAs(AllocationConfigPayloadDto.serializer()).rules.map { it.roomId }.toSet()
    }

    @Test
    fun `mengarsipkan ruang menandai konfigurasi alokasi kotor dan payload tidak lagi memuat ruang itu`() = runBlocking<Unit> {
        val target = fx.roomIds()[1]

        assertIs<LedgerResult.Success<*>>(rules.archiveRoom(target.id))

        val row = assertNotNull(configRow(), "ALLOCATION_CONFIG harus tercatat")
        assertTrue(row.dirty)
        assertFalse(target.id.value in configRuleRoomIds(), "payload hanya memuat ruang aktif")
        assertTrue(fx.row(SyncRecordType.ROOM, target.id.value)!!.dirty, "rekaman ruang sendiri tetap kotor")
    }

    @Test
    fun `memulihkan ruang terarsip menandai konfigurasi alokasi kotor`() = runBlocking<Unit> {
        val target = fx.roomIds()[1]
        rules.archiveRoom(target.id)
        fx.markAllClean()
        assertFalse(configRow()!!.dirty)

        assertIs<LedgerResult.Success<*>>(rules.restoreRoom(target.id))

        assertTrue(configRow()!!.dirty)
        assertTrue(target.id.value in configRuleRoomIds(), "ruang yang dipulihkan kembali ke payload (0%)")
    }

    @Test
    fun `mengarsipkan dengan Urungkan lalu mengurungkan menandai konfigurasi kotor di kedua arah`() = runBlocking<Unit> {
        val target = fx.roomIds()[2]
        val undo = (rules.archiveWithUndo(target.id) as LedgerResult.Success).value
        assertTrue(configRow()!!.dirty)
        fx.markAllClean()

        assertIs<LedgerResult.Success<*>>(rules.undoArchive(undo))

        assertTrue(configRow()!!.dirty)
        assertEquals(setOf(target.id.value), configRuleRoomIds().intersect(setOf(target.id.value)))
    }

    @Test
    fun `mengubah nama atau urutan ruang tanpa mengubah status arsip tidak menandai konfigurasi alokasi`() = runBlocking<Unit> {
        val room = fx.roomIds().first()

        fx.local.rooms.saveRooms(listOf(room.copy(name = "Nama baru")))
        rules.moveRoom(fx.roomIds()[1].id, -1)

        assertEquals(null, configRow(), "tidak ada perubahan isi konfigurasi")
        assertTrue(fx.row(SyncRecordType.ROOM, room.id.value)!!.dirty)
    }

    @Test
    fun `menyimpan ruang yang sudah terarsip dengan status sama tidak menandai konfigurasi`() = runBlocking<Unit> {
        val target = fx.roomIds()[1]
        rules.archiveRoom(target.id)
        fx.markAllClean()

        val archived = fx.local.rooms.find(target.id)!!
        fx.local.rooms.saveRooms(listOf(archived.copy(name = "Masih terarsip")))

        assertFalse(configRow()!!.dirty)
    }
}
