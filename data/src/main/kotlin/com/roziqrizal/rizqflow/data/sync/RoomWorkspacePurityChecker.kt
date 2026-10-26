package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.WorkspacePurityChecker

/**
 * Implementasi Room [WorkspacePurityChecker] (Tahap 3 sync-desain.md bagian B "Perangkat baru"):
 * memeriksa SEMUA tabel yang berarti pengguna sudah memakai aplikasi untuk sesuatu yang nyata, bukan
 * hanya isi onboarding bawaan. Ruang/akun/kategori/alokasi SENGAJA tidak diperiksa di sini — itu
 * justru yang akan diganti bila ruang kerja terbukti murni.
 */
class RoomWorkspacePurityChecker(private val db: RizqflowDatabase) : WorkspacePurityChecker {
    override suspend fun isPure(): Boolean =
        db.transactions().count() == 0 &&
            db.debts().all().isEmpty() &&
            db.debts().allPayments().isEmpty() &&
            db.bills().all().isEmpty() &&
            db.recurring().all().isEmpty() &&
            db.zakat().activeProfiles().isEmpty() &&
            db.favorites().all().isEmpty()
}
