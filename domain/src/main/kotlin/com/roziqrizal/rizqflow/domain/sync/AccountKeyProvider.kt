package com.roziqrizal.rizqflow.domain.sync

/**
 * Enkripsi dan dekripsi AEAD (authenticated encryption) untuk satu kunci — akun atau ruang
 * keluarga (Tahap 13). Kontrak murni di `:domain` supaya logika sinkron tidak bergantung pada
 * Android Keystore atau Google Tink secara langsung; implementasi sungguhan ada di `:data`.
 */
interface SyncAead {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
}

/** Bentuk ekspor kunci akun yang diberikan ke [AccountKeyProvider.importAccountKeyFromRecovery] tidak bisa dibaca (rusak atau bukan keyset). */
class InvalidAccountKeyExport(cause: Throwable? = null) : Exception("Bentuk ekspor kunci akun tidak sah", cause)

/**
 * Kunci akun: dibuat sekali secara acak di perangkat dan disimpan di Android Keystore
 * (server-sync.md bagian "Model kunci"). Server tidak pernah melihat kunci ini dalam bentuk
 * terbuka — hanya salinan terbungkus kode pemulihan ([RecoveryKeyService]).
 */
interface AccountKeyProvider {
    /** Membuat kunci akun bila belum ada, atau memuat yang sudah tersimpan. Aman dipanggil berulang. */
    fun getOrCreateAccountKey(): SyncAead

    /**
     * Bentuk kunci akun yang bisa diekspor, hanya dipakai sesaat untuk dibungkus kode pemulihan
     * lewat [AccountKeyRecoveryCrypto.wrap] sebelum dikirim ke server — tidak pernah disimpan
     * sendiri dalam bentuk terbuka. Isinya buram bagi `:domain` (format sungguhan ditentukan
     * implementasi di `:data`).
     */
    fun exportAccountKeyForRecovery(): ByteArray

    /**
     * Memasang kunci akun hasil pemulihan ([exportedAccountKey] = keluaran [exportAccountKeyForRecovery]
     * dari perangkat lain, sudah dibuka dari bungkus kode pemulihan) dan MENGGABUNGKAN (merge) ke
     * keyset akun lokal — pola yang sama dengan [RoomKeyProvider.rotateRoomKey]: kunci pulihan
     * ditambahkan ke keyset SEBAGAI PRIMARY (dipakai untuk enkripsi berikutnya), kunci lokal yang
     * sudah ada (yang mungkin sudah mengenkripsi data cakupan pribadi di perangkat ini, sejak sinkron
     * entitas akun dibangun di Tahap 3 sync-desain.md) TETAP ADA di keyset yang sama sehingga
     * ciphertext lama tetap bisa didekripsi. Bila perangkat ini belum pernah punya kunci akun sama
     * sekali (benar-benar baru), tidak ada yang digabung — kunci pulihan langsung dipasang sebagai
     * satu-satunya kunci, sama seperti [RoomKeyProvider.importRoomKey] untuk ruang yang belum pernah dipakai.
     *
     * Kontrak keselamatan, wajib dipenuhi implementasi:
     * - [exportedAccountKey] divalidasi dan di-parse SEBELUM apa pun ditulis. Bila rusak, melempar
     *   [InvalidAccountKeyExport] dan kunci lokal yang ada tetap utuh dan tetap dipakai.
     * - Setelah kembali tanpa galat, [getOrCreateAccountKey] (termasuk [SyncAead] yang sudah dibagikan
     *   sebelumnya) memakai kunci pulihan sebagai PRIMARY untuk enkripsi baru, tetapi tetap bisa
     *   mendekripsi ciphertext yang dibuat dengan kunci lokal lama sebelum pemanggilan ini. Pemanggil
     *   tidak perlu mengambil ulang.
     * - Kunci lama TIDAK dibuang: tidak pernah dipakai lagi untuk enkripsi baru, tetapi tetap tersimpan
     *   di keyset yang sama untuk dekripsi, selamanya (tidak ada pembersihan otomatis di v1).
     */
    fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray)
}
