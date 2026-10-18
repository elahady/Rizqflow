package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto
import com.roziqrizal.rizqflow.domain.sync.RecoveryCode
import com.roziqrizal.rizqflow.domain.sync.RecoveryCodeInvalid
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Kunci akun Tahap 13: dibuat sekali di Android Keystore lewat Tink, dimuat ulang sama setiap kali. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidKeystoreAccountKeyProviderTest {

    private fun newProvider() = AndroidKeystoreAccountKeyProvider(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `enkripsi lalu dekripsi mengembalikan teks asli`() {
        val aead = newProvider().getOrCreateAccountKey()
        val plaintext = "saldo Rp 1.500.000".toByteArray()

        val ciphertext = aead.encrypt(plaintext)

        assertContentEquals(plaintext, aead.decrypt(ciphertext))
    }

    @Test
    fun `associated data yang berbeda menolak dekripsi`() {
        val aead = newProvider().getOrCreateAccountKey()
        val ciphertext = aead.encrypt("rahasia".toByteArray(), "ruang-1".toByteArray())

        assertFailsWith<Exception> {
            aead.decrypt(ciphertext, "ruang-2".toByteArray())
        }
    }

    @Test
    fun `kunci yang sama dimuat ulang pada provider baru untuk konteks yang sama`() {
        val first = newProvider().getOrCreateAccountKey()
        val second = newProvider().getOrCreateAccountKey()
        val plaintext = "dompet".toByteArray()

        val ciphertext = first.encrypt(plaintext)

        assertContentEquals(plaintext, second.decrypt(ciphertext))
    }

    @Test
    fun `provider yang sama mengembalikan instans aead yang di-cache`() {
        val provider = newProvider()

        assertSame(provider.getOrCreateAccountKey(), provider.getOrCreateAccountKey())
    }

    @Test
    fun `keyset yang diekspor lalu dibungkus kode pemulihan bisa dipulihkan jadi aead yang setara`() {
        val provider = newProvider()
        val original = provider.getOrCreateAccountKey()
        val recoveryCode = RecoveryCode.generate()

        val wrapped = AccountKeyRecoveryCrypto.wrap(provider.exportAccountKeyForRecovery(), recoveryCode)
        val restoredKeyset = AccountKeyRecoveryCrypto.unwrap(wrapped, recoveryCode)
        val restoredHandle = TinkProtoKeysetFormat.parseKeyset(restoredKeyset, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        val restoredAead = restoredHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        val plaintext = "dompet dipulihkan dari kode pemulihan".toByteArray()
        val noAssociatedData = ByteArray(0)
        assertContentEquals(plaintext, restoredAead.decrypt(original.encrypt(plaintext), noAssociatedData))
        assertContentEquals(plaintext, original.decrypt(restoredAead.encrypt(plaintext, noAssociatedData)))
    }

    @Test
    fun `ekspor dibungkus kode pemulihan yang salah tidak bisa dibuka`() {
        val provider = newProvider()
        val wrapped = AccountKeyRecoveryCrypto.wrap(provider.exportAccountKeyForRecovery(), RecoveryCode.generate())

        assertFailsWith<RecoveryCodeInvalid> {
            AccountKeyRecoveryCrypto.unwrap(wrapped, RecoveryCode.generate())
        }
    }
}
