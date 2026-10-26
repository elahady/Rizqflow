package com.roziqrizal.rizqflow.domain.sync

/**
 * Apakah ruang kerja lokal masih "murni": database baru yang hanya berisi hasil onboarding bawaan
 * (Tiga hak/Kosong), belum ada transaksi atau perubahan nyata lain dari pengguna (Tahap 3
 * sync-desain.md bagian B "Perangkat baru"). Implementasi Room ada di `:data`.
 *
 * Kriteria MURNI (konservatif, bukan berdasar nama/isi ruang-akun bawaan yang bisa berubah):
 * tidak ada SATU PUN transaksi, utang, tagihan, aturan berulang, profil zakat, atau favorit cepat.
 * Ruang/akun/kategori/alokasi hasil onboarding sendiri BOLEH ada (justru itu yang mau diganti) —
 * yang diperiksa adalah apakah pengguna sudah memakainya untuk sesuatu yang nyata.
 */
interface WorkspacePurityChecker {
    suspend fun isPure(): Boolean
}

/**
 * Keputusan alur pulihkan perangkat baru (sync-desain.md bagian B "Perangkat baru", keputusan
 * pemilik #11: "otomatis pulihkan hanya bila murni; selain itu tanya Gabung/Pulihkan dengan salinan
 * otomatis").
 */
sealed interface NewDeviceRecoveryOutcome {
    /**
     * Ruang kerja murni DAN akun ini punya salinan kode pemulihan di server: aman diganti otomatis
     * TANPA bertanya pengguna. Eksekusi nyata (hapus hasil onboarding lalu pull dari kursor 0) bukan
     * tugas [NewDeviceRecoveryAdvisor] — lihat implementasi penggantian di `:data`.
     */
    data object AutoReplace : NewDeviceRecoveryOutcome

    /**
     * Ruang kerja TIDAK murni (pengguna sudah mulai memakai aplikasi di perangkat baru ini sebelum
     * pulihkan): JANGAN otomatis menimpa. Sediakan titik keputusan "Gabung" (biarkan sync biasa
     * berjalan — data lokal dan server akan berdampingan, risiko duplikat kosmetik diterima) atau
     * "Pulihkan" (timpa dengan data server, HANYA setelah salinan otomatis/backup) ke pengguna.
     */
    data object AskUser : NewDeviceRecoveryOutcome

    /** Server tidak punya salinan kode pemulihan untuk akun ini (atau tidak bisa dipastikan): tidak ada apa pun untuk ditawarkan. */
    data object NothingToRecover : NewDeviceRecoveryOutcome
}

/**
 * Menentukan [NewDeviceRecoveryOutcome] murni dari dua sinyal: [RecoveryKeyService.checkRemoteCopy]
 * (apakah akun ini punya salinan kode pemulihan di server) dan [WorkspacePurityChecker.isPure]. TIDAK
 * mengubah apa pun sendiri — murni lapisan keputusan, supaya bisa diuji tanpa database atau jaringan
 * sungguhan. UI (menyusul, bukan cakupan Tahap 3 ini) memanggil [decide] sekali setelah masuk di
 * perangkat baru, lalu memanggil eksekusi yang sesuai (lihat KDoc tiap varian [NewDeviceRecoveryOutcome]).
 */
class NewDeviceRecoveryAdvisor(
    private val recovery: RecoveryKeyService,
    private val purity: WorkspacePurityChecker,
) {
    suspend fun decide(): NewDeviceRecoveryOutcome {
        if (recovery.checkRemoteCopy() != RemoteCopyCheck.EXISTS) return NewDeviceRecoveryOutcome.NothingToRecover
        return if (purity.isPure()) NewDeviceRecoveryOutcome.AutoReplace else NewDeviceRecoveryOutcome.AskUser
    }
}
