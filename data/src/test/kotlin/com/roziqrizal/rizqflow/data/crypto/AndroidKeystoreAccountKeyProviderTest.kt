package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
