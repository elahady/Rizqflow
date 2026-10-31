package com.roziqrizal.rizqflow.domain.sync

/**
 * Bukti bahwa salinan otomatis berkas database lokal sudah dibuat sebelum menimpa (keputusan pemilik
 * #11: "Pulihkan" hanya dengan salinan otomatis). Hanya [LocalSafetyBackup] yang membuatnya; jalur
 * timpa ([NewDeviceRestoreFlow.overwrite]) menuntutnya sebagai parameter, jadi menimpa tanpa salinan
 * tidak bisa ditulis.
 */
class LocalBackupHandle(
    /** Lokasi salinan (path berkas di penyimpanan privat aplikasi); hanya untuk ditampilkan dan diperiksa ulang. */
    val location: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
)

/** Pembuat salinan otomatis berkas database lokal sebelum ditimpa. Implementasi ada di `:data`. */
fun interface LocalSafetyBackup {
    /** Null bila salinan gagal dibuat (ruang penuh, galat berkas): jalur timpa WAJIB berhenti. Tidak pernah melempar. */
    suspend fun take(): LocalBackupHandle?
}

/** Eksekutor penggantian ruang kerja lokal dengan data server. Implementasi Room: `PureWorkspaceReplacer`. */
interface WorkspaceReplacer {
    /** Membuang hasil onboarding bawaan saja; hanya untuk ruang kerja yang terbukti murni. */
    suspend fun wipeOnboardingAndResetCursor()

    /**
     * Membuang SEMUA data pribadi lokal (jalur "Pulihkan" saat ruang kerja tidak murni) lalu mereset kursor ke 0.
     * Menuntut [backup] dan memeriksa ulang bahwa salinannya masih ada sebelum menghapus apa pun.
     */
    suspend fun wipeAllAndResetCursor(backup: LocalBackupHandle)
}

/** Hasil satu langkah alur perangkat baru. */
sealed interface NewDeviceRestoreResult {
    /** Data server sudah ditarik (sync pribadi selesai, termasuk bila push masih digerbang). */
    data class Synced(val result: PersonalSyncResult.Success) : NewDeviceRestoreResult

    /**
     * Data lokal sudah dibuang (atau dibiarkan, untuk "Gabung") tetapi tarik dari server gagal: aman diulang
     * lewat [NewDeviceRestoreFlow.retrySync] (kursor sudah 0, tarik ulang idempoten).
     */
    data class SyncFailed(val result: PersonalSyncResult) : NewDeviceRestoreResult

    /** Ruang kerja ternyata sudah tidak murni saat akan diganti otomatis: tidak ada yang dihapus, tanya pengguna. */
    data object NoLongerPure : NewDeviceRestoreResult
}

/** Hasil persiapan "Pulihkan": salinan sudah dibuat (menunggu konfirmasi eksplisit) atau gagal. */
sealed interface OverwritePreparation {
    data class Ready(val backup: LocalBackupHandle) : OverwritePreparation

    data object BackupFailed : OverwritePreparation
}

/**
 * Alur perangkat baru setelah kunci akun dipulihkan (sync-desain.md bagian B "Perangkat baru", keputusan
 * pemilik #11): otomatis ganti bila ruang kerja murni; bila tidak, pengguna memilih "Gabung" (data lokal
 * dibiarkan, sync biasa berjalan, duplikat kosmetik diterima) atau "Pulihkan" (salinan otomatis berkas
 * database, konfirmasi eksplisit, lalu data lokal diganti data server).
 *
 * Urutan "Pulihkan": [prepareOverwrite] membuat salinan lebih dulu (tidak merusak apa pun); UI lalu meminta
 * konfirmasi eksplisit dengan menyebut salinan itu; baru [overwrite] yang menghapus. Gagal membuat salinan
 * berarti berhenti tanpa menghapus.
 */
class NewDeviceRestoreFlow(
    private val advisor: NewDeviceRecoveryAdvisor,
    private val purity: WorkspacePurityChecker,
    private val replacer: WorkspaceReplacer,
    private val backup: LocalSafetyBackup,
    private val sync: suspend () -> PersonalSyncResult,
) {
    suspend fun decide(): NewDeviceRecoveryOutcome = advisor.decide()

    /** Jalur [NewDeviceRecoveryOutcome.AutoReplace]: kemurnian diperiksa ULANG tepat sebelum menghapus. */
    suspend fun autoReplace(): NewDeviceRestoreResult {
        if (!purity.isPure()) return NewDeviceRestoreResult.NoLongerPure
        replacer.wipeOnboardingAndResetCursor()
        return syncResult()
    }

    /** "Gabung": tidak menghapus apa pun, hanya menjalankan sync pribadi biasa. */
    suspend fun join(): NewDeviceRestoreResult = syncResult()

    /** Langkah 1 "Pulihkan": salinan otomatis berkas database. Tidak menghapus apa pun. */
    suspend fun prepareOverwrite(): OverwritePreparation =
        backup.take()?.let { OverwritePreparation.Ready(it) } ?: OverwritePreparation.BackupFailed

    /** Langkah 2 "Pulihkan", hanya setelah konfirmasi eksplisit: hapus data lokal lalu tarik dari server. */
    suspend fun overwrite(ready: OverwritePreparation.Ready): NewDeviceRestoreResult {
        replacer.wipeAllAndResetCursor(ready.backup)
        return syncResult()
    }

    /** Ulangi tarik dari server setelah [NewDeviceRestoreResult.SyncFailed]. */
    suspend fun retrySync(): NewDeviceRestoreResult = syncResult()

    private suspend fun syncResult(): NewDeviceRestoreResult = when (val result = sync()) {
        is PersonalSyncResult.Success -> NewDeviceRestoreResult.Synced(result)
        else -> NewDeviceRestoreResult.SyncFailed(result)
    }
}
