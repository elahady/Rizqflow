package com.roziqrizal.rizqflow.data

import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.AccountSyncHandler
import com.roziqrizal.rizqflow.data.sync.AllocationConfigSyncHandler
import com.roziqrizal.rizqflow.data.sync.CategorySyncHandler
import com.roziqrizal.rizqflow.data.sync.RoomSyncHandler
import com.roziqrizal.rizqflow.data.sync.TransactionSyncHandler
import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatusStore
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SyncAead
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryDownload
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AEAD identitas (mengabaikan AAD): cukup untuk tes logika mesin, AAD sudah dibuktikan terpisah di SyncEnvelopeTest (:domain). */
internal class PersonalIdentityAead : SyncAead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
}

internal class FakeAccountKeys(private val aead: SyncAead = PersonalIdentityAead()) : AccountKeyProvider {
    override fun getOrCreateAccountKey(): SyncAead = aead
    override fun exportAccountKeyForRecovery(): ByteArray = error("tidak dipakai di tes ini")
    override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) = error("tidak dipakai di tes ini")
}

internal class FakeRecoveryStatusStore(initial: RecoveryStatus = RecoveryStatus.NotConfigured) : RecoveryStatusStore {
    var current: RecoveryStatus = initial
    override fun read(): RecoveryStatus = current
    override fun markPending(serverUpdatedAt: String?) { current = RecoveryStatus.PendingConfirmation(serverUpdatedAt) }
    override fun markConfigured(at: Instant, serverUpdatedAt: String?) { current = RecoveryStatus.Configured(at, serverUpdatedAt) }
    override fun clear() { current = RecoveryStatus.NotConfigured }
}

internal class UnusedRecoveryKeyRemote : RecoveryKeyRemote {
    override suspend fun put(apiToken: String, wrappedKey: ByteArray) = error("tidak dipakai di tes ini")
    override suspend fun get(apiToken: String) = error("tidak dipakai di tes ini")
    override suspend fun delete(apiToken: String) = error("tidak dipakai di tes ini")
}

internal class PersonalMemoryToken : ApiTokenStore {
    override fun read() = "token"
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

/**
 * Server palsu di memori untuk cakupan pribadi (`room_id` selalu null). [pageSize] membatasi halaman
 * TERLEPAS dari `limit` yang diminta klien (memaksa lebih dari satu halaman di tes). [shuffleSeed] bila
 * tidak null mengacak URUTAN baris yang dikembalikan DI DALAM satu halaman (meniru urutan kedatangan
 * jaringan yang tidak bisa diandalkan) sambil tetap menjaga `next_after`/`has_more` dari urutan id asli.
 */
internal class MemoryPersonalRemote(private val pageSize: Int = 200, private val shuffleSeed: Int? = null) : SyncRemote {
    val stored = mutableListOf<SyncLogEntryDownload>()
    private var nextId = 1L

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        val ids = entries.map { e ->
            val id = nextId++
            stored += SyncLogEntryDownload(id, e.roomId, e.recordType, e.recordId, e.deviceId, e.payload, e.deleted, e.changedAt, e.changedAtMillis, author = e.deviceId)
            id
        }
        return RemoteResult.Success(SyncPushResult(ids, ids.maxOrNull() ?: 0, serverTimeMs = null))
    }

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> {
        val sorted = stored.filter { it.id > after }.sortedBy { it.id }
        val page = sorted.take(pageSize)
        val entries = shuffleSeed?.let { page.shuffled(Random(it)) } ?: page
        return RemoteResult.Success(SyncPullResult(entries, page.lastOrNull()?.id ?: after, page.size == pageSize))
    }
}

/** Database Room di memori TANPA onboarding (perangkat baru/kosong) — lawan [SyncFixture] yang selalu onboarding. */
internal fun emptyLedger(): LocalLedger {
    val db = AndroidRoom.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RizqflowDatabase::class.java)
        .allowMainThreadQueries().build()
    return LocalLedger(db)
}

internal fun engineFor(ledger: LocalLedger, remote: SyncRemote, recoveryStatus: RecoveryStatus = RecoveryStatus.NotConfigured): PersonalSyncCoordinator {
    val db = ledger.db
    val store = ledger.syncStore
    val recovery = RecoveryKeyService(FakeAccountKeys(), UnusedRecoveryKeyRemote(), PersonalMemoryToken(), FakeRecoveryStatusStore(recoveryStatus))
    return PersonalSyncCoordinator(
        handlers = listOf(
            RoomSyncHandler(db, store),
            AccountSyncHandler(db, store),
            CategorySyncHandler(db, store),
            AllocationConfigSyncHandler(db, store),
            TransactionSyncHandler(db, store),
        ),
        store = store,
        accountKeys = FakeAccountKeys(),
        syncRemote = remote,
        apiTokenStore = PersonalMemoryToken(),
        recovery = recovery,
    )
}

