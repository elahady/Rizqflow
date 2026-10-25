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
     * dari perangkat lain, sudah dibuka dari bungkus kode pemulihan) dan MENIMPA kunci akun lokal.
     *
     * Kontrak keselamatan, wajib dipenuhi implementasi:
     * - [exportedAccountKey] divalidasi dan di-parse SEBELUM apa pun ditulis. Bila rusak, melempar
     *   [InvalidAccountKeyExport] dan kunci lokal yang ada tetap utuh dan tetap dipakai.
     * - Setelah kembali tanpa galat, [getOrCreateAccountKey] (termasuk [SyncAead] yang sudah dibagikan
     *   sebelumnya) memakai kunci pulihan, bukan kunci lama. Pemanggil tidak perlu mengambil ulang.
     * - Kunci lama tidak lagi bisa dipakai setelah ini. SAAT INI aman karena belum ada data lokal yang
     *   dienkripsi dengan kunci akun (sync data akun belum dibangun; yang ada baru kunci ruang). Begitu
     *   sync entitas akun ada, pulihkan WAJIB menjaga data lama (mis. menyimpan kunci lama di keyset yang
     *   sama seperti `rotateRoomKey`) sebelum menimpa. Itu kewajiban butir sync berikutnya, bukan di sini.
     */
    fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray)
}
