package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.FullResync
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore
import com.roziqrizal.rizqflow.domain.sync.SyncIssue
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryCursors : SyncCursorStore {
    val values = mutableMapOf<String, Long>()
    override fun read(roomId: String): Long = values[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) {
        values[roomId] = cursor
    }
}

/**
 * "Sinkron ulang penuh" ([FullResync], sync-desain.md bagian I Tahap 7) dengan Room sungguhan dan mesin sinkron pribadi
 * sungguhan: reset kursor lalu tarik ulang dari awal TANPA menghapus data lokal, tidak menggandakan catatan masalah, dan
 * (hanya untuk jalur ini) menarik kembali baris perangkat sendiri yang hilang dari lokal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullResyncTest {
    private lateinit var a: SyncFixture
    private val configured = RecoveryStatus.Configured(Instant.parse("2026-11-01T00:00:00Z"))

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
    }

    private fun count(ledger: LocalLedger, table: String): Int =
        ledger.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun `reset kursor ke nol lalu tarik ulang dari awal, data lokal sama persis sesudahnya`() = runBlocking {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote(pageSize = 3)
        engineFor(a.local, remote, configured).syncNow("device-a")

        val b = emptyLedger()
        val engineB = engineFor(b, remote)
        engineB.syncNow("device-b")
        val tip = b.syncStore.cursor(SyncScope.Personal)
        assertTrue(tip > 0)
        val transactionsBefore = count(b, "money_transaction")
        val syncRecordsBefore = count(b, "sync_record")

        var cursorSeenByPull = -1L
        val outcome = FullResync(
            store = b.syncStore,
            cursors = MemoryCursors(),
            personal = {
                cursorSeenByPull = b.syncStore.cursor(SyncScope.Personal)
                engineB.syncNow("device-b", reapplyOwnRows = true)
            },
            shared = { error("tidak ada ruang bersama") },
        ).run()

        assertEquals(0L, cursorSeenByPull, "tarik dimulai dari awal")
        assertTrue(outcome.complete)
        assertEquals(tip, b.syncStore.cursor(SyncScope.Personal), "kursor kembali ke ujung log server")
        assertEquals(transactionsBefore, count(b, "money_transaction"), "tidak ada transaksi yang hilang atau berganda")
        assertEquals(syncRecordsBefore, count(b, "sync_record"))
        assertEquals(20_000L, b.transactions.find(expenseId)?.amount?.minor)
        assertTrue(b.syncStore.issues().isEmpty())
    }

    @Test
    fun `perubahan lokal yang belum terkirim tetap kotor dan tetap ada`() = runBlocking<Unit> {
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        val b = emptyLedger()
        val engineB = engineFor(b, remote) // kode pemulihan belum dikonfigurasi: push digerbang
        engineB.syncNow("device-b")
        // Edit lokal di B memakai ruang, akun, dan kategori hasil pull.
        val room = b.rooms.activeRooms().first()
        val account = b.accounts.activeAccounts().first()
        val category = b.rooms.categories(room.id).first()
        val ledgerB = LedgerService(b.accounts, b.rooms, b.transactions, { "lokal-b-1" }) { 1_000L }
        val saved = ledgerB.recordExpense(NewExpense(Money.rupiah(9_000), account.id, room.id, category.id, LocalDate.of(2026, 11, 1)))
        val savedId = (saved as LedgerResult.Success).value.id
        assertTrue(b.syncStore.dirtyCount(SyncScope.Personal) > 0)

        val outcome = FullResync(b.syncStore, MemoryCursors(), { engineB.syncNow("device-b", reapplyOwnRows = true) }, { error("tidak ada") }).run()

        assertTrue(outcome.complete)
        assertTrue(b.syncStore.dirtyCount(SyncScope.Personal) > 0, "belum terkirim karena gerbang, dan tidak dihapus")
        assertNotNull(b.transactions.find(savedId))
    }

    @Test
    fun `baris perangkat sendiri yang hilang dari lokal ikut tertarik hanya pada sinkron ulang penuh`() = runBlocking {
        val expenseId = a.expense(12_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")

        // Perangkat yang sama (device-a) setelah berkas databasenya dipulihkan dari cadangan lama: lokal kosong.
        val restored = emptyLedger()
        val engine = engineFor(restored, remote)
        engine.syncNow("device-a")
        assertNull(restored.transactions.find(expenseId), "sinkron biasa melewati baris perangkat sendiri")

        val outcome = FullResync(restored.syncStore, MemoryCursors(), { engine.syncNow("device-a", reapplyOwnRows = true) }, { error("tidak ada") }).run()

        assertTrue(outcome.complete)
        assertEquals(12_000L, restored.transactions.find(expenseId)?.amount?.minor)
    }

    @Test
    fun `masalah tertunda yang sama tidak digandakan oleh tarik ulang`() = runBlocking {
        a.expense(7_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        // Buang baris kategori dari server: transaksi di B tidak pernah menemukan rujukannya (tertunda terus).
        remote.stored.removeAll { it.innerType() == "category" }

        val b = emptyLedger()
        val engineB = engineFor(b, remote)
        engineB.syncNow("device-b")
        val before = b.syncStore.issues()
        assertTrue(before.isNotEmpty() && before.all { it.kind == SyncIssueKind.DEFERRED_MISSING_REF })

        FullResync(b.syncStore, MemoryCursors(), { engineB.syncNow("device-b", reapplyOwnRows = true) }, { error("tidak ada") }).run()

        val after = b.syncStore.issues()
        assertEquals(before.size, after.size, "tidak ada catatan baru untuk baris server yang sama")
        assertEquals(before.map { it.createdAtMillis }.sorted(), after.map { it.createdAtMillis }.sorted(), "umur catatan tidak diulang dari nol")
    }

    @Test
    fun `ruang bersama aktif ikut direset dan ditarik, yang dikeluarkan tidak`() = runBlocking<Unit> {
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        val rooms = a.local.rooms.activeRooms()
        val active = rooms[0].id
        val removed = rooms[1].id
        a.local.syncStore.saveSharedRoom(SharedRoomInfo(active, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1))
        a.local.syncStore.saveSharedRoom(SharedRoomInfo(removed, SharedRoomRole.MEMBER, SharedRoomStatus.REMOVED, 1))
        val cursors = MemoryCursors().apply {
            save(active.value, 50)
            save(removed.value, 60)
        }
        val engine = engineFor(a.local, remote, configured)
        val pulledRooms = mutableListOf<RoomId>()
        val cursorSeen = mutableMapOf<String, Long>()

        val outcome = FullResync(
            store = a.local.syncStore,
            cursors = cursors,
            personal = { engine.syncNow("device-a", reapplyOwnRows = true) },
            shared = { roomId ->
                pulledRooms += roomId
                cursorSeen[roomId.value] = cursors.read(roomId.value)
                SyncResult.Success(pushed = 0, applied = 4, skipped = 0)
            },
        ).run()

        assertEquals(listOf(active), pulledRooms)
        assertEquals(0L, cursorSeen[active.value], "kursor ruang aktif direset sebelum ditarik")
        assertEquals(60L, cursors.read(removed.value), "ruang yang sudah dikeluarkan tidak disentuh")
        assertTrue(outcome.complete)
        assertIs<PersonalSyncResult.Success>(outcome.personal)
    }

    @Test
    fun `catatan masalah dengan id baris server yang sama dihitung satu, tanpa id baris server tetap ditambah`() = runBlocking {
        val store = a.local.syncStore
        fun issue(serverId: Long?, at: Long) =
            SyncIssue(SyncScope.Personal, "transaction", "t-1", SyncIssueKind.DEFERRED_MISSING_REF, serverId = serverId, attempts = 0, createdAtMillis = at)

        val first = store.recordIssue(issue(serverId = 11, at = 1_000))
        val again = store.recordIssue(issue(serverId = 11, at = 9_000))
        val other = store.recordIssue(issue(serverId = 12, at = 2_000))
        store.recordIssue(issue(serverId = null, at = 3_000))
        store.recordIssue(issue(serverId = null, at = 4_000))

        assertEquals(first, again)
        assertTrue(other != first)
        assertEquals(4, store.issues().size)
        assertEquals(1_000L, store.issues().first { it.id == first }.createdAtMillis, "catatan pertama dipertahankan, umurnya tidak diulang")
    }
}
