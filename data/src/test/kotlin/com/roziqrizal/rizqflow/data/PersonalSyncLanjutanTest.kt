package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.WealthKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.role.DcaPlan
import com.roziqrizal.rizqflow.domain.role.TraderProfile
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.zakat.GoldPrice
import com.roziqrizal.rizqflow.domain.zakat.WealthCheck
import com.roziqrizal.rizqflow.domain.zakat.WealthItem
import com.roziqrizal.rizqflow.domain.zakat.ZakatPayment
import com.roziqrizal.rizqflow.domain.zakat.ZakatProfile
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entitas pribadi lanjutan (Tahap 4 sync-desain.md): zakat (profil, harta, cek, pembayaran), harga emas
 * MANUAL, peran trader dan DCA, favorit, dan centang harian. Pola tesnya sama dengan
 * [PersonalSyncCoordinatorTest]: push, pull lintas perangkat dengan id identik, dan referensi belum
 * tiba dicatat `DEFERRED_MISSING_REF` lalu diterapkan setelah rujukannya tiba.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSyncLanjutanTest {
    private lateinit var a: SyncFixture
    private val configured = RecoveryStatus.Configured(Instant.now())
    private val day = LocalDate.of(2026, 10, 25)

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
    }

    /** Membuat satu profil zakat lengkap (profil, harta, cek, pembayaran) di perangkat A. Mengembalikan id transaksi pembayaran. */
    private suspend fun seedZakat(): TransactionId {
        a.local.zakat.saveProfile(ZakatProfile(id = "zp-1", name = "Harta utama"))
        a.local.zakat.replaceItems(
            "zp-1",
            listOf(WealthItem(id = "wi-1", profileId = "zp-1", kind = WealthKind.CASH_SAVINGS, label = "Tabungan", value = Money.rupiah(5_000_000), updatedAtMillis = 1L)),
        )
        a.local.zakat.saveCheck(WealthCheck(id = "wc-1", profileId = "zp-1", day = day, netWealth = Money.rupiah(5_000_000), nisab = Money.rupiah(4_000_000)))
        val txId = a.expense(200_000)
        a.local.zakat.savePayment(ZakatPayment(id = "zpay-1", profileId = "zp-1", day = day, transactionId = txId))
        return txId
    }

    @Test
    fun `zakat push lalu pull menghasilkan profil, harta, cek, dan pembayaran dengan id identik`() = runBlocking {
        seedZakat()
        val remote = MemoryPersonalRemote()
        val pushed = engineFor(a.local, remote, configured).syncNow("device-a")
        assertTrue((pushed as PersonalSyncResult.Success).pushed > 0)
        assertTrue(a.rows().none { it.dirty }, "semua rekaman zakat yang terkirim harus bersih")

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        assertIs<PersonalSyncResult.Success>(result)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "tidak ada rujukan yang tertunda")
        assertEquals("Harta utama", b.zakat.findProfile("zp-1")?.name)
        assertEquals(listOf("wi-1"), b.zakat.items("zp-1").map { it.id })
        assertEquals(listOf("wc-1"), b.zakat.checks("zp-1").map { it.id })
        assertEquals(listOf("zpay-1"), b.zakat.payments("zp-1").map { it.id })
    }

    @Test
    fun `zakat pembayaran yang transaksinya belum tiba ditunda lalu diterapkan setelah transaksi tiba`() = runBlocking {
        seedZakat()
        val full = MemoryPersonalRemote()
        engineFor(a.local, full, configured).syncNow("device-a")

        // Perangkat B hanya menerima semua baris KECUALI transaksi, jadi pembayaran zakat kekurangan rujukan.
        val partial = MemoryPersonalRemote().also { it.stored += full.stored.filter { row -> row.recordType != "transaction" } }
        val b = emptyLedger()
        engineFor(b, partial).syncNow("device-b")

        assertTrue(
            b.syncStore.issues(SyncScope.Personal).any { it.kind == SyncIssueKind.DEFERRED_MISSING_REF && it.recordType == "zakat_payment" },
            "pembayaran zakat harus tercatat DEFERRED_MISSING_REF",
        )
        assertTrue(b.zakat.payments("zp-1").isEmpty())

        // Transaksi tiba belakangan (id server lebih besar dari kursor sekarang), lalu sync berikutnya menuntaskannya.
        partial.stored += full.stored.filter { it.recordType == "transaction" }.mapIndexed { i, row -> row.copy(id = 10_000L + i) }
        engineFor(b, partial).syncNow("device-b")

        assertEquals(listOf("zpay-1"), b.zakat.payments("zp-1").map { it.id }, "pembayaran diterapkan begitu rujukannya ada")
        assertTrue(b.syncStore.issues(SyncScope.Personal).none { it.recordType == "zakat_payment" })
    }

    @Test
    fun `harga emas hanya MANUAL yang dikirim, dan tombstone hanya menghapus baris MANUAL`() = runBlocking {
        a.local.zakat.saveGoldPrice(GoldPrice(day, Money.rupiah(1_300_000), GoldPriceSource.MANUAL))
        a.local.zakat.saveGoldPrice(GoldPrice(day.plusDays(1), Money.rupiah(1_310_000), GoldPriceSource.AUTO))
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        assertEquals(1, remote.stored.count { it.recordType == "gold_price" }, "harga AUTO tidak boleh ikut terkirim")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")
        val pulled = b.zakat.latestGoldPrice()
        assertEquals(1_300_000L, pulled?.perGram?.minor)
        assertEquals(GoldPriceSource.MANUAL, pulled?.source)

        // Harga MANUAL di hari itu diganti AUTO di A: baris MANUAL-nya dihapus (tombstone) dan terhapus juga di B.
        a.local.zakat.saveGoldPrice(GoldPrice(day, Money.rupiah(1_300_000), GoldPriceSource.AUTO))
        engineFor(a.local, remote, configured).syncNow("device-a")
        engineFor(b, remote).syncNow("device-b")
        assertNull(b.zakat.latestGoldPrice(), "tombstone harga MANUAL harus menghapus baris di perangkat lain")
    }

    @Test
    fun `peran trader dan DCA terkirim dan diterima perangkat lain dengan kunci ruang yang sama`() = runBlocking {
        val room = a.roomIds().first().id
        val category = a.local.rooms.categories(room).first().id
        val account: AccountId = a.accountId()
        a.local.roles.saveTrader(TraderProfile(room, Money.rupiah(10_000_000), BasisPoints(100)))
        a.local.roles.saveDca(DcaPlan(room, Money.rupiah(500_000), 5, account, category))
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")

        assertEquals(10_000_000L, b.roles.trader(room)?.capital?.minor)
        assertEquals(100, b.roles.trader(room)?.riskPerTrade?.value)
        assertEquals(500_000L, b.roles.dca(room)?.amount?.minor)
        assertEquals(5, b.roles.dca(room)?.dayOfMonth)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty())
    }

    @Test
    fun `DCA yang kategorinya belum tiba ditunda lalu diterapkan setelah kategori tiba`() = runBlocking {
        val room = a.roomIds().first().id
        val category = a.local.rooms.categories(room).first().id
        a.local.roles.saveDca(DcaPlan(room, Money.rupiah(500_000), 5, a.accountId(), category))
        val full = MemoryPersonalRemote()
        engineFor(a.local, full, configured).syncNow("device-a")

        val partial = MemoryPersonalRemote().also { it.stored += full.stored.filter { row -> row.recordType != "category" } }
        val b = emptyLedger()
        engineFor(b, partial).syncNow("device-b")
        assertTrue(b.syncStore.issues(SyncScope.Personal).any { it.recordType == "dca_plan" && it.kind == SyncIssueKind.DEFERRED_MISSING_REF })
        assertNull(b.roles.dca(room))

        partial.stored += full.stored.filter { it.recordType == "category" }.mapIndexed { i, row -> row.copy(id = 20_000L + i) }
        engineFor(b, partial).syncNow("device-b")
        assertEquals(500_000L, b.roles.dca(room)?.amount?.minor)
        assertTrue(b.syncStore.issues(SyncScope.Personal).none { it.recordType == "dca_plan" })
    }

    @Test
    fun `favorit terkirim, use_count dan last_used_at tetap milik perangkat masing-masing, dan tombstone menghapus`() = runBlocking {
        val room = a.roomIds().first().id
        val category = a.local.rooms.categories(room).first().id
        val account = a.accountId()
        val favorite = QuickFavorite(id = "fav-1", name = "Kopi", amount = Money.rupiah(15_000), roomId = room, categoryId = category, accountId = account)
        a.local.favorites.save(favorite)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")
        assertEquals("Kopi", b.favorites.find("fav-1")?.name)

        // B memakai favorit (hanya use_count dan last_used_at berubah): tidak boleh ikut terkirim.
        b.favorites.save(favorite.copy(useCount = 5, lastUsedAtMillis = 123L))
        assertEquals(0, dirtyCount(b), "pemakaian favorit saja tidak menandai kotor")

        // A mengubah nama: B menerima nama baru, tetapi use_count miliknya tetap 5.
        a.local.favorites.save(favorite.copy(name = "Kopi susu"))
        engineFor(a.local, remote, configured).syncNow("device-a")
        engineFor(b, remote).syncNow("device-b")
        val afterRename = b.favorites.find("fav-1")
        assertEquals("Kopi susu", afterRename?.name)
        assertEquals(5, afterRename?.useCount)
        assertEquals(123L, afterRename?.lastUsedAtMillis)

        a.local.favorites.delete("fav-1")
        engineFor(a.local, remote, configured).syncNow("device-a")
        engineFor(b, remote).syncNow("device-b")
        assertNull(b.favorites.find("fav-1"), "tombstone favorit harus menghapusnya di perangkat lain")
    }

    @Test
    fun `centang harian terkirim dan diterima, dan id d-epochDay konvergen di dua perangkat`() = runBlocking {
        a.local.settings.markDayChecked(day)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")
        assertTrue(b.settings.isDayChecked(day))
        assertTrue(!b.settings.isDayChecked(day.plusDays(1)))

        // Menandai hari yang sama lagi di B tidak menambah rekaman kotor (sudah tercentang di sana).
        b.settings.markDayChecked(day)
        assertEquals(0, dirtyCount(b), "hari yang sudah tercentang tidak menandai kotor ulang")
        assertEquals(1, remote.stored.count { it.recordType == "day_check" })
    }

    /** Jumlah rekaman kotor di `sync_record` milik [ledger] (`LocalLedger` tidak punya `rows()`, itu milik [SyncFixture]). */
    private fun dirtyCount(ledger: LocalLedger): Int =
        ledger.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_record WHERE dirty = 1").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
}
