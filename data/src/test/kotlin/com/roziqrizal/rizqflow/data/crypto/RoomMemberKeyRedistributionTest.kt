package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.domain.sync.RoomMemberKeyRedistribution
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Keluarkan anggota: distribusi kunci yang sudah dirotasi (Tahap 9, roadmap.md "Keluarkan anggota:
 * kirim keyset baru ke anggota yang tetap") lewat [RoomMemberKeyRedistribution], memakai provider
 * Keystore sungguhan (Robolectric) seperti `AndroidKeystoreRoomKeyProviderTest`, supaya "anggota
 * tetap menerima keyset baru, anggota yang dikeluarkan tidak" teruji dengan kripto nyata, bukan
 * tiruan identitas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMemberKeyRedistributionTest {

    private fun newProvider() = AndroidKeystoreRoomKeyProvider(ApplicationProvider.getApplicationContext<Context>())
    private val roomId = RoomId("ruang-keluarga-1")
    private val roomName = "Keluarga"

    /** Pasangan seperti di sumber QR undangan pertama kali: memindai lalu mengimpor kunci pemilik. */
    private fun joinViaInvite(owner: AndroidKeystoreRoomKeyProvider): AndroidKeystoreRoomKeyProvider {
        val invite = RoomInvite.decode(RoomInvite(roomId, roomName, owner.exportRoomKey(roomId)).encode())
        val member = newProvider()
        member.importRoomKey(invite.roomId, invite.exportedRoomKey)
        return member
    }

    @Test
    fun `anggota tetap menerima keyset baru lewat QR ulang dan bisa membaca transaksi berikutnya`() {
        val owner = newProvider()
        owner.getOrCreateRoomKey(roomId) // kunci ruang ada sejak sebelum siapa pun dikeluarkan

        // Dua anggota bergabung lebih dulu lewat undangan pertama, sama-sama punya kunci lama.
        val anggotaTetap = joinViaInvite(owner)
        val anggotaDikeluarkan = joinViaInvite(owner)

        // Pemilik mengeluarkan satu anggota: rotasi lalu siapkan undangan baru (QR baru).
        val undanganBaru = RoomMemberKeyRedistribution(owner).rotateAndPrepareInvite(roomId, roomName)

        // Hanya anggota yang TETAP yang diajak memindai QR baru itu.
        anggotaTetap.importRoomKey(undanganBaru.roomId, undanganBaru.exportedRoomKey)

        val dataBaru = owner.getOrCreateRoomKey(roomId).encrypt("transaksi setelah anggota dikeluarkan".toByteArray())

        assertContentEquals(
            "transaksi setelah anggota dikeluarkan".toByteArray(),
            anggotaTetap.getOrCreateRoomKey(roomId).decrypt(dataBaru),
        )
        assertFailsWith<Exception>("anggota yang dikeluarkan tidak pernah menerima undangan baru, tidak boleh bisa membaca data baru") {
            anggotaDikeluarkan.getOrCreateRoomKey(roomId).decrypt(dataBaru)
        }
    }

    @Test
    fun `anggota yang dikeluarkan tetap bisa membaca data lama, bukan ikut terhapus`() {
        val owner = newProvider()
        val anggotaDikeluarkan = joinViaInvite(owner)
        val dataLama = owner.getOrCreateRoomKey(roomId).encrypt("belanja sebelum dikeluarkan".toByteArray())

        RoomMemberKeyRedistribution(owner).rotateAndPrepareInvite(roomId, roomName)

        // server-sync.md "Model kunci": data lama yang sudah ditarik anggota yang dikeluarkan tetap bisa dibaca.
        assertContentEquals("belanja sebelum dikeluarkan".toByteArray(), anggotaDikeluarkan.getOrCreateRoomKey(roomId).decrypt(dataLama))
    }

    @Test
    fun `anggota tetap yang mengimpor undangan baru juga masih bisa membaca data lama`() {
        val owner = newProvider()
        val anggotaTetap = joinViaInvite(owner)
        val dataLama = owner.getOrCreateRoomKey(roomId).encrypt("tabungan bersama".toByteArray())

        val undanganBaru = RoomMemberKeyRedistribution(owner).rotateAndPrepareInvite(roomId, roomName)
        anggotaTetap.importRoomKey(undanganBaru.roomId, undanganBaru.exportedRoomKey)

        assertContentEquals("tabungan bersama".toByteArray(), anggotaTetap.getOrCreateRoomKey(roomId).decrypt(dataLama))
    }

    @Test
    fun `rotateAndPrepareInvite membawa nama dan id ruang yang sama seperti undangan biasa`() {
        val owner = newProvider()
        owner.getOrCreateRoomKey(roomId)

        val undangan = RoomMemberKeyRedistribution(owner).rotateAndPrepareInvite(roomId, roomName)

        assertContentEquals(roomId.value.toByteArray(), undangan.roomId.value.toByteArray())
        kotlin.test.assertEquals(roomName, undangan.roomName)
    }
}
