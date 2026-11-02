package com.roziqrizal.rizqflow.domain.sync

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Turunan kunci dari `member_secret` (sync-desain.md bagian F, langkah 1-2; keputusan pemilik 2026-11-02/03).
 * Satu rahasia 32 byte per undangan dipakai untuk dua tujuan yang sengaja dipisah lewat label HKDF
 * (`info`), supaya hash yang dikirim ke server tidak pernah bisa dipakai membuka `key_grant`:
 *
 * - [inviteHash]: SHA-256 dari HKDF(rahasia, [INFO_INVITE]), heksadesimal huruf kecil. Ini yang dikirim
 *   ke server (`invite_hash`); server tidak bisa memulihkan rahasia dari sini.
 * - [keyGrantKey]: HKDF(rahasia, [INFO_KEY_GRANT]), kunci AEAD AES-256-GCM untuk `key_grant` (langkah 4).
 *
 * HKDF-SHA256 menurut RFC 5869 dengan garam kosong (sama dengan `hash_hkdf('sha256', ...)` PHP), jadi
 * server atau klien web bisa menurunkan nilai yang sama bila perlu. Murni JVM (`javax.crypto`), tanpa Tink.
 */
object MemberSecretKdf {
    const val INFO_INVITE = "rizqflow-invite"
    const val INFO_KEY_GRANT = "rizqflow-key-grant"

    private const val HASH_LENGTH = 32
    private const val MAC_ALGORITHM = "HmacSHA256"

    fun inviteHash(memberSecret: ByteArray): String {
        val okm = hkdfSha256(memberSecret, INFO_INVITE.toByteArray(Charsets.UTF_8), HASH_LENGTH)
        return MessageDigest.getInstance("SHA-256").digest(okm).joinToString("") { "%02x".format(it) }
    }

    fun keyGrantKey(memberSecret: ByteArray): ByteArray =
        hkdfSha256(memberSecret, INFO_KEY_GRANT.toByteArray(Charsets.UTF_8), HASH_LENGTH)

    /** HKDF-Extract lalu HKDF-Expand (RFC 5869) dengan SHA-256; [salt] kosong dipakai sebagai [HASH_LENGTH] byte nol. */
    internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(0)): ByteArray {
        require(length in 1..255 * HASH_LENGTH) { "Panjang keluaran HKDF tidak sah: $length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        val prk = hmac(effectiveSalt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).apply { init(SecretKeySpec(key, MAC_ALGORITHM)) }.doFinal(data)
}
