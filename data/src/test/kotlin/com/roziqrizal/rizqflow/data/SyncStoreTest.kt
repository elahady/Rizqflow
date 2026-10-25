package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.ApplyDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingVersion
import com.roziqrizal.rizqflow.domain.sync.SentVersion
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncIssue
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.isSyncApplying
import com.roziqrizal.rizqflow.domain.sync.withSyncApplying
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Implementasi Room [com.roziqrizal.rizqflow.domain.sync.SyncStore]: aturan urutan total, echo/duplikat/urutan terbalik,
 * tombstone menang, konteks `applying`, `OVERWRITTEN`, dan pembersihan kotor bersyarat setelah push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncStoreTest {
    private lateinit var fx: SyncFixture
    private val store get() = fx.local.syncStore
    private val scope = SyncScope.Personal
    private val type = SyncRecordType.TRANSACTION
    private val me = "dev-a"

    @Before
    fun setUp() {
        fx = SyncFixture()
        fx.clearSync()
    }

    @After
    fun tearDown() = fx.close()

    private fun incoming(changedAt: Long, deleted: Boolean = false, device: String = "dev-b", author: String? = null, serverId: Long? = null) =
        IncomingVersion(changedAt, deleted, device, author, serverId)

    private suspend fun apply(id: String, v: IncomingVersion, counter: IntArray = IntArray(1)): ApplyDecision =
        store.applyIncoming(scope, type, id, v, me) { counter[0]++ }

    // ---- aturan urutan total pada penyimpanan sungguhan

    @Test
    fun `versi pertama diterapkan dan meta tersimpan bersih dengan id perangkat penulisnya`() = runBlocking {
        val calls = IntArray(1)
        assertEquals(ApplyDecision.APPLIED, apply("t1", incoming(100, author = "member-9"), calls))
        assertEquals(1, calls[0])
        val meta = assertNotNull(store.meta(scope, type, "t1"))
        assertEquals(100L, meta.changedAtMillis)
        assertFalse(meta.dirty)
        assertFalse(meta.deleted)
        assertEquals("dev-b", meta.deviceId)
        assertEquals("member-9", meta.author)
        assertEquals(100L, meta.pushedChangedAtMillis)
        assertEquals(0, store.dirtyCount(scope))
    }

    @Test
    fun `duplikat dan echo diabaikan tanpa memanggil penerapan`() = runBlocking {
        apply("t1", incoming(100))
        val calls = IntArray(1)
        assertEquals(ApplyDecision.IGNORED, apply("t1", incoming(100), calls), "duplikat persis")
        assertEquals(0, calls[0])

        // Echo versi lokal yang baru dikirim: cap sama, id perangkat ini.
        fx.nowMillis = 500
        val stamp = store.markDirty(scope, type, "t2", false, fx.clock)!!
        assertEquals(ApplyDecision.IGNORED, apply("t2", incoming(stamp, device = me), calls))
        assertEquals(0, calls[0])
        assertTrue(store.meta(scope, type, "t2")!!.dirty, "echo tidak membersihkan kotor; itu tugas markPushed")
    }

    @Test
    fun `urutan terbalik aman versi lama tidak menimpa versi baru`() = runBlocking {
        apply("t1", incoming(200))
        val calls = IntArray(1)
        assertEquals(ApplyDecision.IGNORED, apply("t1", incoming(100), calls))
        assertEquals(0, calls[0])
        assertEquals(200L, store.meta(scope, type, "t1")!!.changedAtMillis)
    }

    @Test
    fun `tombstone menang pada cap yang sama walau deviceId lebih kecil, dan versi live sesudahnya diabaikan`() = runBlocking {
        apply("t1", incoming(100, deleted = false, device = "dev-z"))
        assertEquals(ApplyDecision.APPLIED, apply("t1", incoming(100, deleted = true, device = "dev-a")))
        assertTrue(store.meta(scope, type, "t1")!!.deleted)
        assertEquals(ApplyDecision.IGNORED, apply("t1", incoming(100, deleted = false, device = "dev-zz")))
        assertTrue(store.meta(scope, type, "t1")!!.deleted, "tombstone tidak dihidupkan kembali pada cap sama")
        // Edit bercap lebih baru menghidupkan kembali (LWW murni).
        assertEquals(ApplyDecision.APPLIED, apply("t1", incoming(101, deleted = false)))
        assertFalse(store.meta(scope, type, "t1")!!.deleted)
    }

    @Test
    fun `pada cap dan status hapus sama deviceId terbesar menang`() = runBlocking {
        apply("t1", incoming(100, device = "dev-b"))
        assertEquals(ApplyDecision.IGNORED, apply("t1", incoming(100, device = "dev-a")))
        assertEquals(ApplyDecision.APPLIED, apply("t1", incoming(100, device = "dev-c")))
        assertEquals("dev-c", store.meta(scope, type, "t1")!!.deviceId)
    }

    @Test
    fun `versi masuk yang menang atas edit lokal kotor mengalahkannya dan mencatat OVERWRITTEN`() = runBlocking {
        val localStamp = store.markDirty(scope, type, "t1", false, fx.clock)!!
        val calls = IntArray(1)

        val decision = apply("t1", incoming(localStamp + 4_000, device = "dev-b", serverId = 77), calls)

        assertEquals(ApplyDecision.APPLIED_OVER_LOCAL_EDIT, decision)
        assertEquals(1, calls[0])
        val meta = store.meta(scope, type, "t1")!!
        assertFalse(meta.dirty, "edit lokal yang kalah tidak dikirim lagi")
        assertEquals(localStamp + 4_000, meta.changedAtMillis)
        val issue = store.issues(scope).single()
        assertEquals(SyncIssueKind.OVERWRITTEN, issue.kind)
        assertEquals("t1", issue.recordId)
        assertEquals("transaction", issue.recordType)
        assertEquals(77L, issue.serverId)
        assertTrue("changed_at=$localStamp" in issue.detail!!, issue.detail)
    }

    @Test
    fun `versi masuk atas rekaman bersih tidak mencatat OVERWRITTEN`() = runBlocking {
        apply("t1", incoming(100))
        assertEquals(ApplyDecision.APPLIED, apply("t1", incoming(200)))
        assertTrue(store.issues(scope).isEmpty())
    }

    @Test
    fun `edit lokal kotor yang lebih baru tetap menang dan tetap menunggu dikirim`() = runBlocking {
        fx.nowMillis = 9_000
        store.markDirty(scope, type, "t1", false, fx.clock)
        assertEquals(ApplyDecision.IGNORED, apply("t1", incoming(1_000)))
        assertTrue(store.meta(scope, type, "t1")!!.dirty)
        assertTrue(store.issues(scope).isEmpty())
    }

    @Test
    fun `penerapan yang melempar membatalkan meta dan catatan masalah sekaligus`() = runBlocking {
        val localStamp = store.markDirty(scope, type, "t1", false, fx.clock)!!
        val before = store.meta(scope, type, "t1")

        assertFailsWith<IllegalStateException> {
            store.applyIncoming(scope, type, "t1", incoming(localStamp + 4_000), me) { error("gagal menerapkan") }
        }

        assertEquals(before, store.meta(scope, type, "t1"))
        assertTrue(store.issues(scope).isEmpty(), "catatan OVERWRITTEN ikut dibatalkan")
    }

    @Test
    fun `semua urutan kedatangan acak menghasilkan keadaan akhir yang sama`() = runBlocking {
        val versions = listOf(
            incoming(100, device = "dev-a"), incoming(100, device = "dev-b"), incoming(100, deleted = true, device = "dev-a"),
            incoming(99, device = "dev-z"), incoming(101, device = "dev-a"), incoming(101, deleted = true, device = "dev-b"),
        )
        var expected: Triple<Long, Boolean, String?>? = null
        val random = kotlin.random.Random(25)
        repeat(30) { round ->
            val f = SyncFixture()
            try {
                for (v in versions.shuffled(random)) f.local.syncStore.applyIncoming(scope, type, "t1", v, "local") {}
                val meta = f.local.syncStore.meta(scope, type, "t1")!!
                val actual = Triple(meta.changedAtMillis, meta.deleted, meta.deviceId)
                if (expected == null) expected = actual
                assertEquals(expected, actual, "putaran $round")
            } finally {
                f.close()
            }
        }
        assertEquals(Triple(101L, true, "dev-b"), expected)
    }

    // ---- konteks applying

    @Test
    fun `penerapan versi masuk berjalan di konteks applying jadi penulisan data tidak menandai kotor`() = runBlocking {
        val account = fx.local.accounts.activeAccounts().first()
        fx.clearSync()
        var sawApplying = false

        store.applyIncoming(scope, SyncRecordType.ACCOUNT, account.id.value, incoming(777), me) {
            sawApplying = isSyncApplying()
            fx.local.accounts.save(account.copy(name = "Hasil pull"))
        }

        assertTrue(sawApplying)
        assertEquals("Hasil pull", fx.local.accounts.find(account.id)!!.name)
        val row = fx.row(SyncRecordType.ACCOUNT, account.id.value)!!
        assertFalse(row.dirty, "hasil pull tidak boleh kotor")
        assertEquals(777L, row.changedAt)
        assertEquals(1, fx.rows().size, "hanya meta rekaman yang diterapkan; tidak ada jejak lain")
        assertFalse(isSyncApplying(), "konteks applying tidak bocor keluar")
    }

    @Test
    fun `pull lalu push menghasilkan nol unggahan`() = runBlocking {
        val account = fx.local.accounts.activeAccounts().first()
        fx.clearSync()
        store.applyIncoming(scope, SyncRecordType.ACCOUNT, account.id.value, incoming(777), me) { fx.local.accounts.save(account.copy(name = "A")) }
        store.applyIncoming(scope, SyncRecordType.CATEGORY, "cat-x", incoming(778), me) {}
        assertEquals(0, store.dirtyCount(scope))
        assertTrue(store.dirtyRecords(scope).isEmpty())
    }

    @Test
    fun `markDirty di konteks applying tidak melakukan apa pun`() = runBlocking {
        val result = withSyncApplying { store.markDirty(scope, type, "t1", false, fx.clock) }
        assertNull(result)
        assertNull(store.meta(scope, type, "t1"))
    }

    // ---- pembersihan kotor bersyarat (balapan markSynced)

    @Test
    fun `markPushed membersihkan kotor bila changed_at masih sama dengan yang dikirim`() = runBlocking {
        fx.nowMillis = 1_000
        val stamp = store.markDirty(scope, type, "t1", false, fx.clock)!!
        val cleared = store.markPushed(scope, listOf(SentVersion(type, "t1", stamp)))
        assertEquals(1, cleared)
        val meta = store.meta(scope, type, "t1")!!
        assertFalse(meta.dirty)
        assertEquals(stamp, meta.pushedChangedAtMillis)
    }

    @Test
    fun `edit selama push berjalan tetap kotor dan terkirim di putaran berikutnya`() = runBlocking {
        fx.nowMillis = 1_000
        val sentStamp = store.markDirty(scope, type, "t1", false, fx.clock)!!
        val collected = store.dirtyRecords(scope).single()
        assertEquals(sentStamp, collected.changedAtMillis)

        // Push berjalan... pengguna mengedit rekaman yang sama.
        fx.nowMillis = 2_000
        val editedStamp = store.markDirty(scope, type, "t1", false, fx.clock)!!
        assertTrue(editedStamp > sentStamp)

        // Ack push lama tiba.
        val cleared = store.markPushed(scope, listOf(SentVersion(type, "t1", sentStamp)))

        assertEquals(0, cleared, "versi baru belum terkirim")
        val meta = store.meta(scope, type, "t1")!!
        assertTrue(meta.dirty, "edit selama push tidak boleh ikut terhapus kotornya")
        assertEquals(editedStamp, meta.changedAtMillis)
        assertEquals(sentStamp, meta.pushedChangedAtMillis)
        assertEquals(listOf(editedStamp), store.dirtyRecords(scope).map { it.changedAtMillis })

        // Putaran berikutnya mengirim versi baru lalu bersih.
        assertEquals(1, store.markPushed(scope, listOf(SentVersion(type, "t1", editedStamp))))
        assertEquals(0, store.dirtyCount(scope))
    }

    @Test
    fun `markPushed rekaman yang tidak ada tidak membuat baris`() = runBlocking {
        assertEquals(0, store.markPushed(scope, listOf(SentVersion(type, "hantu", 5))))
        assertNull(store.meta(scope, type, "hantu"))
    }

    @Test
    fun `pushed_changed_at tidak pernah turun oleh ack yang datang terlambat`() = runBlocking {
        fx.nowMillis = 1_000
        val s1 = store.markDirty(scope, type, "t1", false, fx.clock)!!
        fx.nowMillis = 2_000
        val s2 = store.markDirty(scope, type, "t1", false, fx.clock)!!
        store.markPushed(scope, listOf(SentVersion(type, "t1", s2)))
        store.markPushed(scope, listOf(SentVersion(type, "t1", s1)))
        val meta = store.meta(scope, type, "t1")!!
        assertEquals(s2, meta.pushedChangedAtMillis)
        assertFalse(meta.dirty)
    }

    @Test
    fun `rekaman kotor diurutkan dari yang tertua dan dibatasi`() = runBlocking {
        fx.nowMillis = 3_000; store.markDirty(scope, type, "c", false, fx.clock)
        fx.nowMillis = 1_000; store.markDirty(scope, type, "a", false, fx.clock)
        fx.nowMillis = 2_000; store.markDirty(scope, type, "b", false, fx.clock)
        // Jam pernah lebih besar, jadi cap "a" dan "b" naik mengikuti cap global yang tidak turun; urutan stempel tetap menaik.
        val all = store.dirtyRecords(scope)
        assertEquals(listOf("a", "b", "c").toSet(), all.map { it.recordId }.toSet())
        assertEquals(all.map { it.changedAtMillis }.sorted(), all.map { it.changedAtMillis })
        assertEquals(2, store.dirtyRecords(scope, limit = 2).size)
        assertEquals(3, store.dirtyCount(scope))
    }

    // ---- kursor, masalah, ruang bersama

    @Test
    fun `kursor per cakupan tersimpan di database dan dimulai dari nol`() = runBlocking {
        val room = SyncScope.SharedRoom(RoomId("room-1"))
        assertEquals(0L, store.cursor(scope))
        store.setCursor(scope, 41)
        store.setCursor(room, 7)
        store.setCursor(scope, 42)
        assertEquals(42L, store.cursor(scope))
        assertEquals(7L, store.cursor(room))
    }

    @Test
    fun `masalah sinkron dicatat, disaring per cakupan, dan dipangkas menurut umur dan jumlah`() = runBlocking {
        val room = SyncScope.SharedRoom(RoomId("room-1"))
        fun issue(s: SyncScope, at: Long, kind: SyncIssueKind = SyncIssueKind.DEFERRED_MISSING_REF) =
            SyncIssue(s, "debt", "d-$at", kind, createdAtMillis = at, detail = "rujukan belum ada", rawRow = "baris-mentah")
        store.recordIssue(issue(scope, 1_000))
        store.recordIssue(issue(room, 2_000, SyncIssueKind.TAMPERED_OR_CORRUPT))
        store.recordIssue(issue(scope, 3_000))
        store.recordIssue(issue(scope, 4_000, SyncIssueKind.UNSUPPORTED_VERSION))

        assertEquals(4, store.issues().size)
        assertEquals(3, store.issues(scope).size)
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, store.issues(room).single().kind)
        assertEquals("baris-mentah", store.issues(room).single().rawRow)

        // Umur: yang lebih tua dari 1_500 ms dari sekarang (5_000) dibuang -> 1_000, 2_000, 3_000 hilang.
        assertEquals(3, store.pruneIssues(nowMillis = 5_000, maxRows = 100, maxAgeMillis = 1_500))
        assertEquals(listOf("d-4000"), store.issues().map { it.recordId })

        // Jumlah: sisakan paling banyak 2 baris terbaru.
        store.recordIssue(issue(scope, 5_000)); store.recordIssue(issue(scope, 5_001)); store.recordIssue(issue(scope, 5_002))
        assertEquals(2, store.pruneIssues(nowMillis = 5_002, maxRows = 2, maxAgeMillis = 1_000_000))
        assertEquals(listOf("d-5001", "d-5002"), store.issues().map { it.recordId })
    }

    @Test
    fun `ruang bersama tersimpan dan ikut terhapus bersama ruangnya`() = runBlocking {
        val room = fx.roomIds().first()
        store.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1_000))
        assertEquals(SharedRoomRole.OWNER, store.sharedRoom(room.id)!!.role)
        store.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.OWNER, SharedRoomStatus.REMOVED, 1_000))
        assertEquals(SharedRoomStatus.REMOVED, store.sharedRooms().single().status)
        assertNull(store.sharedRoom(RoomId("tidak-ada")))

        // Kunci asing ON DELETE CASCADE dari room (tidak ada API hapus ruang; diuji langsung di SQL).
        fx.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        fx.db.openHelper.writableDatabase.execSQL("DELETE FROM money_transaction")
        fx.db.openHelper.writableDatabase.execSQL("DELETE FROM allocation_entry")
        fx.db.openHelper.writableDatabase.execSQL("DELETE FROM quick_favorite")
        fx.db.openHelper.writableDatabase.execSQL("DELETE FROM room WHERE id = '${room.id.value}'")
        assertTrue(store.sharedRooms().isEmpty())
    }

    @Test
    fun `shared_room menolak ruang yang tidak ada`() = runBlocking<Unit> {
        assertFailsWith<Exception> {
            store.saveSharedRoom(SharedRoomInfo(RoomId("tidak-ada"), SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, 1))
        }
    }
    // ---- penangan yang menelan galat repositori

    @Test
    fun `apply yang menelan galat repositori tidak dilaporkan berhasil dan tidak menyimpan setengah`() = runBlocking<Unit> {
        val account = fx.local.accounts.activeAccounts().first()
        val room = fx.roomIds().first()
        val bad = com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction(
            id = com.roziqrizal.rizqflow.domain.model.TransactionId("bad-1"),
            kind = com.roziqrizal.rizqflow.domain.model.TransactionKind.EXPENSE,
            amount = com.roziqrizal.rizqflow.domain.money.Money.rupiah(1_000),
            accountId = com.roziqrizal.rizqflow.domain.model.AccountId("akun-tidak-ada"), // melanggar FK
            roomId = room.id,
            categoryId = fx.local.rooms.categories(room.id).first().id,
            occurredOn = fx.today,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        fx.clearSync()

        // Penangan buruk: menulis sesuatu yang sah, lalu MENELAN galat repositori dan terus seolah berhasil.
        val outcome = runCatching {
            store.applyIncoming(scope, SyncRecordType.ACCOUNT, account.id.value, incoming(9_000_000_000_000L), me) {
                fx.local.accounts.save(account.copy(name = "Setengah jalan"))
                try {
                    fx.local.transactions.save(bad)
                } catch (_: Exception) {
                    // ditelan
                }
            }
        }

        // Perilaku nyata Room: transaksi luar ditandai gagal oleh transaksi bersarang, jadi SEMUANYA dibatalkan.
        assertEquals(account.name, fx.local.accounts.find(account.id)!!.name, "penulisan yang sah ikut dibatalkan")
        assertNull(store.meta(scope, SyncRecordType.ACCOUNT, account.id.value), "meta tidak tersimpan")
        // Dan karena itu applyIncoming TIDAK BOLEH mengembalikan APPLIED (kursor tidak boleh maju): ia melempar.
        assertTrue(outcome.isFailure, "applyIncoming tidak boleh melapor berhasil: ${outcome.getOrNull()}")
        assertTrue(outcome.exceptionOrNull() is com.roziqrizal.rizqflow.domain.sync.SyncApplyAbortedException, outcome.exceptionOrNull().toString())
    }

    @Test
    fun `apply yang membiarkan galat merambat membatalkan bersih dengan galat aslinya`() = runBlocking<Unit> {
        val account = fx.local.accounts.activeAccounts().first()
        fx.clearSync()
        val failure = assertFailsWith<IllegalStateException> {
            store.applyIncoming(scope, SyncRecordType.ACCOUNT, account.id.value, incoming(9_000_000_000_000L), me) {
                fx.local.accounts.save(account.copy(name = "Setengah jalan"))
                error("galat penangan")
            }
        }
        assertEquals("galat penangan", failure.message)
        assertEquals(account.name, fx.local.accounts.find(account.id)!!.name)
        assertNull(store.meta(scope, SyncRecordType.ACCOUNT, account.id.value))
    }
}
