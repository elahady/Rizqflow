package com.roziqrizal.rizqflow.data.sync

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.repo.ALLOCATION_MODE_KEY
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

/**
 * Membuang hasil onboarding bawaan (ruang, akun, kategori, alokasi) dan jejak `sync_record`-nya, lalu
 * mereset kursor cakupan pribadi ke 0 — langkah SEBELUM menarik ulang (`PersonalSyncCoordinator.syncNow`,
 * dipanggil pemanggil SETELAH ini, bukan di sini) seluruh data pribadi akun ini dari server (Tahap 3
 * sync-desain.md bagian B "Perangkat baru").
 *
 * HANYA aman dipanggil setelah [com.roziqrizal.rizqflow.domain.sync.WorkspacePurityChecker.isPure]
 * terbukti true — [wipeOnboardingAndResetCursor] TIDAK memeriksa ulang sendiri (pemanggil, mis.
 * [com.roziqrizal.rizqflow.domain.sync.NewDeviceRecoveryAdvisor], yang menjaga). Dipanggil juga untuk
 * jalur "Pulihkan" yang dipilih pengguna secara eksplisit (ruang kerja TIDAK murni) — pemanggil WAJIB
 * mengambil salinan otomatis/backup dulu sebelum memanggil ini pada jalur itu (bagian B "Perangkat baru",
 * keputusan pemilik #11); kelas ini sendiri tidak tahu dan tidak membuat backup.
 */
class PureWorkspaceReplacer(private val db: RizqflowDatabase, private val store: SyncStore) {
    suspend fun wipeOnboardingAndResetCursor() {
        db.withTransaction {
            db.rooms().deleteAll() // kategori dan allocation_rule ikut ON DELETE CASCADE
            db.accounts().deleteAll()
            db.settings().delete(ALLOCATION_MODE_KEY)
            db.sync().deletePureOnboardingRecords()
        }
        store.setCursor(SyncScope.Personal, 0)
    }
}
