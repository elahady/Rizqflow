package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.repo.ALLOCATION_MODE_KEY
import com.roziqrizal.rizqflow.domain.sync.LocalBackupHandle
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore
import com.roziqrizal.rizqflow.domain.sync.WorkspaceReplacer
import java.io.File

/**
 * Membuang data lokal lalu mereset kursor cakupan pribadi ke 0 — langkah SEBELUM menarik ulang
 * (`PersonalSyncCoordinator.syncNow`, dipanggil pemanggil SETELAH ini, bukan di sini) seluruh data pribadi
 * akun ini dari server (Tahap 3 sync-desain.md bagian B "Perangkat baru"). Alur lengkapnya di
 * [com.roziqrizal.rizqflow.domain.sync.NewDeviceRestoreFlow].
 *
 * - [wipeOnboardingAndResetCursor]: hanya hasil onboarding bawaan (ruang, akun, kategori, alokasi). HANYA aman
 *   setelah [com.roziqrizal.rizqflow.domain.sync.WorkspacePurityChecker.isPure] terbukti true; tidak memeriksa
 *   ulang sendiri (pemanggil yang menjaga).
 * - [wipeAllAndResetCursor]: jalur "Pulihkan" saat ruang kerja TIDAK murni (keputusan pemilik #11), menuntut
 *   bukti salinan otomatis dan memeriksa ulang bahwa berkasnya masih ada sebelum menghapus apa pun. Ruang yang
 *   dibagi ikut terhapus (baris `shared_room` CASCADE), jadi keanggotaan ruang keluarga di perangkat ini harus
 *   diaktifkan ulang setelahnya; kunci ruang di Keystore tidak disentuh.
 */
class PureWorkspaceReplacer(private val db: RizqflowDatabase, private val store: SyncStore) : WorkspaceReplacer {
    override suspend fun wipeOnboardingAndResetCursor() {
        db.withTransaction {
            db.rooms().deleteAll() // kategori dan allocation_rule ikut ON DELETE CASCADE
            db.accounts().deleteAll()
            db.settings().delete(ALLOCATION_MODE_KEY)
            db.sync().deletePureOnboardingRecords()
        }
        store.setCursor(SyncScope.Personal, 0)
    }

    override suspend fun wipeAllAndResetCursor(backup: LocalBackupHandle) {
        val copy = File(backup.location)
        check(copy.isFile && copy.length() > 0L) { "Salinan otomatis tidak ditemukan; data lokal tidak dihapus" }
        db.withTransaction {
            val sync = db.sync()
            sync.wipeZakatPayments()
            sync.wipeWealthChecks()
            sync.wipeWealthItems()
            sync.wipeZakatProfiles()
            sync.wipeManualGoldPrices()
            sync.wipeDebtPayments()
            sync.wipeDebts()
            sync.wipeBills()
            sync.wipeRecurringRules()
            sync.wipeDcaPlans()
            sync.wipeTraderProfiles()
            sync.wipeQuickFavorites()
            sync.wipeDayChecks()
            sync.wipeAllocationEntries()
            sync.wipeTransactions()
            db.rooms().deleteAll() // kategori, allocation_rule, shared_room, pending_room_tombstone ikut CASCADE
            db.accounts().deleteAll()
            db.settings().delete(ALLOCATION_MODE_KEY)
            sync.wipeSyncRecords()
            sync.wipeSyncIssues()
            sync.wipeSyncCursors()
        }
        store.setCursor(SyncScope.Personal, 0)
    }
}
