package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
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

    @Test
    fun `kunci yang diimpor menimpa kunci lama dan terbaca ulang oleh provider baru`() {
        val kunciLama = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val sumber = newProvider()
        val kunciDibagikan = sumber.getOrCreateRoomKey(RoomId("ruang-keluarga-sumber"))
        val diekspor = sumber.exportRoomKey(RoomId("ruang-keluarga-sumber"))

        newProvider().importRoomKey(ruangKeluarga, diekspor)

        val dimuatUlang = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val plaintext = "cicilan rumah bulan ini".toByteArray()
        assertContentEquals(plaintext, dimuatUlang.decrypt(kunciDibagikan.encrypt(plaintext)))
        assertFailsWith<Exception> { kunciLama.decrypt(kunciDibagikan.encrypt(plaintext)) }
    }

    @Test
    fun `pipa lengkap QR - ekspor, bungkus RoomInvite, pindai, impor - menghasilkan kunci yang sama`() {
        val pengundang = newProvider()
        val kunciPengundang = pengundang.getOrCreateRoomKey(ruangKeluarga)
        val qrBytes = RoomInvite(ruangKeluarga, "Keluarga", pengundang.exportRoomKey(ruangKeluarga)).encode()

        val undangan = RoomInvite.decode(qrBytes)
        val pasangan = newProvider()
        pasangan.importRoomKey(undangan.roomId, undangan.exportedRoomKey)
        val kunciPasangan = pasangan.getOrCreateRoomKey(undangan.roomId)

        val plaintext = "belanja bersama".toByteArray()
        assertContentEquals(plaintext, kunciPasangan.decrypt(kunciPengundang.encrypt(plaintext)))
        assertContentEquals(plaintext, kunciPengundang.decrypt(kunciPasangan.encrypt(plaintext)))
    }

    @Test
    fun `setelah rotasi, kunci baru tetap bisa membaca data yang dienkripsi sebelum rotasi`() {
        val provider = newProvider()
        val sebelumRotasi = provider.getOrCreateRoomKey(ruangKeluarga)
        val dataLama = sebelumRotasi.encrypt("transaksi sebelum anggota dikeluarkan".toByteArray())

        val sesudahRotasi = provider.rotateRoomKey(ruangKeluarga)

        assertContentEquals("transaksi sebelum anggota dikeluarkan".toByteArray(), sesudahRotasi.decrypt(dataLama))
    }

    @Test
    fun `anggota yang dikeluarkan tidak bisa membaca data baru setelah rotasi`() {
        val provider = newProvider()
        val kunciAnggotaDikeluarkan = provider.getOrCreateRoomKey(ruangKeluarga)

        val kunciBaru = provider.rotateRoomKey(ruangKeluarga)
        val dataBaru = kunciBaru.encrypt("transaksi setelah anggota dikeluarkan".toByteArray())

        assertFailsWith<Exception> { kunciAnggotaDikeluarkan.decrypt(dataBaru) }
    }

    @Test
    fun `rotasi tersimpan dan termuat ulang oleh provider baru, lengkap dengan kunci lama`() {
        val provider = newProvider()
        val sebelumRotasi = provider.getOrCreateRoomKey(ruangKeluarga)
        val dataLama = sebelumRotasi.encrypt("tabungan bersama".toByteArray())

        provider.rotateRoomKey(ruangKeluarga)

        val dimuatUlang = newProvider().getOrCreateRoomKey(ruangKeluarga)
        val dataBaru = dimuatUlang.encrypt("setelah rotasi".toByteArray())
        assertContentEquals("tabungan bersama".toByteArray(), dimuatUlang.decrypt(dataLama))
        assertContentEquals("setelah rotasi".toByteArray(), dimuatUlang.decrypt(dataBaru))
    }

    @Test
    fun `impor menggabung, bukan menimpa - data yang dienkripsi dengan kunci acak sendiri tetap terbaca`() {
        val anggota = newProvider()
        val dataSendiri = anggota.getOrCreateRoomKey(ruangKeluarga).encrypt("pengeluaran sebelum memindai QR".toByteArray())
        val pemilik = newProvider()
        val kunciPemilik = pemilik.getOrCreateRoomKey(RoomId("ruang-keluarga-sumber"))

        anggota.importRoomKey(ruangKeluarga, pemilik.exportRoomKey(RoomId("ruang-keluarga-sumber")))

        val dimuatUlang = newProvider().getOrCreateRoomKey(ruangKeluarga)
        assertContentEquals("pengeluaran sebelum memindai QR".toByteArray(), dimuatUlang.decrypt(dataSendiri))
        // Kunci yang diimpor jadi primary: pemilik bisa membaca apa yang dienkripsi anggota sesudah impor.
        assertContentEquals("catatan baru".toByteArray(), kunciPemilik.decrypt(dimuatUlang.encrypt("catatan baru".toByteArray())))
    }

    @Test
    fun `anggota yang tetap menerima keyset hasil rotasi tanpa kehilangan kunci lama`() {
        val pemilik = newProvider()
        val kunciAwal = pemilik.getOrCreateRoomKey(ruangKeluarga)
        val anggota = newProvider()
        anggota.importRoomKey(RoomId("ruang-anggota"), pemilik.exportRoomKey(ruangKeluarga))
        val dataLama = kunciAwal.encrypt("sebelum rotasi".toByteArray())

        // Pemilik mengeluarkan seseorang: rotasi, lalu keyset barunya dibagikan ke anggota yang tetap (key_grant).
        val kunciRotasi = pemilik.rotateRoomKey(ruangKeluarga)
        val dataBaru = kunciRotasi.encrypt("sesudah rotasi".toByteArray())
        anggota.importRoomKey(RoomId("ruang-anggota"), pemilik.exportRoomKey(ruangKeluarga))

        val kunciAnggota = newProvider().getOrCreateRoomKey(RoomId("ruang-anggota"))
        assertContentEquals("sebelum rotasi".toByteArray(), kunciAnggota.decrypt(dataLama))
        assertContentEquals("sesudah rotasi".toByteArray(), kunciAnggota.decrypt(dataBaru))
        // Dan anggota kini menulis dengan kunci rotasi, sehingga pemilik membacanya.
        assertContentEquals("balasan".toByteArray(), kunciRotasi.decrypt(kunciAnggota.encrypt("balasan".toByteArray())))
    }

    @Test
    fun `mengimpor keyset yang sama dua kali tidak mengubah keyset tersimpan`() {
        val pemilik = newProvider()
        pemilik.getOrCreateRoomKey(ruangKeluarga)
        val diekspor = pemilik.exportRoomKey(ruangKeluarga)
        val anggota = newProvider()

        anggota.importRoomKey(RoomId("ruang-anggota"), diekspor)
        val sesudahPertama = anggota.exportRoomKey(RoomId("ruang-anggota"))
        anggota.importRoomKey(RoomId("ruang-anggota"), diekspor)

        assertContentEquals(sesudahPertama, anggota.exportRoomKey(RoomId("ruang-anggota")))
        assertContentEquals(sesudahPertama, newProvider().exportRoomKey(RoomId("ruang-anggota")))
    }

    @Test
    fun `keyset impor yang rusak ditolak dan keyset tersimpan tidak berubah`() {
        val provider = newProvider()
        val kunci = provider.getOrCreateRoomKey(ruangKeluarga)
        val data = kunci.encrypt("masih utuh".toByteArray())
        val sebelum = provider.exportRoomKey(ruangKeluarga)

        assertFailsWith<Exception> { provider.importRoomKey(ruangKeluarga, byteArrayOf(1, 2, 3, 4)) }

        assertContentEquals(sebelum, newProvider().exportRoomKey(ruangKeluarga))
        assertContentEquals("masih utuh".toByteArray(), newProvider().getOrCreateRoomKey(ruangKeluarga).decrypt(data))
    }
}
