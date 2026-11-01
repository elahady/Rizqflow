package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
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

/**
 * Sisi terima tombstone `room`, `category`, dan `account` (Tahap 9; sync-desain.md bagian D, keputusan pemilik #10 LWW murni).
 * Perangkat A menghapus lewat API hapus lokal dan mendorong tombstone; perangkat B menariknya. Perilaku yang dikunci:
 * tombstone yang menang menghapus (termasuk favorit lewat cascade) tanpa menerbitkan tombstone baru, rujukan yang masih
 * ada menunda tombstone (`DEFERRED_FK_RESTRICT`) sampai rujukannya hilang, dan edit yang lebih baru mengalahkan tombstone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTombstoneReceiveTest {
    private lateinit var a: SyncFixture
    private lateinit var b: LocalLedger
    private val remote = MemoryPersonalRemote()
    private var bCounter = 0

    @Before
    fun setUp() {
        a = SyncFixture()
        b = emptyLedger()
    }

    @After
    fun tearDown() {
        a.close()
        b.db.close()
    }

    private fun engineA() = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now()))
    private fun engineB() = engineFor(b, remote, RecoveryStatus.Configured(Instant.now()))

    private suspend fun syncA() = assertIs<PersonalSyncResult.Success>(engineA().syncNow("device-a"))
    private suspend fun syncB() = assertIs<PersonalSyncResult.Success>(engineB().syncNow("device-b"))

    private fun ledgerB() = LedgerService(b.accounts, b.rooms, b.transactions, { "b-${++bCounter}" }) { System.currentTimeMillis() }

    private suspend fun extraAccount(id: String): Account =
        Account(AccountId(id), "Bank $id", AccountKind.BANK, Money.rupiah(0), sortOrder = 5).also { a.local.accounts.save(it) }

    private suspend fun extraRoom(id: String): Pair<Room, List<Category>> {
        val room = Room(RoomId(id), "Ruang $id", RoomKind.MENCUKUPI, "icon", 4, sortOrder = 9, archived = true)
        val categories = (1..2).map { Category(CategoryId("$id-cat$it"), room.id, "Pos $it", sortOrder = it) }
        a.local.rooms.addRoom(room, categories)
        return room to categories
    }

    /** B tidak boleh punya perubahan yang belum terkirim akibat menerapkan tombstone (penerapan tidak menerbitkan apa pun). */
    private fun dirtyOnB(): Int = b.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_record WHERE dirty = 1").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private suspend fun expenseOnB(accountId: AccountId, roomId: RoomId, categoryId: CategoryId): TransactionId {
        val result = ledgerB().recordExpense(NewExpense(Money.rupiah(5_000), accountId, roomId, categoryId, LocalDate.of(2026, 10, 25)))
        return (result as LedgerResult.Success).value.id
    }

    @Test
    fun `tombstone akun menghapus akun dan favoritnya di perangkat lain tanpa menerbitkan tombstone baru`() = runBlocking<Unit> {
        val extra = extraAccount("acc-x")
        val room = a.roomIds().first()
        val category = a.local.rooms.categories(room.id).first()
        a.local.favorites.save(QuickFavorite("fav-x", "Kopi", Money.rupiah(10_000), room.id, category.id, extra.id))
        syncA()
        syncB()
        assertNotNull(b.accounts.find(extra.id))
        assertNotNull(b.favorites.find("fav-x"))
        // Sinkronkan B sekali lagi supaya penanda kotor akibat kedatangan data (bila ada) tidak mengaburkan asersi akhir.
        syncB()
        assertEquals(0, dirtyOnB())

        assertEquals(LedgerResult.Success(Unit), a.local.accounts.delete(extra.id))
        syncA()
        syncB()

        assertNull(b.accounts.find(extra.id), "akun yang dihapus di A harus hilang di B")
        assertNull(b.favorites.find("fav-x"), "favorit yang memakai akun ikut hilang")
        assertEquals(0, dirtyOnB(), "menerapkan tombstone tidak boleh menerbitkan tombstone baru")
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty())
    }

    @Test
    fun `tombstone kategori menghapus kategori di perangkat lain`() = runBlocking<Unit> {
        val (room, categories) = extraRoom("room-x")
        syncA()
        syncB()
        assertEquals(2, b.rooms.categories(room.id).size)

        assertEquals(LedgerResult.Success(Unit), a.local.rooms.deleteCategory(categories[0].id))
        syncA()
        syncB()

        assertNull(b.rooms.findCategory(categories[0].id))
        assertNotNull(b.rooms.findCategory(categories[1].id), "kategori lain tidak tersentuh")
        assertEquals(0, dirtyOnB())
    }

    @Test
    fun `tombstone ruang menghapus ruang beserta kategori dan favoritnya di perangkat lain`() = runBlocking<Unit> {
        val (room, categories) = extraRoom("room-x")
        val account = a.accountId()
        a.local.favorites.save(QuickFavorite("fav-r", "Fav", Money.rupiah(1_000), room.id, categories[0].id, account))
        syncA()
        syncB()
        assertNotNull(b.rooms.find(room.id))
        assertNotNull(b.favorites.find("fav-r"))
        syncB()

        assertEquals(LedgerResult.Success(Unit), a.local.rooms.deleteRoom(room.id))
        syncA()
        syncB()

        assertNull(b.rooms.find(room.id), "ruang yang dihapus di A harus hilang di B")
        assertTrue(categories.all { b.rooms.findCategory(it.id) == null }, "kategori ruang ikut hilang")
        assertNull(b.favorites.find("fav-r"))
        assertEquals(0, dirtyOnB())
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "tombstone anak yang menyusul untuk baris yang sudah hilang tidak boleh jadi masalah")
    }

    @Test
    fun `tombstone akun yang masih dirujuk transaksi lokal ditunda lalu berlaku setelah rujukannya hilang`() = runBlocking<Unit> {
        val extra = extraAccount("acc-x")
        syncA()
        syncB()
        val room = b.rooms.activeRooms().first()
        val category = b.rooms.categories(room.id).first()
        val expenseId = expenseOnB(extra.id, room.id, category.id)

        assertEquals(LedgerResult.Success(Unit), a.local.accounts.delete(extra.id))
        syncA()
        syncB()

        assertNotNull(b.accounts.find(extra.id), "akun masih dirujuk transaksi: tidak boleh dihapus")
        val issue = b.syncStore.issues(SyncScope.Personal).single()
        assertEquals(SyncIssueKind.DEFERRED_FK_RESTRICT, issue.kind)
        assertEquals("account", issue.recordType)

        // Transaksi dihapus di B: sync berikutnya mencoba lagi tombstone akun dan kini berlaku.
        b.transactions.delete(expenseId)
        syncB()

        assertNull(b.accounts.find(extra.id), "setelah rujukan hilang, tombstone yang tertunda berlaku")
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "masalah selesai dan dibuang")
    }

    @Test
    fun `tombstone ruang yang masih punya transaksi lokal ditunda, ruang dan kategori yang dipakai bertahan`() = runBlocking<Unit> {
        val (room, categories) = extraRoom("room-x")
        syncA()
        syncB()
        // Ruang uji terarsip (syarat hapus di A), padahal pengeluaran hanya bisa dicatat di ruang aktif: B mengaktifkannya
        // lewat DAO langsung (bukan edit tersinkron), meniru pemakaian konkuren yang belum sampai ke A.
        b.db.rooms().upsert(assertNotNull(b.db.rooms().find(room.id.value)).copy(archived = false))
        expenseOnB(b.accounts.activeAccounts().first().id, room.id, categories[0].id)

        assertEquals(LedgerResult.Success(Unit), a.local.rooms.deleteRoom(room.id))
        syncA()
        syncB()

        assertNotNull(b.rooms.find(room.id), "ruang masih punya transaksi: tidak dihapus")
        // Tombstone tiap kategori berdiri sendiri: yang dipakai transaksi B bertahan, yang tidak dipakai ikut keputusan A.
        assertNotNull(b.rooms.findCategory(categories[0].id), "kategori yang dipakai transaksi bertahan")
        assertNull(b.rooms.findCategory(categories[1].id), "kategori yang tidak dipakai mengikuti tombstone-nya sendiri")
        assertTrue(b.syncStore.issues(SyncScope.Personal).any { it.kind == SyncIssueKind.DEFERRED_FK_RESTRICT && it.recordType == "room" })
        assertTrue(b.syncStore.issues(SyncScope.Personal).any { it.kind == SyncIssueKind.DEFERRED_FK_RESTRICT && it.recordType == "category" })
    }

    @Test
    fun `tombstone ruang yang sedang dibagi ditunda`() = runBlocking<Unit> {
        val (room, _) = extraRoom("room-x")
        syncA()
        syncB()
        b.syncStore.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1L))

        assertEquals(LedgerResult.Success(Unit), a.local.rooms.deleteRoom(room.id))
        syncA()
        syncB()

        assertNotNull(b.rooms.find(room.id), "ruang yang dibagi aktif tidak dihapus oleh tombstone pribadi")
        assertTrue(b.syncStore.issues(SyncScope.Personal).any { it.kind == SyncIssueKind.DEFERRED_FK_RESTRICT && it.recordType == "room" })
    }

    @Test
    fun `edit lokal yang lebih baru mengalahkan tombstone masuk (LWW murni)`() = runBlocking<Unit> {
        val extra = extraAccount("acc-x")
        syncA()
        syncB()

        // A menghapus pada jam fixture (1_000_000 ms); B mengubah nama dengan jam sistem yang jauh lebih baru.
        assertEquals(LedgerResult.Success(Unit), a.local.accounts.delete(extra.id))
        syncA()
        val renamed = assertNotNull(b.accounts.find(extra.id)).copy(name = "Diganti di B")
        b.accounts.save(renamed)
        syncB()

        assertEquals("Diganti di B", b.accounts.find(extra.id)?.name, "tombstone yang lebih lama tidak boleh menghapus edit yang lebih baru")
        assertTrue(b.syncStore.issues(SyncScope.Personal).none { it.kind == SyncIssueKind.DEFERRED_FK_RESTRICT })
    }
}
