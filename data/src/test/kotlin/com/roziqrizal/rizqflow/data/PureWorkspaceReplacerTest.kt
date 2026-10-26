package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.PureWorkspaceReplacer
import com.roziqrizal.rizqflow.data.sync.RoomWorkspacePurityChecker
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
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
import kotlin.test.assertTrue

/**
 * Deteksi "ruang kerja masih murni" dan eksekusi "ganti dengan data server" (Tahap 3 sync-desain.md
 * bagian B "Perangkat baru", keputusan pemilik #11). [SyncFixture] selalu onboarding (Tiga hak + satu
 * akun), jadi di sini "murni" berarti "hasil onboarding itu BELUM dipakai", bukan "database kosong".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PureWorkspaceReplacerTest {
    private lateinit var fixture: SyncFixture

    @Before
    fun setUp() {
        fixture = SyncFixture()
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `ruang kerja baru hasil onboarding masih murni`() = runBlocking {
        assertTrue(RoomWorkspacePurityChecker(fixture.db).isPure())
    }

    @Test
    fun `satu transaksi saja membuat ruang kerja tidak murni lagi`() = runBlocking {
        fixture.expense(10_000)
        assertFalse(RoomWorkspacePurityChecker(fixture.db).isPure())
    }

    @Test
    fun `utang tanpa transaksi juga membuat ruang kerja tidak murni`() = runBlocking {
        fixture.local.debts.save(
            com.roziqrizal.rizqflow.domain.debt.Debt(
                id = "utang-1",
                direction = com.roziqrizal.rizqflow.domain.debt.DebtDirection.BORROWED,
                party = "Teman",
                principal = Money.rupiah(100_000),
                startDate = fixture.today,
            ),
        )
        assertFalse(RoomWorkspacePurityChecker(fixture.db).isPure())
    }

    @Test
    fun `wipeOnboardingAndResetCursor membuang ruang, akun, kategori, alokasi, dan jejak sync-nya, kursor kembali ke 0`() = runBlocking {
        fixture.local.syncStore.setCursor(SyncScope.Personal, 123L)
        assertTrue(fixture.local.rooms.activeRooms().isNotEmpty())
        assertTrue(fixture.local.accounts.activeAccounts().isNotEmpty())

        PureWorkspaceReplacer(fixture.db, fixture.local.syncStore).wipeOnboardingAndResetCursor()

        assertTrue(fixture.local.rooms.allRooms().isEmpty())
        assertTrue(fixture.local.accounts.allAccounts().isEmpty())
        assertTrue(fixture.local.rooms.allCategories().isEmpty())
        assertTrue(fixture.local.rooms.rules().isEmpty())
        assertEquals(0L, fixture.local.syncStore.cursor(SyncScope.Personal))
        assertTrue(
            fixture.rows().none { it.type in setOf("room", "account", "category", "allocation_config") },
            "jejak sync_record hasil onboarding juga harus hilang",
        )
    }

    @Test
    fun `alur otomatis ganti penuh - wipe lalu tarik ulang dari server menghasilkan data A, bukan onboarding B`() = runBlocking {
        val a = SyncFixture()
        try {
            val roomA = a.local.rooms.activeRooms().first { it.name == "Keluarga" }
            val expenseA = (
                a.ledger.recordExpense(
                    NewExpense(Money.rupiah(40_000), a.accountId(), roomA.id, a.local.rooms.categories(roomA.id).first().id, a.today),
                ) as LedgerResult.Success
                ).value

            val remote = MemoryPersonalRemote()
            val pushed = engineFor(a.local, remote, RecoveryStatus.Configured(java.time.Instant.now())).syncNow("device-a")
            assertTrue((pushed as PersonalSyncResult.Success).pushed > 0)

            // B sudah onboarding SENDIRI (hasil onboarding template yang sama, belum dipakai): masih MURNI.
            // (SyncFixture memberi id berurutan "id-1", "id-2", ... PER instans, jadi ruang onboarding A dan B
            // kebetulan punya id yang sama di tes ini — tidak terjadi di produksi, yang memakai UUID acak.)
            val b = fixture
            assertTrue(RoomWorkspacePurityChecker(b.db).isPure())
            val roomCountBeforeWipe = b.local.rooms.allRooms().size

            PureWorkspaceReplacer(b.db, b.local.syncStore).wipeOnboardingAndResetCursor()
            val result = engineFor(b.local, remote).syncNow("device-b")
            assertIs<PersonalSyncResult.Success>(result)

            // Hasil akhir: persis ruang A (id sama, jumlah sama), BUKAN gabungan dengan sisa onboarding B (sudah dibuang) —
            // jumlah tidak berlipat walau onboarding B tadinya punya jumlah ruang yang sama (bukti tidak duplikat berdampingan).
            val roomsNowB = b.local.rooms.allRooms().map { it.id }.toSet()
            assertEquals(a.local.rooms.allRooms().map { it.id }.toSet(), roomsNowB)
            assertEquals(roomCountBeforeWipe, roomsNowB.size, "jumlah ruang tidak berlipat (onboarding B sudah dibuang, bukan ditambahkan)")
            assertEquals(TransactionKind.EXPENSE, b.local.transactions.find(expenseA.id)?.kind)
        } finally {
            a.close()
        }
    }
}
