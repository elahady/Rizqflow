package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.InMemoryLedger
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Tes konvergensi dua klien (atau lebih) untuk ruang bersama, memakai mesin SyncCoordinator sungguhan, AES-GCM JDK
 * sungguhan (AAD bermakna), dan server palsu di memori. Dasar: sync-desain.md bagian A (pindah keluar dari ruang bersama),
 * bagian E (sync_issue), bagian K (strategi pengujian). Hanya terbukti di JVM: belum di perangkat atau server hidup.
 */

/** Server palsu: idempoten seperti `SyncController::push`, penulis pertama, halaman, kegagalan, ack hilang. */
internal class FakeSyncServer : SyncRemote {
    val stored = mutableListOf<SyncLogEntryDownload>()
    private var nextId = 1L
    var failWith: RemoteError? = null

    /** Server menyimpan baris, tetapi jawabannya hilang di jaringan: klien melihat kegagalan dan mengirim ulang. */
    var dropAckOnNextPush = false

    /** Ukuran tiap permintaan push yang sampai (sebelum penghapusan ganda), untuk menegaskan "0 unggahan". */
    val pushSizes = mutableListOf<Int>()

    /** Dijalankan sekali, setelah baris tersimpan dan sebelum jawaban kembali: meniru suntingan pengguna selama push berjalan. */
    var duringPush: (suspend () -> Unit)? = null

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        pushSizes += entries.size
        val dropAck = dropAckOnNextPush
        dropAckOnNextPush = false
        val ids = entries.map { entry ->
            val existing = stored.firstOrNull {
                it.deviceId == entry.deviceId && it.recordId == entry.recordId && it.roomId == entry.roomId &&
                    it.changedAtMillis == entry.changedAtMillis && it.deleted == entry.deleted
            }
            if (existing != null) return@map existing.id
            val id = nextId++
            val author = stored.firstOrNull { it.recordId == entry.recordId && it.roomId == entry.roomId }?.author ?: entry.deviceId
            stored += SyncLogEntryDownload(
                id, entry.roomId, entry.recordType, entry.recordId, entry.deviceId, entry.payload, entry.deleted,
                entry.changedAt, entry.changedAtMillis, author,
            )
            id
        }
        duringPush?.let {
            duringPush = null
            it()
        }
        if (dropAck) return RemoteResult.Failure(RemoteError.Unreachable)
        return RemoteResult.Success(SyncPushResult(ids, ids.max(), null))
    }

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        val page = stored.filter { it.id > after }.sortedBy { it.id }.take(limit)
        return RemoteResult.Success(SyncPullResult(page, page.lastOrNull()?.id ?: after, page.size == limit, null))
    }

    fun replace(index: Int, change: (SyncLogEntryDownload) -> SyncLogEntryDownload) {
        stored[index] = change(stored[index])
    }
}

/** Semua ruang memakai kunci AES-GCM JDK yang sama (kunci ruang dibagi lewat QR): ciphertext antar klien saling terbaca. */
private class SharedAeadKeys : RoomKeyProvider {
    private val aead = JdkAesGcmAead()
    override fun getOrCreateRoomKey(roomId: RoomId) = aead
    override fun exportRoomKey(roomId: RoomId) = ByteArray(0)
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) = Unit
    override fun rotateRoomKey(roomId: RoomId) = aead
}

private class FixedToken : ApiTokenStore {
    override fun read() = "token-1"
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

private class MapCursors : SyncCursorStore {
    val map = mutableMapOf<String, Long>()
    override fun read(roomId: String) = map[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { map[roomId] = cursor }
}

/** Codec format lama tidak dipakai di tes ini; memanggilnya berarti ada baris format lama yang tidak diduga. */
private class UnusedLegacyCodec : TransactionSyncCodec {
    override fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray = error("format lama tidak ditulis lagi")
    override fun decode(bytes: ByteArray): DecodedSyncTransaction = error("tidak ada baris format lama di tes ini")
}

/** `SyncStore` hanya untuk buku catatan masalah (perilaku dedupe sama dengan `RoomSyncStore.recordIssue`); sisanya tidak dipakai SyncCoordinator. */
internal class IssueBookSyncStore : SyncStore {
    val book = mutableListOf<SyncIssue>()
    private var nextId = 1L

