package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemberSecretKdfTest {
    private val secret = ByteArray(MEMBER_SECRET_LENGTH) { it.toByte() }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `HKDF-SHA256 cocok dengan vektor uji 1 RFC 5869`() {
        val okm = MemberSecretKdf.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )

        assertContentEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865".hexToBytes(),
            okm,
        )
    }

    @Test
    fun `hash undangan cocok dengan hash_hkdf PHP untuk rahasia 0 sampai 31`() {
        // Dihitung terpisah di PHP: hash('sha256', hash_hkdf('sha256', $rahasia, 32, 'rizqflow-invite', '')).
        assertEquals("24daa6ad6c168a67d48af38a647163e1ad7db9ab3c6992f5e812e3605075ebe5", MemberSecretKdf.inviteHash(secret))
    }

    @Test
    fun `kunci key_grant cocok dengan hash_hkdf PHP untuk rahasia 0 sampai 31`() {
        // Dihitung terpisah di PHP: hash_hkdf('sha256', $rahasia, 32, 'rizqflow-key-grant', '').
        assertContentEquals(
            "54dc10b7525c02bc7aa3ea320558056e6f8a5bf582f47f0fe04e0b81a755a9cf".hexToBytes(),
            MemberSecretKdf.keyGrantKey(secret),
        )
    }

    @Test
    fun `hash undangan 64 heksadesimal huruf kecil dan tidak mengungkap kunci key_grant`() {
        val hash = MemberSecretKdf.inviteHash(secret)

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        // Label berbeda memisahkan keduanya: hash yang dikirim ke server bukan kunci key_grant.
        assertNotEquals(hash, MemberSecretKdf.keyGrantKey(secret).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `rahasia berbeda menghasilkan hash berbeda dan rahasia yang sama stabil`() {
        val lain = ByteArray(MEMBER_SECRET_LENGTH) { (it + 1).toByte() }

        assertEquals(MemberSecretKdf.inviteHash(secret), MemberSecretKdf.inviteHash(secret.copyOf()))
        assertNotEquals(MemberSecretKdf.inviteHash(secret), MemberSecretKdf.inviteHash(lain))
    }
}
