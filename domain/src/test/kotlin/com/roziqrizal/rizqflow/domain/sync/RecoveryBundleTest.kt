package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Isi plaintext bungkusan kode pemulihan format `version` 2 (Tahap 13, roadmap.md "Masukkan kunci ruang
 * keluarga..."): bolak-balik encode/decode kunci akun plus kunci ruang, dan checksum menangkap kerusakan.
 * Golden test tanpa mock, sama gayanya dengan [RoomInviteTest].
 */
class RecoveryBundleTest {

    private val accountKey = byteArrayOf(1, 2, 3, 4, 5, 9, 9, 9)
    private val bundle = RecoveryBundle(
        accountKey,
        listOf(
            RecoveryBundle.RoomKeyEntry(RoomId("ruang-keluarga-1"), byteArrayOf(10, 20, 30)),
            RecoveryBundle.RoomKeyEntry(RoomId("ruang-keluarga-2"), byteArrayOf(40, 50, 60, 70)),
        ),
    )

    @Test
    fun `decode hasil encode mengembalikan bundle yang sama, kunci akun dan semua kunci ruang`() {
        assertEquals(bundle, RecoveryBundle.decode(bundle.encode()))
    }

    @Test
    fun `tanpa ruang sama sekali (version 2 tapi tidak mengikuti ruang apa pun) tetap bolak-balik`() {
        val tanpaRuang = RecoveryBundle(accountKey, emptyList())

        assertEquals(tanpaRuang, RecoveryBundle.decode(tanpaRuang.encode()))
    }

    @Test
    fun `kunci akun dan kunci ruang kosong tetap bolak-balik`() {
        val kosong = RecoveryBundle(ByteArray(0), listOf(RecoveryBundle.RoomKeyEntry(RoomId(""), ByteArray(0))))

        assertEquals(kosong, RecoveryBundle.decode(kosong.encode()))
    }

    @Test
    fun `urutan ruang dipertahankan`() {
        val decoded = RecoveryBundle.decode(bundle.encode())

        assertEquals(listOf(RoomId("ruang-keluarga-1"), RoomId("ruang-keluarga-2")), decoded.roomKeys.map { it.roomId })
    }

    @Test
    fun `banyak ruang (puluhan) tetap bolak-balik`() {
        val banyak = RecoveryBundle(
            accountKey,
            (1..40).map { RecoveryBundle.RoomKeyEntry(RoomId("ruang-$it"), byteArrayOf(it.toByte(), (it * 2).toByte())) },
        )

        assertEquals(banyak, RecoveryBundle.decode(banyak.encode()))
    }

    @Test
    fun `satu byte berubah di tengah payload ditolak checksum`() {
        val bytes = bundle.encode()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()

        assertFailsWith<RecoveryBundleCorrupt> { RecoveryBundle.decode(bytes) }
    }

    @Test
    fun `bytes terpotong ditolak, bukan melempar exception lain`() {
        val truncated = bundle.encode().copyOf(3)

        assertFailsWith<RecoveryBundleCorrupt> { RecoveryBundle.decode(truncated) }
    }

    @Test
    fun `bytes kosong ditolak`() {
        assertFailsWith<RecoveryBundleCorrupt> { RecoveryBundle.decode(ByteArray(0)) }
    }

    @Test
    fun `versi struktur tidak dikenal ditolak`() {
        val bytes = bundle.encode()
        bytes[0] = 99

        assertFailsWith<RecoveryBundleCorrupt> { RecoveryBundle.decode(bytes) }
    }

    @Test
    fun `jumlah ruang yang diklaim lebih besar dari sisa bytes ditolak, bukan melempar exception lain`() {
        // Checksum di akhir dibuang supaya yang tersisa hanya header + sebagian data ruang.
        val bytes = bundle.encode()
        val rusak = bytes.copyOf(bytes.size - 10)

        assertFailsWith<RecoveryBundleCorrupt> { RecoveryBundle.decode(rusak) }
    }

    @Test
    fun `toString tidak pernah mencetak isi kunci`() {
        assertEquals("RecoveryBundle(accountKey=8 byte, roomKeys=2 ruang)", bundle.toString())
    }
}