/**
 * Mesin sinkron cakupan pribadi (Tahap 3 sync-desain.md): gerbang kode pemulihan untuk push, urutan
 * penerapan per halaman (room -> account -> category -> allocation_config -> transaction) terlepas
 * dari urutan kedatangan jaringan, dan restore dari kursor 0 di perangkat baru.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSyncCoordinatorTest {
    private lateinit var a: SyncFixture

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
    }

    @Test
    fun `gerbang tertutup, push tidak mengirim apa pun dan rekaman tetap kotor`() = runBlocking {
        a.expense(15_000)
        val remote = MemoryPersonalRemote()

        val result = engineFor(a.local, remote, RecoveryStatus.NotConfigured).syncNow("device-a")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertEquals(0, success.pushed)
        assertTrue(success.pushGated)
        assertTrue(remote.stored.isEmpty(), "tidak satu baris pun boleh terkirim sebelum kode pemulihan terkonfigurasi")
        assertTrue(a.rows().any { it.dirty }, "rekaman lokal tetap kotor, bukan hilang")
    }

    @Test
    fun `gerbang terbuka, push mengirim dan membersihkan rekaman kotor`() = runBlocking {
        a.expense(15_000)
        val remote = MemoryPersonalRemote()

        val result = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertTrue(success.pushed > 0)
        assertTrue(!success.pushGated)
        assertTrue(remote.stored.isNotEmpty())
        assertTrue(a.rows().none { it.dirty }, "semua rekaman yang berhasil terkirim harus bersih")
    }

    @Test
    fun `restore dari kursor 0 di perangkat baru menghasilkan id identik, urutan halaman diacak tetap benar`() = runBlocking {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote(shuffleSeed = 42) // satu halaman (dataset kecil), tapi urutan baris di dalamnya diacak.
        val pushed = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        assertTrue((pushed as PersonalSyncResult.Success).pushed > 0)

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertTrue(success.applied > 0)
        assertEquals(0, success.skipped)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "tidak ada referensi yang gagal walau urutan kedatangan diacak")

        // Id identik dengan server (R1): ruang, akun, kategori, dan transaksi sama persis dengan A.
        assertEquals(a.local.rooms.activeRooms().map { it.id }.toSet(), b.rooms.activeRooms().map { it.id }.toSet())
        assertEquals(a.local.accounts.activeAccounts().map { it.id }.toSet(), b.accounts.activeAccounts().map { it.id }.toSet())
        val roomA = a.local.rooms.activeRooms().first()
        assertEquals(a.local.rooms.categories(roomA.id).map { it.id }.toSet(), b.rooms.categories(roomA.id).map { it.id }.toSet())
        val pulledExpense = b.transactions.find(expenseId)
        assertEquals(20_000L, pulledExpense?.amount?.minor)
        assertEquals(a.local.rooms.rules().map { it.roomId to it.share }.toSet(), b.rooms.rules().map { it.roomId to it.share }.toSet())
    }

    @Test
    fun `referensi belum tiba lintas halaman dicoba lagi di akhir pull sampai berhasil`() = runBlocking {
        val expenseId = a.expense(30_000)
        // Halaman satu baris: transaksi (peringkat tertinggi) bisa datang sebelum ruang/akun/kategorinya
        // tiba di halaman berikutnya — kebalikan dari "satu halaman diurutkan", ini murni ANTAR halaman.
        val remote = MemoryPersonalRemote(pageSize = 1)
        val pushed = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        assertTrue((pushed as PersonalSyncResult.Success).pushed > 1, "butuh beberapa baris supaya terpaksa lebih dari satu halaman")

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertEquals(0, b.syncStore.issues(SyncScope.Personal).size, "percobaan ulang di akhir pull harus menuntaskan semua DEFERRED_MISSING_REF")
        val pulledExpense = b.transactions.find(expenseId)
        assertEquals(30_000L, pulledExpense?.amount?.minor, "transaksi akhirnya diterapkan walau sempat tertunda")
    }

    @Test
    fun `tombstone transaksi diterapkan sebagai hapus lokal`() = runBlocking {
        val expenseId = a.expense(5_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")
        assertTrue(b.transactions.find(expenseId) != null)

        a.local.transactions.delete(expenseId)
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        engineFor(b, remote).syncNow("device-b")

        assertEquals(null, b.transactions.find(expenseId), "tombstone pull harus menghapus transaksi yang sama di perangkat lain")
    }
}