    override suspend fun recordIssue(issue: SyncIssue): Long {
        val dup = issue.serverId?.let { serverId ->
            book.firstOrNull { it.scope == issue.scope && it.recordType == issue.recordType && it.recordId == issue.recordId && it.kind == issue.kind && it.serverId == serverId }
        }
        if (dup != null) return dup.id
        val stored = issue.copy(id = nextId++)
        book += stored
        return stored.id
    }

    override suspend fun resolveIssue(id: Long) { book.removeAll { it.id == id } }

    override suspend fun incrementIssueAttempts(id: Long) {
        val index = book.indexOfFirst { it.id == id }
        if (index >= 0) book[index] = book[index].copy(attempts = book[index].attempts + 1)
    }

    override suspend fun issues(scope: SyncScope?): List<SyncIssue> = book.filter { scope == null || it.scope == scope }

    override suspend fun meta(scope: SyncScope, type: SyncRecordType, recordId: String): SyncRecordMeta? = error("tidak dipakai")
    override suspend fun markDirty(scope: SyncScope, type: SyncRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long? = error("tidak dipakai")
    override suspend fun dirtyRecords(scope: SyncScope, limit: Int): List<SyncRecordMeta> = error("tidak dipakai")
    override suspend fun dirtyCount(scope: SyncScope): Int = error("tidak dipakai")
    override suspend fun markPushed(scope: SyncScope, sent: List<SentVersion>): Int = error("tidak dipakai")
    override suspend fun applyIncoming(
        scope: SyncScope,
        type: SyncRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision = error("tidak dipakai")
    override suspend fun cursor(scope: SyncScope): Long = error("tidak dipakai")
    override suspend fun setCursor(scope: SyncScope, afterId: Long) = error("tidak dipakai")
    override suspend fun pruneIssues(nowMillis: Long, maxRows: Int, maxAgeMillis: Long): Int = error("tidak dipakai")
    override suspend fun sharedRoom(roomId: RoomId): SharedRoomInfo? = error("tidak dipakai")
    override suspend fun sharedRooms(): List<SharedRoomInfo> = error("tidak dipakai")
    override suspend fun saveSharedRoom(info: SharedRoomInfo) = error("tidak dipakai")
}

class SharedRoomConvergenceTest {
    private val room1 = RoomId("room-1")
    private val room2 = RoomId("room-2")
    private val personal = RoomId("room-pribadi")
    private val accountId = AccountId("acc-1")

    private fun categoryOf(room: RoomId) = CategoryId("cat-${room.value}")

    /** Satu perangkat: buku besar di memori + mesin sync dengan kursornya sendiri. */
    private inner class Client(val deviceId: String, val server: FakeSyncServer, ledgerNowMillis: Long = 9_000L, issueStore: SyncStore? = null) {
        val ledger = InMemoryLedger(nowMillis = { ledgerNowMillis })
        private val engine = SyncCoordinator(
            ledger, ledger, ledger, SharedAeadKeys(), server, UnusedLegacyCodec(), FixedToken(), MapCursors(),
            SyncClock(deviceNowMillis = { 0L }), issueStore,
        )

        init {
            ledger.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
        }

        fun withRooms(rooms: List<RoomId>, shared: Boolean = true): Client {
            rooms.forEachIndexed { index, room ->
                ledger.roomRows[room] = Room(room, "Ruang ${room.value}", RoomKind.MENCUKUPI, "home", 1, sortOrder = index)
                ledger.categoryRows[categoryOf(room)] = Category(categoryOf(room), room, "Belanja")
                if (shared) ledger.sharedRoomIds += room
            }
            return this
        }

        fun expense(id: String, room: RoomId, updatedAt: Long = 1_000L, note: String? = null) = MoneyTransaction(
            id = TransactionId(id), kind = TransactionKind.EXPENSE, amount = Money(25_000), accountId = accountId, roomId = room,
            categoryId = categoryOf(room), occurredOn = LocalDate.of(2026, 11, 1), note = note, origin = TransactionOrigin.MANUAL,
            createdAtMillis = updatedAt, updatedAtMillis = updatedAt,
        )

        suspend fun record(id: String, room: RoomId, updatedAt: Long = 1_000L) {
            ledger.save(expense(id, room, updatedAt))
        }

        /** Menyunting ruang transaksi (hanya ruang dan kategori ruang itu yang berubah), seperti layar edit. */
        suspend fun moveTo(id: String, room: RoomId, updatedAt: Long = 5_000L) {
            val current = checkNotNull(ledger.find(TransactionId(id)))
            ledger.update(current.copy(roomId = room, categoryId = categoryOf(room), updatedAtMillis = updatedAt))
        }

        suspend fun syncRaw(room: RoomId): SyncResult = engine.syncNow(room, deviceId)

        suspend fun sync(room: RoomId): SyncResult.Success = syncRaw(room) as SyncResult.Success

        fun roomOf(id: String): RoomId? = ledger.transactionRows[TransactionId(id)]?.roomId
    }

    private fun client(name: String, server: FakeSyncServer, first: RoomId, second: RoomId? = null, shared: Boolean = true, issueStore: SyncStore? = null, nowMillis: Long = 9_000L) =
        Client(name, server, nowMillis, issueStore).withRooms(listOfNotNull(first, second), shared = shared)

    // ------------------------------------------------------------------ pindah keluar dari ruang bersama

    @Test
    fun moving_an_expense_out_to_a_non_shared_room_sends_a_tombstone_and_other_members_drop_it() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1).withRooms(listOf(personal), shared = false)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1)
        a.sync(room1)
        b.sync(room1)
        assertEquals(room1, b.roomOf("tx-1"), "sanity: B sudah punya salinannya")

