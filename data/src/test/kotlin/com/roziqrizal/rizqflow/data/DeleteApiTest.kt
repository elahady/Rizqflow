package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.bill.Bill
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.recurring.Frequency
import com.roziqrizal.rizqflow.domain.role.DcaPlan
import com.roziqrizal.rizqflow.domain.role.TraderProfile
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API hapus akun, kategori, dan ruang (Tahap 2 sync-desain.md; utang "repositori belum punya API hapus"). Perilaku
 * aman yang diuji, USULAN Claude (belum dikonfirmasi pemilik): hapus ditolak dengan galat domain yang jelas bila masih
 * ada rujukan (kunci asing RESTRICT), penolakan tidak meninggalkan perubahan sama sekali, dan setiap rekaman yang hilang
 * termasuk lewat cascade ruang -> kategori mendapat tombstone sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteApiTest {
    private lateinit var fx: SyncFixture

    @Before
    fun setUp() {
        fx = SyncFixture()
    }

    @After
    fun tearDown() = fx.close()

    private fun assertTombstone(type: SyncRecordType, id: String) {
        val row = assertNotNull(fx.row(type, id), "tombstone $type $id")
        assertTrue(row.deleted && row.dirty, "$type $id harus tombstone kotor")
    }

    private fun assertNoTombstone(type: SyncRecordType, id: String) {
        assertTrue(fx.row(type, id)?.deleted != true, "$type $id tidak boleh bertombstone")
    }

    private suspend fun extraAccount(id: String = "acc-extra", archived: Boolean = false): Account {
        val account = Account(AccountId(id), "Bank $id", AccountKind.BANK, Money.rupiah(0), archived = archived, sortOrder = 5)
        fx.local.accounts.save(account)
        return account
    }

    /** Ruang baru yang kosong: dua kategori biasa dan satu kategori sistem. */
    private suspend fun newRoom(id: String = "room-x", archived: Boolean = true, normalCategories: Int = 2): Pair<Room, List<Category>> {
        val room = Room(RoomId(id), "Ruang $id", RoomKind.MENCUKUPI, "icon", 4, sortOrder = 9, archived = archived)
        val categories = (1..normalCategories).map { Category(CategoryId("$id-cat$it"), room.id, "Pos $it", sortOrder = it) } +
            Category(CategoryId("$id-sys"), room.id, "Tak terlacak", isSystem = true, sortOrder = 99)
        fx.local.rooms.addRoom(room, categories)
        return room to categories
    }

    private suspend fun favoriteFor(id: String, room: Room, category: Category, account: AccountId) =
        QuickFavorite(id, "Fav $id", Money.rupiah(10_000), room.id, category.id, account).also { fx.local.favorites.save(it) }

    // ------------------------------------------------------------------ akun

    @Test
    fun `hapus akun yang tidak dirujuk berhasil, menulis tombstone akun dan favoritnya`() = runBlocking<Unit> {
        val extra = extraAccount()
        val (room, categories) = newRoom(archived = false)
        favoriteFor("fav-a", room, categories.first(), extra.id)
        fx.markAllClean()

        val result = fx.local.accounts.delete(extra.id)

        assertEquals(LedgerResult.Success(Unit), result)
        assertNull(fx.local.accounts.find(extra.id))
        assertNull(fx.local.favorites.find("fav-a"), "favorit yang memakai akun ikut hilang")
        assertTombstone(SyncRecordType.ACCOUNT, extra.id.value)
        assertTombstone(SyncRecordType.QUICK_FAVORITE, "fav-a")
    }

    @Test
    fun `hapus akun yang dirujuk transaksi ditolak dan tidak mengubah apa pun`() = runBlocking<Unit> {
        val extra = extraAccount()
        val room = fx.roomIds().first()
        val category = fx.local.rooms.categories(room.id).first()
        fx.ledger.recordExpense(com.roziqrizal.rizqflow.domain.ledger.NewExpense(Money.rupiah(5_000), extra.id, room.id, category.id, fx.today))
        fx.markAllClean()

        val result = fx.local.accounts.delete(extra.id)

        assertEquals(LedgerResult.Failure(LedgerError.ACCOUNT_IN_USE), result)
        assertNotNull(fx.local.accounts.find(extra.id))
        assertNoTombstone(SyncRecordType.ACCOUNT, extra.id.value)
    }

    @Test
    fun `hapus akun yang dirujuk tagihan, transaksi berulang, utang, atau rencana DCA ditolak`() = runBlocking<Unit> {
        val room = fx.roomIds().last()
        val category = fx.local.rooms.categories(room.id).first()

        val forBill = extraAccount("acc-bill")
        fx.local.bills.save(Bill("bill-1", "Listrik", Money.rupiah(100_000), forBill.id, room.id, category.id, null, Frequency.MONTHLY, fx.today, fx.today))
        val forRecurring = extraAccount("acc-rec")
        fx.local.recurring.save(
            com.roziqrizal.rizqflow.domain.recurring.RecurringRule(
                "rr-1", com.roziqrizal.rizqflow.domain.model.TransactionKind.INCOME, Money.rupiah(1_000), forRecurring.id,
                incomeSource = "Gaji", frequency = Frequency.MONTHLY, startDate = fx.today, nextDue = fx.today,
            ),
        )
        val forDebt = extraAccount("acc-debt")
        fx.local.debts.save(
            com.roziqrizal.rizqflow.domain.debt.Debt("debt-1", com.roziqrizal.rizqflow.domain.debt.DebtDirection.LENT, "Budi", Money.rupiah(1_000), forDebt.id, null, fx.today),
        )
        val forDca = extraAccount("acc-dca")
        fx.local.roles.saveDca(DcaPlan(room.id, Money.rupiah(50_000), 5, forDca.id, category.id))

        for (account in listOf(forBill, forRecurring, forDebt, forDca)) {
            assertEquals(LedgerResult.Failure(LedgerError.ACCOUNT_IN_USE), fx.local.accounts.delete(account.id), account.name)
            assertNotNull(fx.local.accounts.find(account.id))
        }
    }

    @Test
    fun `akun aktif terakhir tidak bisa dihapus tetapi akun terarsip bisa`() = runBlocking<Unit> {
        val only = fx.accountId()
        assertEquals(LedgerResult.Failure(LedgerError.LAST_ACCOUNT), fx.local.accounts.delete(only))

        val archived = extraAccount(archived = true)
        assertEquals(LedgerResult.Success(Unit), fx.local.accounts.delete(archived.id))
    }

    @Test
    fun `akun anggota virtual dan akun yang tidak ada tidak bisa dihapus lewat API pengguna`() = runBlocking<Unit> {
        val room = fx.roomIds().first()
        val virtual = fx.local.accounts.findOrCreateVirtualMember(room.id, "ayu", "Dompet")

        assertEquals(LedgerResult.Failure(LedgerError.ACCOUNT_NOT_FOUND), fx.local.accounts.delete(virtual.id))
        assertNotNull(fx.local.accounts.find(virtual.id))
        assertEquals(LedgerResult.Failure(LedgerError.ACCOUNT_NOT_FOUND), fx.local.accounts.delete(AccountId("tidak-ada")))
    }

    // ------------------------------------------------------------------ kategori

    @Test
    fun `hapus kategori yang tidak dirujuk berhasil, menulis tombstone kategori dan favoritnya`() = runBlocking<Unit> {
        val (room, categories) = newRoom(archived = false)
        favoriteFor("fav-c", room, categories[0], fx.accountId())
        fx.markAllClean()

        val result = fx.local.rooms.deleteCategory(categories[0].id)

        assertEquals(LedgerResult.Success(Unit), result)
        assertNull(fx.local.rooms.findCategory(categories[0].id))
        assertNull(fx.local.favorites.find("fav-c"))
        assertTombstone(SyncRecordType.CATEGORY, categories[0].id.value)
        assertTombstone(SyncRecordType.QUICK_FAVORITE, "fav-c")
        assertNotNull(fx.local.rooms.findCategory(categories[1].id), "kategori lain tidak tersentuh")
    }

    @Test
    fun `hapus kategori sistem, yang dirujuk transaksi atau DCA, atau yang terakhir ditolak`() = runBlocking<Unit> {
        val (room, categories) = newRoom(archived = false)
        val sys = categories.last()
        assertEquals(LedgerResult.Failure(LedgerError.CATEGORY_IS_SYSTEM), fx.local.rooms.deleteCategory(sys.id))

        // Dirujuk transaksi.
        fx.ledger.recordExpense(com.roziqrizal.rizqflow.domain.ledger.NewExpense(Money.rupiah(5_000), fx.accountId(), room.id, categories[0].id, fx.today))
        assertEquals(LedgerResult.Failure(LedgerError.CATEGORY_IN_USE), fx.local.rooms.deleteCategory(categories[0].id))

        // Dirujuk rencana DCA.
        fx.local.roles.saveDca(DcaPlan(room.id, Money.rupiah(50_000), 5, fx.accountId(), categories[1].id))
        assertEquals(LedgerResult.Failure(LedgerError.CATEGORY_IN_USE), fx.local.rooms.deleteCategory(categories[1].id))

        // Kategori biasa aktif terakhir di ruangnya.
        val (_, single) = newRoom("room-single", archived = false, normalCategories = 1)
        assertEquals(LedgerResult.Failure(LedgerError.LAST_CATEGORY), fx.local.rooms.deleteCategory(single[0].id))

        assertEquals(LedgerResult.Failure(LedgerError.CATEGORY_NOT_FOUND), fx.local.rooms.deleteCategory(CategoryId("tidak-ada")))
    }

    // ------------------------------------------------------------------ ruang

    @Test
    fun `hapus ruang terarsip menghapus kategori, DCA, trader, favorit dan menulis tombstone untuk semuanya`() = runBlocking<Unit> {
        val (room, categories) = newRoom()
        fx.local.roles.saveDca(DcaPlan(room.id, Money.rupiah(50_000), 5, fx.accountId(), categories[0].id)) // RESTRICT ke kategori ruang ini
        fx.local.roles.saveTrader(TraderProfile(room.id, Money.rupiah(1_000_000), BasisPoints(100)))
        favoriteFor("fav-r", room, categories[1], fx.accountId())
        fx.markAllClean()

        val result = fx.local.rooms.deleteRoom(room.id)

        assertEquals(LedgerResult.Success(Unit), result)
        assertNull(fx.local.rooms.find(room.id))
        assertTrue(fx.local.rooms.allCategories().none { it.roomId == room.id }, "kategori ikut hilang")
        assertNull(fx.local.roles.dca(room.id))
        assertNull(fx.local.roles.trader(room.id))
        assertNull(fx.local.favorites.find("fav-r"))
        assertTombstone(SyncRecordType.ROOM, room.id.value)
        for (category in categories) assertTombstone(SyncRecordType.CATEGORY, category.id.value)
        assertTombstone(SyncRecordType.DCA_PLAN, room.id.value)
        assertTombstone(SyncRecordType.TRADER_PROFILE, room.id.value)
        assertTombstone(SyncRecordType.QUICK_FAVORITE, "fav-r")
        assertNull(fx.db.sync().sharedRoom(room.id.value))
    }

    @Test
    fun `hapus ruang yang belum diarsipkan atau ruang bersama aktif ditolak`() = runBlocking<Unit> {
        val (active, _) = newRoom("room-active", archived = false)
        assertEquals(LedgerResult.Failure(LedgerError.ROOM_NOT_ARCHIVED), fx.local.rooms.deleteRoom(active.id))

        val (shared, _) = newRoom("room-shared")
        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(shared.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1))
        assertEquals(LedgerResult.Failure(LedgerError.ROOM_SHARED), fx.local.rooms.deleteRoom(shared.id))
        assertNotNull(fx.local.rooms.find(shared.id))

        // Sesudah keluar dari ruang bersama, hapus diizinkan dan baris keanggotaan ikut hilang (CASCADE).
        fx.local.syncStore.saveSharedRoom(SharedRoomInfo(shared.id, SharedRoomRole.OWNER, SharedRoomStatus.LEFT, 1))
        assertEquals(LedgerResult.Success(Unit), fx.local.rooms.deleteRoom(shared.id))
        assertNull(fx.db.sync().sharedRoom(shared.id.value))

        assertEquals(LedgerResult.Failure(LedgerError.ROOM_NOT_FOUND), fx.local.rooms.deleteRoom(RoomId("tidak-ada")))
    }

    @Test
    fun `hapus ruang yang punya riwayat ditolak dan tidak meninggalkan perubahan setengah jalan`() = runBlocking<Unit> {
        val (room, categories) = newRoom(archived = false)
        fx.local.roles.saveDca(DcaPlan(room.id, Money.rupiah(50_000), 5, fx.accountId(), categories[0].id))
        val recorded = fx.ledger.recordExpense(com.roziqrizal.rizqflow.domain.ledger.NewExpense(Money.rupiah(5_000), fx.accountId(), room.id, categories[1].id, fx.today))
        assertTrue(recorded is LedgerResult.Success, "pengeluaran harus tercatat dulu (ruang masih aktif)")
        fx.local.rooms.saveRooms(listOf(room.copy(archived = true)))
        fx.markAllClean()

        val result = fx.local.rooms.deleteRoom(room.id)

        assertEquals(LedgerResult.Failure(LedgerError.ROOM_IN_USE), result)
        assertNotNull(fx.local.rooms.find(room.id))
        assertEquals(categories.size, fx.local.rooms.allCategories().count { it.roomId == room.id }, "tidak ada kategori yang terhapus")
        assertNotNull(fx.local.roles.dca(room.id), "rencana DCA tidak ikut terhapus oleh penolakan")
        assertFalse(fx.rows().any { it.deleted }, "tidak ada tombstone dari hapus yang ditolak")
    }

    @Test
    fun `hapus ruang yang kategorinya dirujuk tagihan ditolak walau ruang tagihan itu berbeda`() = runBlocking<Unit> {
        // Tagihan merujuk (ruang, kategori) bersama; ruang yang dirujuk tagihan ditolak lewat roomInUse.
        val (room, categories) = newRoom()
        fx.local.bills.save(Bill("bill-x", "Cicilan", Money.rupiah(100_000), fx.accountId(), room.id, categories[0].id, null, Frequency.MONTHLY, fx.today, fx.today))

        assertEquals(LedgerResult.Failure(LedgerError.ROOM_IN_USE), fx.local.rooms.deleteRoom(room.id))
        assertNotNull(fx.local.bills.find("bill-x"))
    }

    @Test
    fun `hapus ruang yang kategorinya dipakai rencana DCA ruang lain ditolak oleh pemeriksaan, bukan oleh crash`() = runBlocking<Unit> {
        val (room, categories) = newRoom()
        val other = fx.roomIds().last()
        fx.local.roles.saveDca(DcaPlan(other.id, Money.rupiah(50_000), 5, fx.accountId(), categories[0].id)) // data tak lazim: DCA ruang lain

        assertEquals(LedgerResult.Failure(LedgerError.ROOM_IN_USE), fx.local.rooms.deleteRoom(room.id))
        assertNotNull(fx.local.rooms.find(room.id))
    }
}
