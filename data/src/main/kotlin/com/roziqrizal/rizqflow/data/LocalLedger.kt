package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room
import com.roziqrizal.rizqflow.data.db.MIGRATION_1_2
import com.roziqrizal.rizqflow.data.db.MIGRATION_2_3
import com.roziqrizal.rizqflow.data.db.MIGRATION_3_4
import com.roziqrizal.rizqflow.data.db.MIGRATION_4_5
import com.roziqrizal.rizqflow.data.db.MIGRATION_5_6
import com.roziqrizal.rizqflow.data.db.MIGRATION_6_7
import com.roziqrizal.rizqflow.data.db.MIGRATION_7_8
import com.roziqrizal.rizqflow.data.db.MIGRATION_8_9
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SharedRoomAutoSync
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
import com.roziqrizal.rizqflow.data.sync.RoomSyncStore
import com.roziqrizal.rizqflow.data.sync.SyncBootstrap
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Ruang kerja data satu akun: satu database beserta repositorinya (diputuskan 2026-09-21).
 * Berganti akun berarti menutup ini dan membuka yang lain; akun yang sama selalu kembali ke
 * berkas yang sama, dan Keluar tidak menghapus berkas.
 */
class LocalLedger(
    val db: RizqflowDatabase,
    val syncClock: SyncClock = SyncClock(),
    /** Lingkup untuk sinkron otomatis ruang bersama; dibatalkan saat ruang kerja ditutup. Tes memberi lingkup sinkron. */
    private val autoSyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    /** Pencatat perubahan lokal untuk sinkron; dipakai bersama oleh semua repositori tulis (Tahap 1 sync-desain.md). */
    val syncRecorder = SyncRecorder(db, syncClock)

    /** Penyimpanan sinkron (rekaman, kursor, masalah, ruang bersama); dipakai mesin sinkron tahap berikutnya. */
    val syncStore = RoomSyncStore(db)

    /** Backfill sekali saat sync pertama diaktifkan. */
    val syncBootstrap = SyncBootstrap(db)

    /** Sinkron otomatis ruang bersama (Tahap 9); sinkron sungguhan dipasang lewat `attach` oleh ruang kerja. */
    val autoSync = SharedRoomAutoSync(syncStore, autoSyncScope)

    val workspace = LocalWorkspaceRepository(db, syncRecorder)
    val accounts = LocalAccountRepository(db, syncRecorder)
    val rooms = LocalRoomRepository(db, syncRecorder)
    val transactions = LocalTransactionRepository(db, syncRecorder, autoSync)
    val favorites = LocalFavoriteRepository(db, syncRecorder)
    val zakat = LocalZakatRepository(db, syncRecorder)
    val roles = LocalRoleRepository(db, syncRecorder)
    val recurring = LocalRecurringRepository(db, syncRecorder)
    val bills = LocalBillRepository(db, syncRecorder)
    val debts = LocalDebtRepository(db, syncRecorder)
    val settings = LocalSettingsRepository(db, syncRecorder)

    /** Berkas SQLite di balik database ini, dipakai apa adanya untuk cadangan (S20). */
    val databaseFile: File get() = File(db.openHelper.writableDatabase.path!!)

    /**
     * Menyatukan perubahan yang masih di berkas `-wal` (Room memakai mode WAL) ke berkas utama,
     * supaya [databaseFile] menjadi salinan yang lengkap tanpa perlu menutup koneksi ini.
     */
    fun checkpoint() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    override fun close() {
        // Sinkron yang masih berjalan dibatalkan dulu, supaya tidak menyentuh database yang sudah ditutup.
        autoSyncScope.cancel()
        db.close()
    }

    companion object {
        /**
         * Membuka (atau membuat) database milik [accountId]. Tidak memakai migrasi destruktif: skema
         * yang naik versi wajib punya migrasi (docs/model-data.md).
         */
        fun open(context: Context, accountId: String): LocalLedger {
            val databaseName = AccountStorage.databaseName(accountId)
            // Pagar migrasi: berkas disalin sebelum Room memigrasi database yang versinya di bawah 8, TEPAT sebelum akses
            // database pertama (di thread yang menyentuh Room, bukan di sini), dan tidak pernah melempar.
            val guard = PreMigrateGuard(context.applicationContext.getDatabasePath(databaseName))
            val db = Room.databaseBuilder(
                context.applicationContext,
                RizqflowDatabase::class.java,
                databaseName,
            ).openHelperFactory(guard.openHelperFactory()).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).addCallback(guard.callback()).build()
            return LocalLedger(db)
        }
    }
}