        a.moveTo("tx-1", personal)
        val result = a.sync(room1)

        assertEquals(1, result.pushed, "hanya tombstone yang terkirim")
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty(), "tombstone dibuang setelah terkirim")
        b.sync(room1)
        assertEquals(null, b.roomOf("tx-1"), "B ikut menghapus pengeluaran yang dipindah keluar dari ruang bersama")
        assertEquals(personal, a.roomOf("tx-1"), "A tetap menyimpannya di ruang pribadinya")
    }

    @Test
    fun a_personal_room_expense_never_reaches_the_server_even_after_the_move() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1).withRooms(listOf(personal), shared = false)
        a.record("tx-pribadi", personal)
        a.record("tx-1", room1)
        a.sync(room1)
        a.moveTo("tx-1", personal)
        a.sync(room1)
        a.sync(room1)

        assertTrue(server.stored.all { it.roomId == room1.value }, "tidak ada baris di cakupan selain ruang bersama")
        assertEquals(2, server.stored.size, "satu versi hidup dan satu tombstone untuk tx-1; tx-pribadi tidak pernah dikirim")
        assertTrue(a.ledger.pendingSync(room1).isEmpty())
    }

    @Test
    fun moving_between_two_shared_rooms_converges_in_either_pull_order() = runSuspend {
        for (live2First in listOf(true, false)) {
            val server = FakeSyncServer()
            // Jam hapus (9_000) lebih besar dari cap edit (5_000): tombstone ruang asal TIBA dengan cap lebih besar dari versi
            // hidup di ruang tujuan. Tanpa syarat ruang, urutan "ruang tujuan dulu" menghapus baris yang baru pindah.
            val a = client("device-a", server, room1, room2)
            val b = client("device-b", server, room1, room2)
            a.record("tx-1", room1)
            a.sync(room1)
            b.sync(room1)
            b.sync(room2)
            assertEquals(room1, b.roomOf("tx-1"))

            a.moveTo("tx-1", room2)
            assertEquals(1, a.sync(room1).pushed, "tombstone di ruang asal")
            assertEquals(1, a.sync(room2).pushed, "versi hidup di ruang tujuan")

            if (live2First) {
                b.sync(room2)
                b.sync(room1)
            } else {
                b.sync(room1)
                b.sync(room2)
            }

            assertEquals(room2, b.roomOf("tx-1"), "urutan tarik live2First=$live2First: baris berakhir di ruang tujuan")
            assertEquals(1, b.ledger.transactionRows.size)
        }
    }

    @Test
    fun a_member_of_only_the_origin_room_drops_the_expense_when_it_moves_to_a_room_it_cannot_see() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1, room2)
        val b = client("device-b", server, room1) // B tidak ikut ruang tujuan
        a.record("tx-1", room1)
        a.sync(room1)
        b.sync(room1)

        a.moveTo("tx-1", room2)
        a.sync(room1)
        a.sync(room2)
        b.sync(room1)

        assertEquals(null, b.roomOf("tx-1"))
    }

    @Test
    fun moving_into_a_shared_room_sends_a_live_version_and_no_tombstone() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1).withRooms(listOf(personal), shared = false)
        val b = client("device-b", server, room1)
        a.record("tx-1", personal)
        a.sync(room1)
        assertEquals(0, server.stored.size, "belum ada yang masuk ruang bersama")

        a.moveTo("tx-1", room1)
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty(), "ruang pribadi asal tidak menerbitkan tombstone")
        val result = a.sync(room1)
        b.sync(room1)

        assertEquals(1, result.pushed)
        assertEquals(room1, b.roomOf("tx-1"))
    }

    @Test
    fun moving_back_into_the_origin_room_cancels_the_pending_tombstone() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1).withRooms(listOf(personal), shared = false)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1)
        a.sync(room1)
        b.sync(room1)

        a.moveTo("tx-1", personal)
        assertEquals(1, a.ledger.pendingTombstoneRows.size)
        a.moveTo("tx-1", room1, updatedAt = 6_000L)
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty(), "kembali ke ruang yang sama menggugurkan tombstone")

        val result = a.sync(room1)
        b.sync(room1)

        assertEquals(1, result.pushed, "hanya versi hidup yang terkirim")
        assertTrue(server.stored.none { it.deleted })
        assertEquals(room1, b.roomOf("tx-1"))
    }

    @Test
    fun the_coordinator_itself_drops_a_pending_tombstone_that_a_newer_live_version_supersedes() = runSuspend {
        // Jaring pengaman: bila repositori membiarkan tombstone dan versi hidup menunggu bersamaan di ruang yang sama,
        // keduanya tidak boleh dikirim bersamaan (tombstone yang capnya lebih besar akan mengalahkan versi hidup).
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        a.record("tx-1", room1)
        a.ledger.pendingTombstoneRows[TransactionId("tx-1") to room1] = 9_000L

        val result = a.sync(room1)

        assertEquals(1, result.pushed)
        assertTrue(server.stored.none { it.deleted })
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty())
    }

    @Test
    fun a_chain_of_moves_keeps_one_tombstone_per_shared_room_it_left() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1, room2).withRooms(listOf(personal), shared = false)
        val b = client("device-b", server, room1, room2)
        a.record("tx-1", room1)
        a.sync(room1)
        b.sync(room1)

        a.moveTo("tx-1", room2, updatedAt = 4_000L)
        a.moveTo("tx-1", personal, updatedAt = 5_000L)
        assertEquals(setOf(TransactionId("tx-1") to room1, TransactionId("tx-1") to room2), a.ledger.pendingTombstoneRows.keys)

        assertEquals(1, a.sync(room1).pushed)
        assertEquals(1, a.sync(room2).pushed, "tombstone ruang kedua tidak menimpa yang pertama")
        b.sync(room2)
        b.sync(room1)

        assertEquals(null, b.roomOf("tx-1"))
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty())
    }

    @Test
    fun an_edit_that_keeps_the_room_never_creates_a_tombstone() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        a.record("tx-1", room1)
        a.sync(room1)

        val current = checkNotNull(a.ledger.find(TransactionId("tx-1")))
        a.ledger.update(current.copy(note = "diubah", updatedAtMillis = 5_000L))

        assertTrue(a.ledger.pendingTombstoneRows.isEmpty())
        assertEquals(1, a.sync(room1).pushed)
        assertTrue(server.stored.none { it.deleted })
    }

    @Test
    fun moving_between_non_shared_rooms_creates_no_tombstone() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, personal, shared = false).withRooms(listOf(RoomId("room-pribadi-2")), shared = false)
        a.record("tx-1", personal)
        a.moveTo("tx-1", RoomId("room-pribadi-2"))

        assertTrue(a.ledger.pendingTombstoneRows.isEmpty())
    }

    @Test
    fun a_tombstone_whose_ack_was_lost_is_resent_without_duplicating_on_the_server() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1).withRooms(listOf(personal), shared = false)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1)
        a.sync(room1)
        b.sync(room1)
        a.moveTo("tx-1", personal)

        server.dropAckOnNextPush = true
        assertTrue(a.syncRaw(room1) is SyncResult.Failed, "ack hilang: klien melihat kegagalan")
        assertEquals(1, a.ledger.pendingTombstoneRows.size, "tombstone tetap menunggu karena klien tidak tahu server sudah menyimpannya")
        val rowsAfterFirst = server.stored.size

        val retry = a.sync(room1)
        assertEquals(1, retry.pushed)
        assertEquals(rowsAfterFirst, server.stored.size, "kirim ulang ber-cap sama: server tidak menggandakan")
        assertTrue(a.ledger.pendingTombstoneRows.isEmpty())
        b.sync(room1)
        assertEquals(null, b.roomOf("tx-1"))
    }

    // ------------------------------------------------------------------ konvergensi dasar

    @Test
    fun an_edit_made_while_the_push_is_in_flight_is_not_marked_as_sent_and_goes_out_next_round() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1, updatedAt = 1_000L)
        server.duringPush = {
            val current = checkNotNull(a.ledger.find(TransactionId("tx-1")))
            a.ledger.update(current.copy(note = "diubah saat push", updatedAtMillis = 2_000L))
        }

        assertEquals(1, a.sync(room1).pushed, "versi lama terkirim")
        assertEquals(1, a.ledger.pendingSync(room1).size, "suntingan saat push tetap menunggu, tidak ikut ditandai terkirim")

        assertEquals(1, a.sync(room1).pushed, "versi baru terkirim di putaran berikutnya")
        assertTrue(a.ledger.pendingSync(room1).isEmpty())
        b.sync(room1)
        assertEquals("diubah saat push", b.ledger.transactionRows[TransactionId("tx-1")]?.note)
    }

    @Test
    fun pulling_and_then_syncing_again_uploads_nothing() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1)
        a.record("tx-2", room1)
        a.sync(room1)

        val first = b.sync(room1)
        assertEquals(2, first.applied)
        assertEquals(0, first.pushed, "baris hasil tarik tidak diterbitkan balik")

        val pushesBefore = server.pushSizes.size
        val second = b.sync(room1)
        assertEquals(0, second.pushed)
        assertEquals(0, second.applied)
        assertEquals(pushesBefore, server.pushSizes.size, "tidak ada permintaan push sama sekali bila tak ada yang menunggu")

        val back = a.sync(room1)
        assertEquals(0, back.pushed)
        assertEquals(0, back.applied, "baris yang dikirim A sendiri tidak diterapkan ulang")
    }

    // ------------------------------------------------------------------ sync_issue untuk ruang bersama

    private fun rowOf(server: FakeSyncServer, index: Int = 0) = server.stored[index]

    @Test
    fun a_row_moved_to_another_rooms_scope_fails_to_open_and_is_recorded_as_an_issue() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1, room2)
        val issues = IssueBookSyncStore()
        val b = client("device-b", server, room1, room2, issueStore = issues)
        a.record("tx-1", room1)
        a.sync(room1)
        // Server jahat memindahkan ciphertext ke ruang lain: AAD mengikat cakupan, jadi tidak bisa dibuka di sana.
        server.replace(0) { it.copy(roomId = room2.value) }

        val result = b.sync(room2)

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        val issue = issues.book.single()
        assertEquals(SyncScope.SharedRoom(room2), issue.scope)
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, issue.kind)
        assertEquals(rowOf(server).id, issue.serverId)
        assertEquals("r", issue.recordType, "kolom luar record_type konstan")
        assertTrue(b.ledger.transactionRows.isEmpty())
    }

    @Test
    fun the_same_bad_server_row_is_recorded_only_once_even_if_pulled_again() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        val issues = IssueBookSyncStore()
        val b = client("device-b", server, room1, issueStore = issues)
        a.record("tx-1", room1)
        a.sync(room1)
        server.replace(0) { it.copy(changedAtMillis = (it.changedAtMillis ?: 0L) + 1) } // cap dipalsukan: AAD tidak cocok

        b.sync(room1)
        // Menarik ulang dari awal (mis. "sinkron ulang penuh"): kursor ruang ini kembali ke 0 pada klien baru dengan buku catatan sama.
        val b2 = Client("device-b", server, 9_000L, issues).withRooms(listOf(room1))
        b2.sync(room1)

        assertEquals(1, issues.book.size, "baris server yang sama tidak menggandakan catatan")
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, issues.book.single().kind)
    }

    @Test
    fun a_payload_that_is_not_valid_base64_is_recorded_and_does_not_crash_the_sync() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        val issues = IssueBookSyncStore()
        val b = client("device-b", server, room1, issueStore = issues)
        a.record("tx-1", room1)
        a.record("tx-2", room1)
        a.sync(room1)
        server.replace(0) { it.copy(payload = "###bukan-base64###") }

        val result = b.sync(room1)

        assertEquals(1, result.applied, "baris yang sehat tetap diterapkan")
        assertEquals(1, result.skipped)
        assertEquals(SyncIssueKind.TAMPERED_OR_CORRUPT, issues.book.single().kind)
    }

    @Test
    fun an_envelope_with_a_newer_payload_version_or_an_unknown_type_is_recorded_as_unsupported() = runSuspend {
        val server = FakeSyncServer()
        val issues = IssueBookSyncStore()
        val b = client("device-b", server, room1, issueStore = issues)
        val aead = JdkAesGcmAead()
        fun seed(type: String, id: String, version: Int, serverId: Long) {
            val sealed = SyncEnvelope.seal(
                aead, SyncScope.SharedRoom(room1), type, id, version, buildJsonObject { put("amount_minor", 1) }, 3_000L, false, "device-z",
            )
            server.stored += SyncLogEntryDownload(
                serverId, room1.value, "r", sealed.wireId, "device-z", Base64.getEncoder().encodeToString(sealed.ciphertext),
                false, Instant.ofEpochMilli(3_000L).toString(), 3_000L, "device-z",
            )
        }
        seed(SharedRecordType.SHARED_EXPENSE.wire, "tx-baru", version = SharedExpensePayload.VERSION + 1, serverId = 1)
        seed("jenis_masa_depan", "x-1", version = 1, serverId = 2)

        val result = b.sync(room1)

        assertEquals(0, result.applied)
        assertEquals(2, result.skipped)
        assertEquals(setOf(SyncIssueKind.UNSUPPORTED_VERSION), issues.book.map { it.kind }.toSet())
        assertEquals(2, issues.book.size)
        assertTrue(b.ledger.transactionRows.isEmpty(), "versi yang tak dikenal tidak diterapkan separuh")
    }

    @Test
    fun a_row_that_cannot_be_applied_yet_is_kept_as_a_deferred_issue_and_applied_once_the_reference_exists() = runSuspend {
        val server = FakeSyncServer()
        // Pengeluaran tanpa nama kategori (klien lain mengirimnya begitu): penerima jatuh ke "Lain-lain" yang belum ada di
        // ruangnya (kasus langka). Dirakit langsung di server palsu karena MoneyTransaction sendiri mewajibkan kategori.
        val data = buildJsonObject {
            put("amount_minor", 25_000)
            put("currency", "IDR")
            put("account_name", "Dompet")
            put("occurred_on", LocalDate.of(2026, 11, 1).toEpochDay())
        }
        val sealed = SyncEnvelope.seal(JdkAesGcmAead(), SyncScope.SharedRoom(room1), SharedRecordType.SHARED_EXPENSE.wire, "tx-1", SharedExpensePayload.VERSION, data, 3_000L, false, "device-z")
        server.stored += SyncLogEntryDownload(
            1, room1.value, "r", sealed.wireId, "device-z", Base64.getEncoder().encodeToString(sealed.ciphertext),
            false, Instant.ofEpochMilli(3_000L).toString(), 3_000L, "device-z",
        )
        val issues = IssueBookSyncStore()
        val b = Client("device-b", server, 9_000L, issues)
        b.ledger.roomRows[room1] = Room(room1, "Ruang", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)

        val first = b.sync(room1)

        assertEquals(0, first.applied)
        assertEquals(1, first.skipped)
        val issue = issues.book.single()
        assertEquals(SyncIssueKind.DEFERRED_MISSING_REF, issue.kind)
        assertNotNull(issue.rawRow, "baris mentah disimpan supaya bisa dicoba lagi tanpa tarik ulang")
        assertTrue(b.ledger.transactionRows.isEmpty())

        // Kategori "Lain-lain" muncul; sync berikutnya (kursor sudah lewat baris itu) mencoba lagi dan menuntaskannya.
        b.ledger.categoryRows[CategoryId("cat-lain")] = Category(CategoryId("cat-lain"), room1, "Lain-lain")
        val second = b.sync(room1)

        assertEquals(1, second.applied)
        assertEquals(0, second.skipped)
        assertTrue(issues.book.isEmpty(), "catatan dibuang begitu baris berhasil diterapkan")
        assertEquals(room1, b.roomOf("tx-1"))
    }

    @Test
    fun without_an_issue_book_failures_are_only_counted_as_before() = runSuspend {
        val server = FakeSyncServer()
        val a = client("device-a", server, room1)
        val b = client("device-b", server, room1)
        a.record("tx-1", room1)
        a.sync(room1)
        server.replace(0) { it.copy(payload = "###") }

        val result = b.sync(room1)

        assertEquals(1, result.skipped)
    }
}
