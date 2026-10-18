package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.backup.BackupCorruptOrWrongPassword
import com.roziqrizal.rizqflow.domain.backup.BackupCrypto
import java.security.SecureRandom

/** Kode pemulihan tidak cocok dengan salinan kunci akun terbungkus, atau datanya rusak. */
class RecoveryCodeInvalid : Exception("Kode pemulihan tidak cocok atau data rusak")

/**
 * Membungkus dan membuka salinan kunci akun dengan kode pemulihan (Tahap 13, server-sync.md):
 * "salinan kunci akun terbungkus kode itu disimpan di server, yang tidak bisa membukanya".
 * Memakai ulang [BackupCrypto] (AES-256-GCM, kunci turunan PBKDF2) — frasa 24 kata dipakai sebagai
 * "sandi"-nya, bukan kripto baru. Kunci akun sendiri tetap dibuat dan dikelola Tink di Keystore
 * ([com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider]); fungsi ini hanya membungkus bentuk
 * ekspornya untuk disimpan di tempat yang tidak dipercaya (server).
 */
object AccountKeyRecoveryCrypto {
    fun wrap(exportedAccountKey: ByteArray, recoveryCode: RecoveryCode, random: SecureRandom = SecureRandom()): ByteArray =
        BackupCrypto.encrypt(exportedAccountKey, recoveryCode.asPhrase(), random)

    /** @throws RecoveryCodeInvalid bila kode pemulihan salah atau data terbungkus rusak. */
    fun unwrap(wrapped: ByteArray, recoveryCode: RecoveryCode): ByteArray =
        try {
            BackupCrypto.decrypt(wrapped, recoveryCode.asPhrase())
        } catch (e: BackupCorruptOrWrongPassword) {
            throw RecoveryCodeInvalid()
        }
}
