package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.WorkspacePurityChecker

/**
 * Implementasi Room [WorkspacePurityChecker] (Tahap 3 sync-desain.md bagian B "Perangkat baru"):
 * memeriksa SEMUA tabel yang berarti pengguna sudah memakai aplikasi untuk sesuatu yang nyata, bukan
 * hanya isi onboarding bawaan. Ruang/akun/kategori/alokasi SENGAJA tidak diperiksa di sini — itu
 * justru yang akan diganti bila ruang kerja terbukti murni.
 *
 * Sejak Tahap 4 mencakup juga utang dan pembayarannya, tagihan, berulang, `zakat_*` (profil termasuk
 * yang diarsipkan, harta, pemeriksaan, pembayaran), `gold_price` MANUAL, `trader_profile`, `dca_plan`,
 * favorit, dan `day_check`. Daftar tabelnya ada di [USAGE_TABLES]; `PureWorkspaceReplacerTest` menjaga
 * bahwa setiap tabel di [SyncCoverage] masuk salah satu dari [USAGE_TABLES], [ONBOARDING_TABLES], atau
 * [LOCAL_STATE_TABLES], jadi tabel baru tidak bisa lolos tanpa diputuskan.
 */
class RoomWorkspacePurityChecker(private val db: RizqflowDatabase) : WorkspacePurityChecker {
    override suspend fun isPure(): Boolean = usage().values.all { it == 0 }

    /** Jumlah baris per tabel pemakaian nyata (nama tabel ke jumlah). */
    suspend fun usage(): Map<String, Int> = db.sync().workspaceUsage().byTable()

    companion object {
        /** Tabel yang satu barisnya saja sudah berarti ruang kerja TIDAK murni. */
        val USAGE_TABLES: Set<String> = setOf(
            "money_transaction", "allocation_entry", "debt", "debt_payment", "bill", "recurring_rule",
            "zakat_profile", "wealth_item", "wealth_check", "zakat_payment", "gold_price",
            "trader_profile", "dca_plan", "quick_favorite", "day_check",
        )

        /** Hasil onboarding bawaan yang boleh ada di ruang kerja murni (dan yang diganti [PureWorkspaceReplacer]). */
        val ONBOARDING_TABLES: Set<String> = setOf("room", "account", "category", "allocation_rule", "app_setting")

        /** Keadaan sinkron lokal, bukan data pengguna. */
        val LOCAL_STATE_TABLES: Set<String> = setOf("sync_record", "sync_cursor", "sync_issue", "shared_room", "pending_room_tombstone")
    }
}
