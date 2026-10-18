package com.roziqrizal.rizqflow.domain.sync

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AccountKeyRecoveryCryptoTest {
    private val random = SecureRandom()

    @Test
    fun `bolak-balik dengan kode pemulihan yang benar mengembalikan data asli`() {
        val exportedKey = "pura-pura keyset terserialisasi".toByteArray()
        val code = RecoveryCode.generate(random)

        val wrapped = AccountKeyRecoveryCrypto.wrap(exportedKey, code, random)

        assertContentEquals(exportedKey, AccountKeyRecoveryCrypto.unwrap(wrapped, code))
    }

    @Test
    fun `kode pemulihan yang salah melempar galat, bukan data sampah`() {
        val wrapped = AccountKeyRecoveryCrypto.wrap("rahasia".toByteArray(), RecoveryCode.generate(random), random)

        assertFailsWith<RecoveryCodeInvalid> {
            AccountKeyRecoveryCrypto.unwrap(wrapped, RecoveryCode.generate(random))
        }
    }

    @Test
    fun `data terbungkus yang rusak melempar galat`() {
        val code = RecoveryCode.generate(random)
        val wrapped = AccountKeyRecoveryCrypto.wrap("rahasia".toByteArray(), code, random)
        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1] + 1).toByte()

        assertFailsWith<RecoveryCodeInvalid> { AccountKeyRecoveryCrypto.unwrap(wrapped, code) }
    }
}
