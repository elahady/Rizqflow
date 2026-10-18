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

/**
 * Kunci akun: dibuat sekali secara acak di perangkat dan disimpan di Android Keystore
 * (server-sync.md bagian "Model kunci"). Server tidak pernah melihat kunci ini dalam bentuk
 * terbuka — hanya salinan terbungkus kode pemulihan (menyusul, belum diterapkan).
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
}
