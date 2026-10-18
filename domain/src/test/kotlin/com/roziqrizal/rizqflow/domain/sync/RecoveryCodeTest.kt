package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden test memakai 4 vektor resmi BIP-39 (trezor/python-mnemonic `vectors.json`, entropi 32
 * byte / 24 kata) — skema kode pemulihan sengaja sama persis dengan BIP-39 24-kata, bukan dirancang
 * sendiri, supaya bisa diverifikasi lawan vektor baku alih-alih cuma lawan kode sendiri.
 */
class RecoveryCodeTest {

    @Test
    fun `entropi nol menghasilkan 24 kata baku, berakhir art`() {
        val code = RecoveryCode.parse(
            ("abandon ".repeat(23) + "art").split(" ")
        )
        assertContentEquals(ByteArray(32), code.entropy)
    }

    @Test
    fun `entropi 0x7f menghasilkan kata baku, berakhir title`() {
        val entropy = ByteArray(32) { 0x7f }
        val expected = "legal winner thank year wave sausage worth useful legal winner thank year " +
            "wave sausage worth useful legal winner thank year wave sausage worth title"
        val code = RecoveryCode.parse(expected.split(" "))
        assertEquals(entropy.toList(), code.entropy.toList())
    }

    @Test
    fun `entropi 0x80 menghasilkan kata baku, berakhir bless`() {
        val expected = "letter advice cage absurd amount doctor acoustic avoid letter advice cage " +
            "absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless"
        val code = RecoveryCode.parse(expected.split(" "))
        assertEquals(ByteArray(32) { 0x80.toByte() }.toList(), code.entropy.toList())
    }

    @Test
    fun `entropi semua 0xff menghasilkan 24 kata zoo, berakhir vote`() {
        val code = RecoveryCode.parse(("zoo ".repeat(23) + "vote").split(" "))
        assertEquals(ByteArray(32) { 0xff.toByte() }.toList(), code.entropy.toList())
    }

    @Test
    fun `generate lalu parse mengembalikan entropi yang sama`() {
        val generated = RecoveryCode.generate()
        val parsed = RecoveryCode.parse(generated.words)
        assertEquals(generated.entropy.toList(), parsed.entropy.toList())
    }

    @Test
    fun `dua kali generate menghasilkan kode berbeda`() {
        val first = RecoveryCode.generate()
        val second = RecoveryCode.generate()
        assert(first.words != second.words)
    }

    @Test
    fun `jumlah kata salah ditolak`() {
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse(listOf("abandon", "abandon")) }
    }

    @Test
    fun `kata tak dikenal ditolak`() {
        val words = ("abandon ".repeat(23) + "art").split(" ").toMutableList()
        words[0] = "bukankata"
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse(words) }
    }

    @Test
    fun `urutan kata tertukar (checksum tidak cocok) ditolak`() {
        val words = ("abandon ".repeat(23) + "art").split(" ").toMutableList()
        val last = words.removeAt(words.size - 1)
        words.add(0, last) // menukar urutan tetap 24 kata valid dari wordlist, tapi checksum pasti tidak cocok
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse(words) }
    }
}
