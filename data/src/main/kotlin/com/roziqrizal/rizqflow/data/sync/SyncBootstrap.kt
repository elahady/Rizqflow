package com.roziqrizal.rizqflow.data.sync

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.SyncScope

/**
 * Backfill sekali saat sync pertama diaktifkan (Tahap 1 sync-desain.md, USULAN Claude): menandai
 * SEMUA data yang sudah ada sebelum [SyncRecorder] aktif (data pra-migrasi 8, data demo, hasil impor)
 * sebagai kotor di cakupan pribadi supaya terkirim di push pertama.
 *
 * - **Idempoten**: `INSERT OR IGNORE`, jadi rekaman yang sudah punya baris `sync_record` (diubah sejak
 *   migrasi, atau sudah terkirim) tidak ditimpa dan memanggilnya dua kali tidak mengubah apa-apa.
 * - **Dalam batch**: satu pernyataan berbasis himpunan per tabel di satu transaksi, tanpa memuat baris ke memori.
 * - `changed_at` = cap waktu entitas bila ada (transaksi `updated_at`, harta `updated_at`), selain itu
 *   cap MINIMAL ([BACKFILL_STAMP] = 1), BUKAN "sekarang". Data lama tanpa cap bawaan tidak punya waktu ubah yang
 *   benar; memberinya cap sekarang membuat salinan basi (mis. backup lama yang dipulihkan) bisa mengalahkan versi
 *   yang lebih baru di server. Dengan cap minimal, versi nyata mana pun (cap dari jam, atau `updated_at`) menang atas
 *   hasil backfill, dan bila server kosong hasil backfill tetap satu-satunya versi. Edit berikutnya memakai
 *   `SyncClock.stamp(changed_at lama)` = max(jam, 1 + 1), jadi tetap konsisten.
 * - Dilewati: akun anggota virtual (`shared_from` terisi), transaksi `origin = SHARED` (termasuk pengeluaran
 *   anggota lain hasil pull SyncEngine lama), harga emas `AUTO` (per perangkat), dan `app_setting` selain mode alokasi.
 *
 * Belum ada yang memanggilnya; Tahap 3 (cakupan pribadi inti) memanggilnya saat sync diaktifkan.
 */
class SyncBootstrap(private val db: RizqflowDatabase) {
    /** Mengembalikan jumlah rekaman yang BARU ditandai pada pemanggilan ini. */
    suspend fun markAllDirty(): Int = db.withTransaction {
        val dao = db.sync()
        val before = dao.count(SyncScope.Personal.wire)
        // Urutan mengikuti SyncRecordType.applyRank agar mudah dibaca; urutan sisip tidak memengaruhi hasil.
        dao.backfillRooms()
        dao.backfillAccounts()
        dao.backfillCategories()
        dao.backfillAllocationConfig()
        dao.backfillTransactions()
        dao.backfillDebts()
        dao.backfillDebtPayments()
        dao.backfillBills()
        dao.backfillRecurringRules()
        dao.backfillZakatProfiles()
        dao.backfillWealthItems()
        dao.backfillWealthChecks()
        dao.backfillZakatPayments()
        dao.backfillGoldPrices()
        dao.backfillTraderProfiles()
        dao.backfillDcaPlans()
        dao.backfillQuickFavorites()
        dao.backfillDayChecks()
        dao.count(SyncScope.Personal.wire) - before
    }

    companion object {
        /** Cap rekaman hasil backfill yang tidak punya cap waktu bawaan; harus sama dengan literal `1` di `SyncDao.backfill*`. */
        const val BACKFILL_STAMP = 1L
    }
}
