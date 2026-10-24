package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room
import com.roziqrizal.rizqflow.data.db.MIGRATION_1_2
import com.roziqrizal.rizqflow.data.db.MIGRATION_2_3
import com.roziqrizal.rizqflow.data.db.MIGRATION_3_4
import com.roziqrizal.rizqflow.data.db.MIGRATION_4_5
import com.roziqrizal.rizqflow.data.db.MIGRATION_5_6
import com.roziqrizal.rizqflow.data.db.MIGRATION_6_7
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.repo.LocalAccountRepository
import com.roziqrizal.rizqflow.data.repo.LocalBillRepository
import com.roziqrizal.rizqflow.data.repo.LocalDebtRepository
import com.roziqrizal.rizqflow.data.repo.LocalFavoriteRepository
import com.roziqrizal.rizqflow.data.repo.LocalRecurringRepository
import com.roziqrizal.rizqflow.data.repo.LocalRoleRepository
import com.roziqrizal.rizqflow.data.repo.LocalRoomRepository
import com.roziqrizal.rizqflow.data.repo.LocalSettingsRepository
import com.roziqrizal.rizqflow.data.repo.LocalTransactionRepository
import com.roziqrizal.rizqflow.data.repo.LocalWorkspaceRepository
import com.roziqrizal.rizqflow.data.repo.LocalZakatRepository
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import java.io.File

/**
 * Ruang kerja data satu akun: satu database beserta repositorinya (diputuskan 2026-09-21).
 * Berganti akun berarti menutup ini dan membuka yang lain; akun yang sama selalu kembali ke
 * berkas yang sama, dan Keluar tidak menghapus berkas.
 */
class LocalLedger(val db: RizqflowDatabase) : AutoCloseable {
    val workspace = LocalWorkspaceRepository(db)
    val accounts = LocalAccountRepository(db)
    val rooms = LocalRoomRepository(db)
    val transactions = LocalTransactionRepository(db)
    val favorites = LocalFavoriteRepository(db)
    val zakat = LocalZakatRepository(db)
    val roles = LocalRoleRepository(db)
    val recurring = LocalRecurringRepository(db)
    val bills = LocalBillRepository(db)
    val debts = LocalDebtRepository(db)
    val settings = LocalSettingsRepository(db)

    /** Berkas SQLite di balik database ini, dipakai apa adanya untuk cadangan (S20). */
    val databaseFile: File get() = File(db.openHelper.writableDatabase.path!!)

    /**
     * Menyatukan perubahan yang masih di berkas `-wal` (Room memakai mode WAL) ke berkas utama,
     * supaya [databaseFile] menjadi salinan yang lengkap tanpa perlu menutup koneksi ini.
     */
    fun checkpoint() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    override fun close() = db.close()

    companion object {
        /**
         * Membuka (atau membuat) database milik [accountId]. Tidak memakai migrasi destruktif: skema
         * yang naik versi wajib punya migrasi (docs/model-data.md).
         */
        fun open(context: Context, accountId: String): LocalLedger {
            val db = Room.databaseBuilder(
                context.applicationContext,
                RizqflowDatabase::class.java,
                AccountStorage.databaseName(accountId),
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
            return LocalLedger(db)
        }
    }
}
