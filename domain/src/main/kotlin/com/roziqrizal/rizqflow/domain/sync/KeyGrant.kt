package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** `key_grant` tidak bisa dibuka: rahasia salah, bingkai rusak, atau AAD (ruang/id) tidak cocok. */
class KeyGrantException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Rekaman `key_grant` (sync-desain.md bagian F langkah 4): keyset kunci ruang yang BARU (hasil rotasi setelah
 * mengeluarkan anggota) dibungkus per anggota yang tetap, dikirim lewat log ruang yang sama. Berbeda dari rekaman
 * lain, grant TIDAK disegel kunci ruang ([SyncEnvelope]): penerima belum punya kunci barunya, dan pemegang kunci lama
 * (termasuk anggota yang baru dikeluarkan) tidak boleh bisa membacanya. Yang membukanya `member_secret` penerima,
 * rahasia 32 byte dari QR undangannya (lihat [RoomInvite]); pemilik menyimpan salinannya ([MemberSecretStore]).
 *
 * Kolom luar di server: `record_type` = [RECORD_TYPE], `record_id` = [recordId] = tag penerima (32 heksadesimal,
 * [tag]) + epoch (8 heksadesimal, [epochOf]), `payload` = base64 bingkai [seal]. Tag membuat penerima mengenali
 * barisnya tanpa mencoba membuka milik orang lain; ia stabil per (anggota, ruang), jadi server bisa menghubungkan
 * grant-grant untuk anggota yang sama tetapi tidak tahu siapa dia (id anggota tidak ada di sini) maupun isinya.
 * Epoch dari isi keyset, jadi dua rotasi berbeda tidak berbenturan di `record_id` dan mengirim ulang grant yang
 * sama menimpa dirinya sendiri.
 *
 * Bingkai: versi (1 byte, [VERSION]) | nonce (12 byte) | ciphertext+tag AES-256-GCM. Kunci =
 * [MemberSecretKdf.keyGrantKey]. AAD = `rizqflow-key-grant|v1|<roomId>|<recordId>`: grant yang dipindah ke ruang lain
 * atau diberi id lain gagal dibuka. Murni JVM (`javax.crypto`).
 */
object KeyGrant {
    /** Nilai kolom luar `record_type` untuk grant; BEDA dari [ENVELOPE_RECORD_TYPE] ("r"). */
    const val RECORD_TYPE = "g"

    private const val VERSION: Byte = 1
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    private const val TAG_HEX_LENGTH = 32
    private const val EPOCH_HEX_LENGTH = 8

    /** Tag penerima untuk ruang ini: 16 byte HKDF dari rahasia dengan label yang memuat id ruang. */
    fun tag(memberSecret: ByteArray, roomId: RoomId): String =
        MemberSecretKdf.hkdfSha256(memberSecret, "rizqflow-grant-tag|${roomId.value}".toByteArray(Charsets.UTF_8), TAG_HEX_LENGTH / 2)
            .toHex()

    /** Epoch dari isi keyset: 4 byte pertama SHA-256, heksadesimal. Keyset yang sama selalu menghasilkan epoch yang sama. */
    fun epochOf(keyset: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(keyset).copyOf(EPOCH_HEX_LENGTH / 2).toHex()

    fun recordId(tag: String, epoch: String): String = tag + epoch

    /** Apakah [recordId] ditujukan untuk pemilik [memberSecret] di [roomId] (sekadar awalan tag, belum membuktikan apa pun). */
    fun belongsTo(memberSecret: ByteArray, roomId: RoomId, recordId: String): Boolean =
        recordId.length == TAG_HEX_LENGTH + EPOCH_HEX_LENGTH && recordId.startsWith(tag(memberSecret, roomId))

    class Sealed(val recordId: String, val frame: ByteArray)

    fun seal(memberSecret: ByteArray, roomId: RoomId, keyset: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val recordId = recordId(tag(memberSecret, roomId), epochOf(keyset))
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(MemberSecretKdf.keyGrantKey(memberSecret), "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(roomId, recordId))
        }
        val ciphertext = cipher.doFinal(keyset)
        return Sealed(recordId, ByteBuffer.allocate(1 + NONCE_LENGTH + ciphertext.size).put(VERSION).put(nonce).put(ciphertext).array())
    }

    /** @throws KeyGrantException bila [frame] tidak bisa dibuka dengan [memberSecret] untuk [roomId] dan [recordId] ini. */
    fun open(memberSecret: ByteArray, roomId: RoomId, recordId: String, frame: ByteArray): ByteArray {
        if (frame.size < 1 + NONCE_LENGTH + TAG_BITS / 8 || frame[0] != VERSION) throw KeyGrantException("bingkai key_grant tidak sah")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(MemberSecretKdf.keyGrantKey(memberSecret), "AES"),
                    GCMParameterSpec(TAG_BITS, frame, 1, NONCE_LENGTH),
                )
                updateAAD(aad(roomId, recordId))
            }.doFinal(frame, 1 + NONCE_LENGTH, frame.size - 1 - NONCE_LENGTH)
        } catch (e: GeneralSecurityException) {
            throw KeyGrantException("key_grant tidak bisa dibuka", e)
        }
    }

    private fun aad(roomId: RoomId, recordId: String): ByteArray =
        "rizqflow-key-grant|v1|${roomId.value}|$recordId".toByteArray(Charsets.UTF_8)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
