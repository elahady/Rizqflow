package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.domain.model.RoomId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Kunci per ruang Tahap 13: satu keyset Keystore terpisah per [RoomId], ruang lain tidak ikut terbuka. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidKeystoreRoomKeyProviderTest {

    private fun newProvider() = AndroidKeystoreRoomKeyProvider(ApplicationProvider.getApplicationContext<Context>())
    private val ruangKeluarga = RoomId("ruang-keluarga-1")

    @Test
    fun `enkripsi lalu dekripsi mengembalikan teks asli`() {
        val aead = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val plaintext = "belanja rumah tangga Rp 250.000".toByteArray()

        val ciphertext = aead.encrypt(plaintext)

        assertContentEquals(plaintext, aead.decrypt(ciphertext))
    }

    @Test
    fun `ruang lain punya kunci berbeda, tidak bisa membuka ciphertext ruang ini`() {
        val provider = newProvider()
        val kunciKeluarga = provider.getOrCreateRoomKey(ruangKeluarga)
        val kunciDiri = provider.getOrCreateRoomKey(RoomId("ruang-diri-pribadi"))
        val ciphertext = kunciKeluarga.encrypt("rahasia ruang keluarga".toByteArray())

        assertFailsWith<Exception> {
            kunciDiri.decrypt(ciphertext)
        }
    }

    @Test
    fun `kunci ruang yang sama dimuat ulang sama pada provider baru`() {
        val first = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val second = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val plaintext = "tagihan listrik".toByteArray()

        val ciphertext = first.encrypt(plaintext)

        assertContentEquals(plaintext, second.decrypt(ciphertext))
    }

    @Test
    fun `provider yang sama mengembalikan instans aead yang di-cache per ruang`() {
        val provider = newProvider()

        assertSame(provider.getOrCreateRoomKey(ruangKeluarga), provider.getOrCreateRoomKey(ruangKeluarga))
    }
}
